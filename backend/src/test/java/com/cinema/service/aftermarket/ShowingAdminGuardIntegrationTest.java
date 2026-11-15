package com.cinema.service.aftermarket;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I13/I14 · 场次守卫集成测试 · H2 内存库 test 档（默认 {@code mvn test} 即跑）。
 *
 * <p>用例主体见 {@link AbstractShowingAdminGuardTest}；本类只声明 profile。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("I13/I14 · 场次创建与取消守卫（H2 内存库 test 档）")
class ShowingAdminGuardIntegrationTest extends AbstractShowingAdminGuardTest {
}
