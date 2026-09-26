package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    /**
     * 起始的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1767225600L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Long nextId(String keyPrefix) {
        //生成时间戳
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //获取当前对应的秒数
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        //获取当前日期 精确到天
        //好处是防止key超过32位自增上限 方便统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = stringRedisTemplate.opsForValue().increment("icr" + keyPrefix + ":" + date);

        //拼接并且返回
        //位运算 timestamp向左移动32位 异或运算
        return (timestamp << COUNT_BITS) | count;
    }

    public static void main(String[] args) {
        // 2026年1月1日0点0分0秒
        LocalDateTime time =
                LocalDateTime.of(2026,1,1,0,0,0);
        // 得到秒数
        long Second = time.toEpochSecond(ZoneOffset.UTC);

        System.out.println("Second:" + Second);
    }
}
