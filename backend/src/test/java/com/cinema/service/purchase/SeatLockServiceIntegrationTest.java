package com.cinema.service.purchase;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I1/I2 · 锁座服务集成测试 · H2 内存库 test 档（默认 {@code mvn test} 即跑）。
 *
 * <p>用例主体见 {@link AbstractSeatLockServiceIntegrationTest}；本类只声明 profile。
 * application-test.yml 强制独立 H2 内存库（不读写演示文件库），与 SeedDataAssertTest 隔离方式一致。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("I1/I2 · SeatLockService（H2 内存库 test 档）")
class SeatLockServiceIntegrationTest extends AbstractSeatLockServiceIntegrationTest {
}
