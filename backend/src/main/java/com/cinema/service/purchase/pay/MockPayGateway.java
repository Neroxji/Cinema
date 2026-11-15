package com.cinema.service.purchase.pay;

import com.cinema.config.BookingConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

/**
 * 模拟支付网关（UC205-3）：不接入真实渠道，失败率可配置（NFR2 / 测试与演示用）。
 *
 * <p>// @UseCase: UC205(模拟支付网关), @UseCase: I4(必败)/I3(必成)/验收(随机)
 *
 * <p>失败率语义（{@code cinema.booking.pay-fail-rate}，BookingConfig 承载）：
 * 0.0=必成功；1.0=必失败；(0,1) 区间=按该概率随机失败。
 */
// @UseCase: UC205
@Component
public class MockPayGateway {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingConfig bookingConfig;

    public MockPayGateway(BookingConfig bookingConfig) {
        this.bookingConfig = bookingConfig;
    }

    /**
     * 模拟扣款。
     *
     * @param orderNo 订单号（流水引用）
     * @param amount  支付金额（模拟渠道仅记录，不实际结算）
     * @return 网关结果（成功含模拟流水号）
     */
    public GatewayResult charge(String orderNo, BigDecimal amount) {
        Double failRate = bookingConfig.getPayFailRate();
        boolean fail;
        if (failRate == null || failRate <= 0d) {
            fail = false;
        } else if (failRate >= 1d) {
            fail = true;
        } else {
            fail = RANDOM.nextDouble() < failRate;
        }
        if (fail) {
            return GatewayResult.failure("模拟网关返回支付失败（失败率档位：" + failRate + "）");
        }
        String txnNo = "MOCK" + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(LocalDateTime.now()) + String.format("%04d", RANDOM.nextInt(10000));
        return GatewayResult.success(txnNo);
    }
}
