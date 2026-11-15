package com.cinema.service.aftermarket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I13/I14 · 场次守卫集成测试 · 容器 MySQL 8 mysql-test 档复跑（机制照搬 SeedDataAssertMySQLTest）。
 *
 * <p>复跑命令：{@code mvn test -Dtest=ShowingAdminGuardMySQLIntegrationTest -Dcinema.mysql.tests=true}
 */
@SpringBootTest
@ActiveProfiles("mysql-test")
@EnabledIfSystemProperty(named = "cinema.mysql.tests", matches = "true",
        disabledReason = "对真 MySQL 复跑需显式开启 -Dcinema.mysql.tests=true 且 cinema-mysql 容器运行中")
@DisplayName("I13/I14 · 场次创建与取消守卫（容器 MySQL 8 mysql-test 档）")
class ShowingAdminGuardMySQLIntegrationTest extends AbstractShowingAdminGuardTest {
}
