package com.cinema.config;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.model.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 登录校验 + 角色校验拦截器（Session 方案，spec §7.1 演示最简）。
 *
 * <p>职责：
 * <ol>
 *   <li>登录校验：会话中无用户 id → 抛 NOT_LOGGED_IN（HTTP 401，统一响应外壳）</li>
 *   <li>角色校验：handler（方法或类）标注 {@link RequireRole} 时，会话角色不在集合内
 *       → 抛 NO_PERMISSION（HTTP 403）。供 task-02 起的管理端接口挂接。</li>
 * </ol>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 静态资源等非 controller handler 直接放行（拦截器本就只挂 /api/**）
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 1. 登录校验（Session 约定键见 SessionContext）
        HttpSession session = request.getSession(false);
        if (SessionContext.currentUserId(session) == null) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN);
        }
        // 2. 角色校验：优先方法级注解，其次类级注解
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole != null) {
            UserRole current = SessionContext.currentUserRole(session);
            boolean allowed = current != null && Arrays.asList(requireRole.value()).contains(current);
            if (!allowed) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
        }
        return true;
    }
}
