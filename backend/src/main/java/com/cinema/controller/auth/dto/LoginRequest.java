package com.cinema.controller.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（POST /api/auth/login，UC201）：三类角色均可登录。
 */
public record LoginRequest(

        /** 用户名。 */
        @NotBlank(message = "用户名不能为空")
        String username,

        /** 密码（明文传输于本机演示环境，服务端只存哈希）。 */
        @NotBlank(message = "密码不能为空")
        String password
) {
}
