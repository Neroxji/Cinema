package com.cinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 购票业务配置中心（单例）：承载 NFR2 全部可配置项——修改配置不改代码。
 *
 * <p>// @Pattern: Singleton(配置中心——Spring 容器默认单例 Bean，@ConfigurationProperties 绑定)
 * <br>// @UseCase: UC204(锁定时长) / UC206(超时释放) / UC302(退票截止) / UC305(候补响应时限)
 *
 * <p>配置来源：application.yml 的 {@code cinema.booking.*}（带中文注释）；
 * 演示档（默认 profile）锁定 30 秒，正式档（mysql profile）覆盖为 15 分钟——"双档"仅由
 * 配置切换，代码无分支。
 *
 * <p>Duration 绑定支持 Spring Boot 简写：30s / 3m / 2h / 15m。
 */
// @Pattern: Singleton(配置中心,Spring单例Bean), @UseCase: UC204/UC206/UC302/UC305
@ConfigurationProperties(prefix = "cinema.booking")
public class BookingConfig {

    /**
     * 座位锁定时长（UC204-4：演示档 30 秒 / 正式档 15 分钟，按 profile 切换）。
     * 超时未支付由定时任务释放（UC206，task-03）。
     */
    private Duration lockDuration = Duration.ofSeconds(30);

    /** 候补递补响应时限（UC305：超时未确认则过期，顺延下一位候补），默认 3 分钟。 */
    private Duration queueResponseLimit = Duration.ofMinutes(3);

    /** 退票截止：开映前多少时长停止受理退票（UC302-4b），默认 2 小时。 */
    private Duration refundDeadline = Duration.ofHours(2);

    /**
     * 模拟支付失败率（UC205-3：失败率可配置，用于测试与演示）。
     * 语义：0.0=必成功；1.0=必失败；(0,1) 区间=按该概率随机失败。
     */
    private Double payFailRate = 0.0;

    /** 单笔订单最多选座数（UC204-3：单笔最多 4 座），默认 4。 */
    private Integer maxSeatsPerOrder = 4;

    /**
     * 锁定超时释放扫描间隔毫秒值（UC206，task-04 追加）：@Scheduled fixedDelayString
     * 直接取用（普通属性占位符，与 Bean 命名无关，重启生效）。默认 1000ms=1 秒——
     * 演示档实时性优先；修改配置不需改代码（NFR2）。
     */
    private Long releaseScanIntervalMillis = 1000L;

    public Duration getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Duration lockDuration) {
        this.lockDuration = lockDuration;
    }

    public Duration getQueueResponseLimit() {
        return queueResponseLimit;
    }

    public void setQueueResponseLimit(Duration queueResponseLimit) {
        this.queueResponseLimit = queueResponseLimit;
    }

    public Duration getRefundDeadline() {
        return refundDeadline;
    }

    public void setRefundDeadline(Duration refundDeadline) {
        this.refundDeadline = refundDeadline;
    }

    public Double getPayFailRate() {
        return payFailRate;
    }

    public void setPayFailRate(Double payFailRate) {
        this.payFailRate = payFailRate;
    }

    public Integer getMaxSeatsPerOrder() {
        return maxSeatsPerOrder;
    }

    public void setMaxSeatsPerOrder(Integer maxSeatsPerOrder) {
        this.maxSeatsPerOrder = maxSeatsPerOrder;
    }

    public Long getReleaseScanIntervalMillis() {
        return releaseScanIntervalMillis;
    }

    public void setReleaseScanIntervalMillis(Long releaseScanIntervalMillis) {
        this.releaseScanIntervalMillis = releaseScanIntervalMillis;
    }
}
