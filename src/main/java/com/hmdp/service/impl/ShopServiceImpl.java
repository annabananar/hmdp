package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

        @Resource
        StringRedisTemplate myStringRedisTemplate;

        @Resource
        private CacheClient myCacheClient;

        @Override
        public Result queryById(Long id) {
            Shop shop = myCacheClient.getWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                    this::getById, 10L, TimeUnit.SECONDS);
            if(shop == null) return Result.fail("商户不存在");
            return Result.ok(shop);
        }

        /* public Shop queryWithPassThrough(Long id) {
            // 1. 从redis查询商铺是否已被缓存
            String cachedShop = myStringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            // 如果商户存在，直接返回
            if(StrUtil.isNotBlank(cachedShop)) {
                Shop shop = JSONUtil.toBean(cachedShop, Shop.class);
                return shop;
            }
            // 如果命中空字符串，则说明商户不存在
            if(cachedShop != null) {
                return null;
            }

            try {
                // 2. 实施缓存重建，来解决潜在的缓存击穿问题
                boolean isLocked = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
                if(!isLocked) {
                    // 如果获取锁失败，说明已经有人在重建
                    Thread.sleep(50);
                    // 当前线程苏醒之后，通过递归再次尝试（以此循环）
                    return queryWithPassThrough(id);
                }
                // 获取锁成功
                Shop shop = getById(id);
                Thread.sleep(200);
                // 商户不存在：则添加当前id和空字符串到redis，并返回空值（以应对缓存穿透问题）
                if(shop == null) {
                    myStringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                            RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 商户存在：更新redis
                myStringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                            RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            finally {
                // 释放互斥锁
                delLock(RedisConstants.LOCK_SHOP_KEY + id);
            }
        } */

        /* public Shop queryWithLogicExpire(Long id) {
            // 1. 从redis查询商铺是否已被缓存
            String cachedShop = myStringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            // 未命中：直接返回
            if(StrUtil.isBlank(cachedShop)) return null;
            // 命中：判断数据是否过期
            RedisData cachedShopData = JSONUtil.toBean(cachedShop, RedisData.class);
            Shop shop = JSONUtil.toBean((JSONObject) cachedShopData.getData(), Shop.class);
            // 未过期：直接返回店铺信息
            if(cachedShopData.getExpireTime().isAfter(LocalDateTime.now())) {
                return shop;
            }
            // 已过期：缓存重建
            // 获取互斥锁，并用线程池打开一个独立线程
            try {
                boolean isLocked = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
                if(isLocked) {
                    cacheRebuildExec.submit(() -> this.saveShopToRedis(id, 20L));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                delLock(RedisConstants.LOCK_SHOP_KEY + id);
            }
            // 这里返回的是过期信息，但下次会返回最新版本
            return shop;
        } */

        // 通过逻辑过期来解决缓存击穿
        public void saveShopToRedis(Long id, Long expireInSec) {
            // 1. 查询店铺数据
            Shop shop = getById(id);
            // 2. 封装为RedisData object（包含逻辑过期时间）
            RedisData redisData = new RedisData();
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireInSec));
            // 3. 写入redis
            myStringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
        }

        @Override
        @Transactional              // 若其中任意环节出现问题，则rollback全部操作
        public Result updateShopById(Shop shop) {
            // 1. 先更新数据库
            updateById(shop);
            Long id = shop.getId();
            if(id == null) {
                return Result.fail("店铺id不能为空");
            }
            // 2. 再删除缓存
            myStringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
            return Result.ok();
        }

}
