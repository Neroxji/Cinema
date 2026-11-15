package com.cinema.service.admin.strategy;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.enums.PriceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 票价策略解析器：按 {@link PriceType} 在三档预设策略间分发（策略模式的上下文辅助角色）。
 *
 * <p>// @Pattern: Strategy(上下文辅助：持有全部具体策略并按类型分发，
 * <br>调用方只依赖抽象接口，不感知具体实现), @UseCase: UC105/204/205
 *
 * <p>构造注入全部 {@link PriceStrategy} Bean 并建立类型索引；重复类型视为装配错误。
 * 纯逻辑类，单元测试可直接 {@code new PriceStrategyResolver(List.of(...))} 构造。
 */
// @Pattern: Strategy(上下文), @UseCase: UC105/204/205
@Component
public class PriceStrategyResolver {

    /** 策略类型 → 策略实现索引。 */
    private final Map<PriceType, PriceStrategy> strategies = new EnumMap<>(PriceType.class);

    public PriceStrategyResolver(List<PriceStrategy> candidates) {
        for (PriceStrategy strategy : candidates) {
            PriceStrategy previous = strategies.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException("票价策略类型重复注册：" + strategy.type());
            }
        }
    }

    /**
     * 按类型取策略；类型为 null 或未注册时抛参数非法
     * （priceType 缺失走 UC103-3a 默认逻辑，此处为非法值的兜底）。
     */
    public PriceStrategy of(PriceType type) {
        PriceStrategy strategy = type == null ? null : strategies.get(type);
        if (strategy == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "参数非法：未知的票价策略类型");
        }
        return strategy;
    }
}
