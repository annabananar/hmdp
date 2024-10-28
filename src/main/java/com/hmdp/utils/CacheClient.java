package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 两个set方法均可以接受任意类型
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        String serializedValue = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, serializedValue, time, timeUnit);
    }

    // with逻辑过期 + client自定义过期时间
    public void setWithLogicalExpiration(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        String serializedValue = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, serializedValue, time, timeUnit);
    }

    // 防止缓存穿透的getter
    public <R, ID> R getWithPassThrough(String prefix, ID id, Class<R> classType, Function<ID, R> func,
                                        Long time, TimeUnit timeUnit) {
        String redisKey = prefix + id;
        String jsonObj = stringRedisTemplate.opsForValue().get(redisKey);
        if(StrUtil.isNotBlank(jsonObj)) {
            return JSONUtil.toBean(jsonObj, classType);
        }
        if(jsonObj == null) return null;
        R result = func.apply(id);
        if(result == null) {                // 缓存穿透，存空值到redis
            stringRedisTemplate.opsForValue().set(redisKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        this.set(redisKey, result, time, timeUnit);
        return result;
    }

    // 防止缓存击穿的getter
    public <R, ID> R getWithLogicalExpire(String keyPrefix, ID id, Class<R> classType, Function<ID, R> func,
                                          Long time, TimeUnit timeUnit) {
        String redisKey = keyPrefix + id;
        String cachedStr = stringRedisTemplate.opsForValue().get(redisKey);
        if(StrUtil.isBlank(cachedStr)) return null;

        RedisData cachedData = JSONUtil.toBean(cachedStr, RedisData.class);
        R res = JSONUtil.toBean((JSONObject) cachedData.getData(), classType);
        if(cachedData.getExpireTime().isAfter(LocalDateTime.now())) {
            return res;
        }

        // 如果热点数据过期，则进行缓存重建（这个时候可以在同一个key做SETNX，因为数据已经过期）
        boolean isLocked = tryLock(redisKey);
        if(isLocked) {
            executor.submit(() -> {                     // 用独立线程更新
                try {
                    R result = func.apply(id);          // result = 新数据
                    this.setWithLogicalExpiration(redisKey, result, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    removeLock(redisKey);
                }
            });
        }
        return res;         // 本次先返回旧数据
    }

    // helper functions
    private boolean tryLock(String cacheKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(cacheKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void removeLock(String cacheKey) {
        stringRedisTemplate.delete(cacheKey);
    }

}
