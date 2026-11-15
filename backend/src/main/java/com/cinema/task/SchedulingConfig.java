package com.cinema.task;

import com.cinema.config.BookingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关配置（task-04）：启用 @Scheduled 扫描（UC206 锁定超时释放）。
 *
 * <p>// @UseCase: UC206（@Scheduled 超时释放任务的调度开关）
 *
 * <p>独立成类而非在主启动类加注解：task-04 不改既有主类，追加式交付。
 * 扫描间隔读取 {@link BookingConfig#getReleaseScanIntervalMillis()}
 * （application.yml 的 {@code cinema.booking.release-scan-interval}，默认 1s，NFR2 可配置）。
 */
// @UseCase: UC206
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
