package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource//引入mapper
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送手机验证码
     * @param phone 手机号
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid( phone)){
            //不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }
        //符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码 到redis
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码
        log.info("发送验证码成功：{}",code);
        // 返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
         // 校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        // 从redis 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY +phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            // 不一致，报错
            return Result.fail("验证码错误");
        }
        // 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();// 利用mybatis-plus查询
        // query代表查询语句 eq是查询条件 one是查询返回的是一个值还是一个列表（list）
        // 判断用户是否存在
        if(user == null){
            // 不存在，创建新用户并保存 注册
            user = createUserWithPhone(phone);
        }
        // 保存用户到session

        // 保存用户信息到redis
        //生成一个token
        String token = UUID.randomUUID().toString(true);
        //将user对象转为hash
        //user类型返回的信信息敏感和比较多，占用内存而且不安全，所以用DTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //DTO转换成map
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //保存到redis中
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,usermap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        //返回token
//        session.setAttribute("user",userDTO);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 创建用户 默认值
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
