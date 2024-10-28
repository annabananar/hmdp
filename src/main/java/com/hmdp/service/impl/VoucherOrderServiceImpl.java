package com.hmdp.service.impl;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
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

            @Override
            public void run() {
                while(true) {
                    try {
                        VoucherOrder currOrder = orderTasks.take();      // take为阻塞型方法
                        handleVoucherOrder(currOrder);
                    } catch (InterruptedException e) {
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
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),        // 这里传空集合，因为没有对应的key参数
                    vId.toString(), userId.toString());
            int r = result.intValue();
            if(r != 0) {
                return Result.fail((r == 1 ? "秒杀券库存不足" : "不能重复下单"));
            }

            long orderId = redisIdWorker.nextId("order");
            // 初始化代理对象
            proxy = (IVoucherOrderService) AopContext.currentProxy();

            // 封装订单信息，加入阻塞队列
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(vId);
            orderTasks.add(voucherOrder);

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
