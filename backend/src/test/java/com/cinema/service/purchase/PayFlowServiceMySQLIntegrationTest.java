package com.cinema.service.purchase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * I3~I7 · 支付全链与超时释放集成测试 · 容器 MySQL 8 mysql-test 档复跑（机制照搬 I1/I2）。
 *
 * <p>复跑前置：容器运行中（docker start cinema-mysql）。复跑命令：
 * <pre>mvn test -Dtest=PayFlowServiceMySQLIntegrationTest -Dcinema.mysql.tests=true</pre>
 * 套件级开关 {@code -Dcinema.mysql.tests=true}：缺省时本类跳过（默认 {@code mvn test} 只跑 H2 档）。
 *
 * <p>安全性：用例主体类级 {@code @Transactional} 回滚（I6 方法级 NOT_SUPPORTED 为真实
 * 提交边界，其专用场次夹具在 finally 中删除），对持久库复跑安全、不污染种子数据。
 */
@SpringBootTest
@ActiveProfiles("mysql-test")
@EnabledIfSystemProperty(named = "cinema.mysql.tests", matches = "true",
        disabledReason = "对真 MySQL 复跑需显式开启 -Dcinema.mysql.tests=true 且 cinema-mysql 容器运行中")
@DisplayName("I3~I7 · 支付全链与超时释放（容器 MySQL 8 mysql-test 档复跑）")
class PayFlowServiceMySQLIntegrationTest extends AbstractPayFlowIntegrationTest {
}
