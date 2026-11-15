package com.cinema.service.aftermarket;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I8~I12+跨模块 · C 模块售后链集成测试 · H2 内存库 test 档（默认 {@code mvn test} 即跑）。
 *
 * <p>用例主体见 {@link AbstractAftermarketIntegrationTest}；本类只声明 profile。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("I8~I12+跨模块 · C 模块售后链（H2 内存库 test 档）")
class AftermarketIntegrationTest extends AbstractAftermarketIntegrationTest {
}
