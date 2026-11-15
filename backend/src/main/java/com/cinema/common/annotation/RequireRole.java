package com.cinema.common.annotation;

import com.cinema.model.enums.UserRole;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验注解：标注在 controller 方法或类上，AuthInterceptor 据此校验当前会话角色
 * （spec §7.1：管理端接口按 role 校验，排片员/检票员）。
 *
 * <p>用法（task-02 起的管理端接口挂接）：
 * <pre>{@code
 * @RequireRole(UserRole.SCHEDULER)
 * @PostMapping("/api/admin/movies")
 * public ApiResponse<Movie> create(@RequestBody ...) { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色集合，命中其一即放行。 */
    UserRole[] value();
}
