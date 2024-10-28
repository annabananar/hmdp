package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private StringRedisTemplate stringRedisTemplate;

    private String key;

    private static final String KEY_PREFIX = "Lock:";

    private static final String VM_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 脚本会在类初始化的时候加载完成
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("Unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String key) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.key = key;
    }

    @Override
    // 不要在方法里写死key，方便不同业务调用
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = VM_PREFIX + Thread.currentThread().getId();
        // 执行setNX
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, threadId, timeoutSec, TimeUnit.SECONDS);
        // 防止空指针Exception
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void delLock() {
        // 调用lua脚本执行，防止业务阻塞（有时候JVM full GC会阻塞业务）
        // 保证原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + key),
                VM_PREFIX + Thread.currentThread().getId()
        );
    }

    /* @Override
    public void delLock() {
        String lockedThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + key);
        String currThreadId = VM_PREFIX + Thread.currentThread().getId();
        // 先判断锁是否属于当前线程，防止误删
        if(lockedThreadId.equals(currThreadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + key);
        }
    } */

}
