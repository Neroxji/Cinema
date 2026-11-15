package com.cinema.model.state;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;

/**
 * 状态机非法迁移异常（State 模式守卫，spec §3.1 行8 / §3.2"状态机拒绝迁移"语义）。
 *
 * <p>异常选型（task-03 决策）：继承 {@link BizException} 以复用 GlobalExceptionHandler 的统一响应外壳
 * （{code, message, data}）；错误码暂复用公共段 INVALID_PARAM(10003)——"状态不符的写操作"本质是
 * 非法请求态（测试计划 A4 口径：业务错误码 + 状态不变）。后续模块若需专用码（C 模块段 13xxx 预留），
 * 仅需改本类 super(...) 一行，不影响状态机代码。
 */
public class IllegalStateTransitionException extends BizException {

    public IllegalStateTransitionException(String message) {
        super(ErrorCode.INVALID_PARAM, message);
    }
}
