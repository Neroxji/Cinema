package com.cinema.service.purchase;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I3~I7 · 支付全链与超时释放集成测试 · H2 内存库 test 档（默认 {@code mvn test} 即跑）。
 *
 * <p>用例主体见 {@link AbstractPayFlowIntegrationTest}；本类只声明 profile。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("I3~I7 · 支付全链与超时释放（H2 内存库 test 档）")
class PayFlowServiceIntegrationTest extends AbstractPayFlowIntegrationTest {
}
