package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RefreshInterceptor implements HandlerInterceptor {

    private StringRedisTemplate myStringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate myStringRedisTemplate) {
        this.myStringRedisTemplate = myStringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        /*
            1. 获取session: HttpSession currSession = request.getSession();
        */
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            System.out.println("Token is blank");
            return true;
        }

        // TODO: 将redis中的map转换成UserDTO object
        Map<Object, Object> currUserMap = myStringRedisTemplate.opsForHash().
                entries(RedisConstants.LOGIN_USER_KEY + token);
        if(currUserMap.isEmpty()) {         // 拦截交给LoginInterceptor来实现
            System.out.println("user empty");
            return true;
        }
        /*  2. 获取session中的user，查询用户是否存在
            N - 直接拦截
            if (user == null) {
                response.setStatus(401);
                return false;
            }
        */

        UserDTO currUserDTO = BeanUtil.fillBeanWithMap(currUserMap, new UserDTO(), false);
        UserHolder.saveUser(currUserDTO);       // 保存用户到ThreadLocal

        // TODO: 更新Redis中对应token的状态
        myStringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                            RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

}