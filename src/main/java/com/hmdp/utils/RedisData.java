package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // 过期时间
    private LocalDateTime expireTime;
    // 缓存进Redis的数据，取出时可以typecast为原实体
    private Object data;
}
