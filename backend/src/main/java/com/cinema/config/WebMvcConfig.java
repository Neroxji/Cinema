package com.cinema.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录/角色校验拦截器（Session + Cookie 方案，spec §7.1）。
 *
 * <p>放行范围约定：
 * <ul>
 *   <li>/api/auth/**（注册/登录，UC201）显式放行</li>
 *   <li>静态资源与页面不在 /api/** 路径内，天然不受拦截</li>
 *   <li>其余 /api/** 一律要求登录；角色校验由接口上的 @RequireRole 注解声明</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
