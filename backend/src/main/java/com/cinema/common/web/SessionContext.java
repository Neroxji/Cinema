package com.cinema.common.web;

import com.cinema.model.User;
import com.cinema.model.enums.UserRole;
import jakarta.servlet.http.HttpSession;

/**
 * 当前登录用户读写助手（Session + Cookie 方案，spec §7.1 演示最简，不做 JWT）。
 *
 * <p>HttpSession 约定键：
 * <ul>
 *   <li>{@link #KEY_USER_ID}：登录用户 id</li>
 *   <li>{@link #KEY_USERNAME}：登录用户名</li>
 *   <li>{@link #KEY_ROLE}：角色枚举名</li>
 * </ul>
 * 登录/登出由 task-02 的 /api/auth/** 接口调用；读取由 AuthInterceptor 与各业务接口使用。
 */
public final class SessionContext {

    /** 会话键：登录用户 id。 */
    public static final String KEY_USER_ID = "cinema.session.userId";

    /** 会话键：登录用户名。 */
    public static final String KEY_USERNAME = "cinema.session.username";

    /** 会话键：登录角色（存枚举 name 字符串）。 */
    public static final String KEY_ROLE = "cinema.session.role";

    private SessionContext() {
    }

    /** 写入登录态（登录成功后调用）。 */
    public static void login(HttpSession session, User user) {
        session.setAttribute(KEY_USER_ID, user.getId());
        session.setAttribute(KEY_USERNAME, user.getUsername());
        session.setAttribute(KEY_ROLE, user.getRole().name());
    }

    /** 清除登录态并销毁会话（登出时调用）。 */
    public static void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    /** 当前登录用户 id；未登录返回 null。 */
    public static Long currentUserId(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(KEY_USER_ID);
        return value instanceof Long userId ? userId : null;
    }

    /** 当前登录用户名；未登录返回 null。 */
    public static String currentUsername(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(KEY_USERNAME);
        return value == null ? null : value.toString();
    }

    /** 当前登录角色；未登录返回 null。 */
    public static UserRole currentUserRole(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(KEY_ROLE);
        return value == null ? null : UserRole.valueOf(value.toString());
    }

    /** 是否已登录。 */
    public static boolean isLoggedIn(HttpSession session) {
        return currentUserId(session) != null;
    }
}
