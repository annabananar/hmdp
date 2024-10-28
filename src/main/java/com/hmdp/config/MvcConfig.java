package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate myStringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // the smaller the order, the earlier the interceptor gets executed: order(0) > order(1)
        // default pattern = "/**"
        registry.addInterceptor(new RefreshInterceptor(myStringRedisTemplate))
                .addPathPatterns("/**");
        // 指定拦截哪些路径
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login", "/user/code",
                        "/shop-type/**", "/shop/**",
                        "/blog/hot",
                        "/voucher/**", "/voucher-order/**"
                );
    }
}
