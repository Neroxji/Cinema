package com.cinema.service.auth;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.PasswordHasher;
import com.cinema.model.User;
import com.cinema.model.enums.UserRole;
import com.cinema.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册/登录服务（UC201，公共段——三类角色均可登录，注册仅开放观众角色）。
 *
 * <p>// @UseCase: UC201(注册与登录)
 *
 * <p>约定（task-04 决策，字段表-B模块.md 同口径）：
 * <ul>
 *   <li>注册只建 AUDIENCE（观众）账号——排片员/检票员由种子数据/管理侧提供；</li>
 *   <li>密码哈希存储（PasswordHasher：SHA-256+盐），登录校验失败统一"用户名或密码错误"
 *       （12007，不区分具体原因防用户名枚举）；</li>
 *   <li>登录成功写入 Session（SessionContext，Session+Cookie 方案，spec §7.1）。</li>
 * </ul>
 */
// @UseCase: UC201
@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 注册观众账号（UC201）：重名拒绝（12006）。 */
    @Transactional
    public User register(String username, String rawPassword, String phone) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new BizException(ErrorCode.USERNAME_TAKEN);
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(PasswordHasher.encode(rawPassword));
        user.setRole(UserRole.AUDIENCE);
        user.setPhone(phone);
        return userRepository.save(user);
    }

    /** 登录（UC201）：三类角色均可；成功建立会话（调用方写 Session），失败 12007。 */
    @Transactional(readOnly = true)
    public User verifyLogin(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.LOGIN_FAILED));
        if (!PasswordHasher.verify(rawPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        return user;
    }
}
