package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿 同时缓存空对象防止缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    private Shop queryWithMutex(Long id) {
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
                //如果命中的是空值，返回错误信息
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
                //重新查询缓存是否建立了
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

    //根据id查询商铺信息主要业务代码
    public Shop queryWithPassThrough(Long id) {
        //从redis查缓存
        String shopkey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);
        if (StrUtil.isNotBlank(shopJson)) {
            //如果缓存中存在，则返回缓存中的数据
            //序列化成对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //查看命中的是否是空值
        if (shopJson != null) {
            //如果命中的是空值，返回错误信息
            return null;
        }
        //如果缓存中不存在，则从数据库中查询
        Shop shop = getById(id);
        //判断商铺是否存在
        if (shop == null) {
            //如果商铺不存在，null写入redis,返回错误信息
            stringRedisTemplate.opsForValue()
                    .set(shopkey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null ;
        }
        //将查询到的数据写入redis缓存
        //序列化成json
        String jsonStr = JSONUtil.toJsonStr(shop);
        //写入redis缓存
        stringRedisTemplate.opsForValue().set(shopkey, jsonStr);
        //设置超时时间
        stringRedisTemplate.expire(shopkey, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
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
        return Result.ok();
    }
}
