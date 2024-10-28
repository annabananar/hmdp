package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate myStringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {

        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        log.debug("Phone: " + phone);
        myStringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // session.setAttribute("code", code);

        log.debug("短信验证码发送成功" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO form, HttpSession session) {
        // 1. 分别校验手机号和验证码
        String phone = form.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // String cachedCode = (String) session.getAttribute("code");
        String cachedCode = myStringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(cachedCode == null | !form.getCode().equals(cachedCode)) {
            return Result.fail("验证码错误");
        }
        // 2. DB是否包含手机号？Y -> 登录；N -> 注册
        User currUser = query().eq("phone", phone).one();
        if(currUser == null) {
            currUser = createNewUserWithPhone(phone);
        }
        // TODO: hash UserDTO里的信息到Redis，并根据uuid创造token作为每个UserDTO的key
        String token = UUID.randomUUID().toString();
        UserDTO currUserDTO = BeanUtil.copyProperties(currUser, UserDTO.class);
        // StringRedisTemplate需要<String, String>的key-value pair，所以这里需要将DTO的每个字段
        // 转换成String类型，以储存到map当中
        Map<String, Object> currUserMap = BeanUtil.beanToMap(currUserDTO, new HashMap<>(),
                        CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        /*
           3. 保存用户信息（手机号）到session
           session.setAttribute("user", currUserDTO);
        */
        myStringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, currUserMap);
        myStringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, 45, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createNewUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);         // 保存新用户到DB
        return user;
    }
}
