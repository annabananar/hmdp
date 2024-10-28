package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final long STARTING_TIME = 1729468800L;

    private static final long LEFT_SHIFT_BITS = 32;

    public long nextId(String prefix) {
        System.out.println("Inside nextId");
        // 计算时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowInLong = now.toEpochSecond(ZoneOffset.UTC);
        long currTimeStamp = nowInLong - STARTING_TIME;
        // 生成序列号（按天设置key，便于统计每天的订单）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        // 拼接 (先左移时间戳 by 32 bits，再加上序列号)
        System.out.println("Current count: " + count);
        return currTimeStamp << LEFT_SHIFT_BITS | count;
    }

}
