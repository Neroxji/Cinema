package com.cinema.repository;

import com.cinema.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 用户仓库（表 user）。findByUsername 供登录鉴权（task-02 auth 接口）与 DataSeeder 使用。 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按用户名查用户。 */
    Optional<User> findByUsername(String username);
}
