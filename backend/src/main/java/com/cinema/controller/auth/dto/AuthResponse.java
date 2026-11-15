package com.cinema.controller.auth.dto;

import com.cinema.model.User;
import com.cinema.model.enums.UserRole;

/**
 * 注册/登录响应（data）：登录成功后服务端已写 Session（Set-Cookie: JSESSIONID）。
 */
public record AuthResponse(

        /** 用户 id。 */
        Long userId,

        /** 用户名。 */
        String username,

        /** 角色：AUDIENCE / SCHEDULER / CHECKER。 */
        UserRole role
) {
    public static AuthResponse from(User user) {
        return new AuthResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
