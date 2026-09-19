package com.hmdp.service.impl;

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

import javax.annotation.Resource;

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
        //从redis查缓存
        String shopkey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopkey);
        if (StrUtil.isNotBlank(shopJson)) {
            //如果缓存中存在，则返回缓存中的数据
            //序列化成对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //如果缓存中不存在，则从数据库中查询
        Shop shop = getById(id);
        //判断商铺是否存在
        if (shop == null) {
            //如果商铺不存在，返回错误信息
            return Result.fail("商铺不存在");
        }
        //将查询到的数据写入redis缓存
        //序列化成json
        String jsonStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(shopkey, jsonStr);
        //返回数据
        return Result.ok(shop);
    }
}
