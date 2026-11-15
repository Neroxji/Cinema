package com.cinema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 电影票在线购票系统 · 主启动类。
 *
 * <p>技术栈：JDK 17 + Spring Boot 3.x + Spring Data JPA；演示档 H2（MySQL 兼容模式），
 * 正式档 MySQL（--spring.profiles.active=mysql）。
 *
 * <p>{@code @ConfigurationPropertiesScan}：扫描注册 BookingConfig（单例配置中心，NFR2）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CinemaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CinemaApplication.class, args);
    }
}
