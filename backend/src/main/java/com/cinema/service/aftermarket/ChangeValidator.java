package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.enums.OrderState;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 改签规则校验器（UC303：限同影片 / 开映前 2 小时 / 每单限 1 次，纯逻辑无依赖）。
 *
 * <p>// @UseCase: UC303(改签校验), @UseCase: 验收场景"第二次改签拒绝"
 *
 * <p>规则口径（任务卡 UC303 语义）：
 * <ol>
 *   <li>本人订单 → 否则 12005；</li>
 *   <li>仅 ISSUED（已出票）可改签 → 否则 13010；</li>
 *   <li>每单限 1 次：change_from_showing_id 已留痕（非 null）即已改签过 → 13013；</li>
 *   <li>改签截止：与退票同一时限口径（开映前 BookingConfig.refundDeadline，默认 2 小时）→ 13011。</li>
 * </ol>
 * "限同影片/目标场次有效"依赖仓储查询，留在 ChangeShowingService（本类只做纯逻辑）。
 */
public final class ChangeValidator {

    private ChangeValidator() {
    }

    /**
     * 校验订单可发起改签（UC303 前置规则），任一规则不满足即抛业务异常。
     *
     * @param status            订单当前状态
     * @param ownerId           订单归属用户 id
     * @param requesterId       发起改签的当前登录用户 id
     * @param changedOnce       是否已改签过（change_from_showing_id 非空即 true）
     * @param showingStart      原场次开映时间
     * @param now               当前时间（参数化：纯逻辑可测）
     * @param refundDeadline    改签截止时长（与退票同口径，NFR2 配置项）
     * @throws BizException 12005 非本人 / 13010 状态不可改签 / 13013 已改签过 / 13011 已过改签截止
     */
    public static void assertChangeable(OrderState status, Long ownerId, Long requesterId,
                                        boolean changedOnce, LocalDateTime showingStart,
                                        LocalDateTime now, Duration refundDeadline) {
        if (ownerId == null || !ownerId.equals(requesterId)) {
            throw new BizException(ErrorCode.ORDER_FORBIDDEN, "无权改签他人订单");
        }
        if (status != OrderState.ISSUED) {
            throw new BizException(ErrorCode.CHANGE_NOT_ALLOWED);
        }
        if (changedOnce) {
            throw new BizException(ErrorCode.CHANGE_LIMIT_REACHED);
        }
        LocalDateTime deadline = showingStart.minus(refundDeadline);
        if (!now.isBefore(deadline)) {
            throw new BizException(ErrorCode.CHANGE_DEADLINE_PASSED,
                    "已过改签截止（开映前 " + deadline.toLocalDate() + " " + deadline.toLocalTime()
                            + " 停止受理），不可改签");
        }
    }
}
