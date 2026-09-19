package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryTypeList() {
        //从redis中查询缓存
        List<String> typeJsonList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //缓存不为空
        if (typeJsonList != null && typeJsonList.size() > 0) {
            //缓存不为空，直接返回
            //序列化
            List<ShopType> typeList = typeJsonList.stream()
                    .map(typeJson -> JSONUtil.toBean(typeJson, ShopType.class))
                    .collect(Collectors.toList());
            return typeList;
        }
        //缓存为空，查询数据库
        List<ShopType> typeList = query()
                .orderByAsc("sort")
                .list();
        //数据库没用
        if(typeList == null || typeList.isEmpty()){
            return typeList;
        }
        //将数据库查询结果写入redis
        //typelist序列化json
        List<String> typeListJson = typeList.stream()
                .map(type -> JSONUtil.toJsonStr(type))
                .collect(Collectors.toList());
        //写入redis
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, typeListJson);
        return typeList;
    }
}
