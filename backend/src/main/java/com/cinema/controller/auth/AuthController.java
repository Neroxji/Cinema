package com.cinema.controller.auth;

import com.cinema.common.ApiResponse;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.auth.dto.AuthResponse;
import com.cinema.controller.auth.dto.LoginRequest;
import com.cinema.controller.auth.dto.RegisterRequest;
import com.cinema.model.User;
import com.cinema.service.auth.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共段鉴权接口（spec §2 公共段，UC201）。
 *
 * <p>// @UseCase: UC201(注册与登录)
 *
 * <p>注册仅开放观众角色；登录三类角色均可（Session + Cookie，spec §7.1）。
 * 本组接口在 WebMvcConfig 拦截器放行清单内（未登录可访问）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册观众账号（UC201）：成功即返回账号信息（角色固定 AUDIENCE），需再登录建立会话。 */
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.username(), request.password(), request.phone());
        return ApiResponse.ok(AuthResponse.from(user));
    }

    /** 登录并建立会话（UC201）：成功写 Session（Set-Cookie: JSESSIONID），失败 12007。 */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpSession session) {
        User user = authService.verifyLogin(request.username(), request.password());
        SessionContext.login(session, user);
        return ApiResponse.ok(AuthResponse.from(user));
    }
}
