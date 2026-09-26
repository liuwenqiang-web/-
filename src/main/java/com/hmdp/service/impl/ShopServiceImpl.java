package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

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
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //工具类解决缓存穿透
        //Shop shop = cacheClient.get(RedisConstants.CACHE_SHOP_KEY, id, Shop.class ,
        //          this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁解决缓存击穿 同时缓存空对象防止缓存穿透

        //  Shop shop = queryWithLogicalExpire(id);
        //封装工具类逻辑过期 解决缓存击穿

        //分流：先查询热点通道 只有预热的商铺
        //先查询热点通道 只有预热的商铺
          Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_REDISDATA_SHOP_KEY, id, Shop.class,
                  this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //不是热点商铺 走普通通道
          if(shop == null){
            shop = queryWithMutex(id);
        }
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 根据id查询商铺信息  互斥锁解决缓存击穿 null解决缓存穿透
     * @param id 商铺id
     * @return 商铺信息
     */
    private Shop queryWithMutex(Long id) {
        //缓存击穿：查询缓存
        String shopkey = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        while (true){
            //查缓存
            String shopJson = stringRedisTemplate.opsForValue().get(shopkey);
            if (StrUtil.isNotBlank(shopJson)) {
                //如果缓存中存在，则返回缓存中的数据
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                //如果命中的是空值，返回错误信息 防止缓存穿透
                return null;
            }
            //获取锁
            if(!tryLock(lockKey)){
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;//继续循环，等待锁
            }

            //抢到锁：重建缓存（能进这里说明锁一定在手，finally 无脑解锁）
            try{
                //重新查询缓存是否已经被其他线程建立了
                shopJson = stringRedisTemplate.opsForValue().get(shopkey);
                if (StrUtil.isNotBlank(shopJson)) {
                    //如果缓存中存在，则返回缓存中的数据
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
                //如果缓存中不存在，则从数据库中查询
                Shop shop = getById(id);
                if (shop == null) {
                    //如果商铺不存在，null写入redis,返回错误信息
                    stringRedisTemplate.opsForValue()
                            .set(shopkey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null ;
                }
                //将查询到的数据写入redis缓存
                stringRedisTemplate.opsForValue()
                        .set(shopkey, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }finally {
                unlock(lockKey);
            }
        }
    }


    //根据id查询商铺信息主要业务代码 缓存击穿
    public Shop queryWithPassThroughAndMutex(Long id) {
        //从redis查缓存
        String shopkey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);
        if (StrUtil.isNotBlank(shopJson)) {//isNotBlank 判断是否不为空 null也判断为false
            //如果缓存中存在，则返回缓存中的数据
            //序列化成对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //查看命中的是否是空值
        if (shopJson != null) {
            //如果命中的是空值，返回错误信息
            return null;
        }

        //获取锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
       try{
           boolean isLocked = tryLock(lockKey);
           if (!isLocked) {
               //失败休眠重试
               Thread.sleep(50);
               return queryWithPassThroughAndMutex(id);
           }
           //成功 查询数据库
           //再查一遍redis
           shopJson = stringRedisTemplate.opsForValue().get(shopkey);
           if (StrUtil.isNotBlank(shopJson)) {
               //如果缓存中存在，则返回缓存中的数据
               return JSONUtil.toBean(shopJson, Shop.class);
           }
           //真的不存在 从数据库查数据
           shop = getById(id);
           //判断商铺是否存在
           if (shop == null) {
               //如果商铺不存在，null写入redis,返回错误信息
               stringRedisTemplate.opsForValue()
                       .set(shopkey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
               return null ;
           }
           //将查询到的数据写入redis缓存
           stringRedisTemplate.opsForValue()
                   .set(shopkey, JSONUtil.toJsonStr(shop),
                           RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
       }catch (InterruptedException e){
           throw new RuntimeException(e);
       }finally {
           unlock(lockKey);
       }
       //返回数据
        return shop;
    }

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


    @Override
    @Transactional//开启事务
    public Result updateCacheById(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺id不能为空");
        }
        //先更新数据库中的数据
        updateById(shop);
        //再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        stringRedisTemplate.delete(RedisConstants.CACHE_REDISDATA_SHOP_KEY + id);
        return Result.ok();
    }

}
