package com.cinema.controller.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（POST /api/auth/register，UC201）：仅创建观众（AUDIENCE）账号。
 */
public record RegisterRequest(

        /** 用户名（2~50 字，全库唯一）。 */
        @NotBlank(message = "用户名不能为空")
        @Size(min = 2, max = 50, message = "用户名长度须在 2~50 之间")
        String username,

        /** 密码（6~32 字，哈希存储）。 */
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度须在 6~32 之间")
        String password,

        /** 手机号（可选，≤20 字）。 */
        @Size(max = 20, message = "手机号过长")
        String phone
) {
}
