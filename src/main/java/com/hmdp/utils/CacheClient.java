package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Component//表示这是一个Spring管理的组件
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将数据保存到Redis中
     * @param key
     * @param value
     * @param time
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //value封装redisDtata 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 获取缓存中的数据，存在则返回，不存在则返回空 只解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R get(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis中获取数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {//isNotBlank 判断是否不为空，null也判断为false
            //命中 直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是不是空值 缓存穿透
        if(json != null){
            //返回一个错误信息
            return null;
        }

        //真的未命中 根据id查询数据库
        R r = dbFallback.apply(id);//这个地方就是传进来的函数
        if (r == null) {
            //存在 放入redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //存在 写入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);//线程池

    //获取lock
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //Boolean是包装类，需要判断是否为true
        return BooleanUtil.isTrue(flag);
    }

    //释放lock
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 逻辑过期缓存 查询缓存 解决缓存击穿
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        //从redis查缓存
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(Json)) {
            //缓存未命中 返回本次商品不在活动范围内
            return null ;
        }
        //缓存命中判断逻辑过期时间
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//店铺数据
        LocalDateTime expireTime = redisData.getExpireTime();//逻辑过期时间
        //未过期直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期直接返回
            return r;
        }
        //过期了开始获取互斥锁
        //获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLocked = tryLock(lockKey);
        if (isLocked) {
            //获取锁成功，第二次查询缓存
            Json = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData2 = JSONUtil.toBean(Json, RedisData.class);
            R r2 = JSONUtil.toBean((JSONObject) redisData2.getData(), type  );//店铺数据
            LocalDateTime expireTime2 = redisData2  .getExpireTime();//逻辑过期时间
            //未过期直接返回
            if (expireTime2.isAfter(LocalDateTime.now())) {
                //释放锁
                unlock(lockKey);
                //未过期直接返回
                return r2;
            }
            //还是过期，开始进行缓存重建
            //利用线程池
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);//释放锁
                }
            });

        }
        //获取锁失败，返回过期数据
        return r;
    }
}
