package com.cinema.model;

import com.cinema.model.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 用户实体（表 user）：三类角色账户（观众/排片员/检票员），spec §1。
 *
 * <p>被 ticket_order / queue 引用；密码哈希格式 {@code salt$sha256hex}（见 PasswordHasher）。
 */
@Entity
@Table(name = "`user`") // user 在 H2 中为关键字，统一加引号规避（MySQL 侧兼容反引号）
public class User {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用户名（唯一索引 uk_user_username）。 */
    @Column(name = "username", length = 50, nullable = false)
    private String username;

    /** 密码哈希（不可存明文）。 */
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    /** 角色：AUDIENCE 观众 / SCHEDULER 排片员 / CHECKER 检票员。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private UserRole role;

    /** 手机号。 */
    @Column(name = "phone", length = 20)
    private String phone;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
