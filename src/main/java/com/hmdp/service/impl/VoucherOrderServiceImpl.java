package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

        @Resource
        private ISeckillVoucherService secKillVoucherService;

        @Resource
        private RedisIdWorker redisIdWorker;

        @Resource
        private StringRedisTemplate stringRedisTemplate;

        @Resource
        private RedissonClient redissonClient;

        private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
        static {
            SECKILL_SCRIPT = new DefaultRedisScript<>();
            SECKILL_SCRIPT.setLocation(new ClassPathResource("SecKill.lua"));
            SECKILL_SCRIPT.setResultType(Long.class);
        }

        // 阻塞队列（只有元素可用时，才会被唤醒）
        private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

        private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

        @PostConstruct
        private void init() {
            EXECUTOR_SERVICE.submit(new VoucherOrderHandler());
        }

        // 独立线程，异步下单 & 更新数据库信息
        private class VoucherOrderHandler implements Runnable {

            String queueName = "stream.orders";

            @Override
            public void run() {
                while(true) {
                    try {
                        // 1. 获取redis stream中的消息
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create(queueName, ReadOffset.lastConsumed())
                        );
                        // 2. 判断消息获取是否成功
                        if(list == null || list.isEmpty()) continue;
                        // 3. 创建订单
                        MapRecord<String, Object, Object> order = list.get(0);
                        Map<Object, Object> values = order.getValue();
                        VoucherOrder currOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        handleVoucherOrder(currOrder);
                        // 4. ACK确认（告诉消息队列，最新消息已经被处理）
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", order.getId());
                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                        handlePendingList();
                    }
                }
            }

            private void handlePendingList() {
                while(true) {
                    try {
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create(queueName, ReadOffset.from("0"))
                        );
                        if(list == null || list.isEmpty()) break;
                        MapRecord<String, Object, Object> order = list.get(0);
                        Map<Object, Object> values = order.getValue();
                        VoucherOrder currOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        handleVoucherOrder(currOrder);
                        stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", order.getId());
                    } catch (Exception e) {
                        log.error("处理订单异常", e);
                    }
                }
            }
        }

        private IVoucherOrderService proxy;

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            // 创建锁对象（以用户为单位），获取锁，判断是否成功；防止并发安全问题
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLocked = lock.tryLock();
            if (!isLocked) {
                log.error("不允许重复下单");
                return;
            }
            try {
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Result secKillVoucher(Long vId) {
            // 执行lua脚本
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),        // 这里传空集合，因为没有对应的key参数
                    vId.toString(), userId.toString(), String.valueOf(orderId));
            int r = result.intValue();
            if(r != 0) {
                return Result.fail((r == 1 ? "秒杀券库存不足" : "不能重复下单"));
            }
            // 初始化代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            return Result.ok(orderId);
        }

        @Transactional
        // 不建议在declaration上加锁，不然会辐射到整个VoucherOrderServiceImpl对象
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            // 一人一单，查询当前userId是否存在
            Long userId = voucherOrder.getUserId();
            Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
            if(count > 0) {         // 虽然前面redis已经做过判断，但可以以防万一
                log.error("用户已购买过当前秒杀券");
                return;
            }
            // 扣减库存，创建订单
            boolean success = secKillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder).gt("stock", 0)
                    .update();
            if(!success) {
                log.error("库存不足");
                return;
            }
            save(voucherOrder);
        }

        /*
        * 秒杀下单（未优化版）
            @Override
            public Result secKillVoucher(Long vId) {
                SeckillVoucher seckillVoucher = secKillVoucherService.getById(vId);
                // 时间错误
                if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
                    return Result.fail("秒杀尚未开始");
                }
                if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
                    return Result.fail("秒杀已经结束");
                }
                // 判断秒杀券库存是否充足
                if(seckillVoucher.getStock() < 1) {
                    return Result.fail("秒杀券库存不足");
                }

                Long userId = UserHolder.getUser().getId();
                // 锁住当前用户，以处理并发安全问题
                // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
                RLock lock = redissonClient.getLock("order:" + userId);
                boolean isLocked = lock.tryLock();
                if(!isLocked) {
                    return Result.fail("不允许重复下单");
                }
                // 获取VoucherOrderServiceImpl的代理对象，防止事务失败
                try {
                    IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
                    return voucherOrderService.createVoucherOrder(vId);
                } finally {
                    lock.unlock();
                }
            }
        */
}
