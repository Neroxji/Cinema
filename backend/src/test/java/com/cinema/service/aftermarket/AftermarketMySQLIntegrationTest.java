package com.cinema.service.aftermarket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I8~I12+跨模块 · C 模块售后链集成测试 · 容器 MySQL 8 mysql-test 档复跑（机制照搬 SeedDataAssertMySQLTest）。
 *
 * <p>复跑前置：容器运行中（docker start cinema-mysql）。复跑命令：
 * <pre>mvn test -Dtest=AftermarketMySQLIntegrationTest -Dcinema.mysql.tests=true</pre>
 * 套件级开关 {@code -Dcinema.mysql.tests=true}：缺省时本类跳过。
 */
@SpringBootTest
@ActiveProfiles("mysql-test")
@EnabledIfSystemProperty(named = "cinema.mysql.tests", matches = "true",
        disabledReason = "对真 MySQL 复跑需显式开启 -Dcinema.mysql.tests=true 且 cinema-mysql 容器运行中")
@DisplayName("I8~I12+跨模块 · C 模块售后链（容器 MySQL 8 mysql-test 档）")
class AftermarketMySQLIntegrationTest extends AbstractAftermarketIntegrationTest {
}
