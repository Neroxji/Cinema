package com.cinema.service.purchase.pay;

/**
 * 支付流程骨架（模板方法，spec §5：参数校验→支付网关→状态迁移→事件广播，骨架固定）。
 *
 * <p>// @Pattern: Template Method(抽象父类——execute 为 final 骨架,
 * <br>四个钩子由子类实现; 具体子类=OnlinePayFlow), @UseCase: UC205(支付出票)
 *
 * <p>骨架四步（不可被子类重排，spec §5"骨架固定"）：
 * <ol>
 *   <li>① 参数与状态校验（{@link #validate}）</li>
 *   <li>② 调用模拟支付网关（{@link #callGateway}）</li>
 *   <li>③ 状态迁移与明细落库（{@link #applyStateChange}）——可能抛
 *       {@link SeatLockLostException} 触发整体事务回滚（UC205-4a/5a）</li>
 *   <li>④ 事件广播（{@link #publishEvents}）——同事务广播，订阅者写库随本事务提交/回滚</li>
 * </ol>
 *
 * <p>事务边界：本类为纯模板（无事务注解），调用方（PayTxRunner.executePayFlow，@Transactional）
 * 圈定事务——①~④ 全程一个事务，③ 抛异常即整体回滚（I6：无脏数据）。
 */
// @Pattern: Template Method(AbstractClass), @UseCase: UC205
public abstract class AbstractPayFlow {

    /**
     * 模板方法（final：骨架固定）：①校验 → ②网关 → ③状态迁移与明细落库 → ④事件广播。
     *
     * @throws com.cinema.common.BizException           ① 的参数/归属/状态类校验失败
     * @throws SeatLockLostException                    ③ 座位已释放/订单已不在待支付态（整体回滚信号）
     */
    public final PayOutcome execute(PayCommand command) {
        PayContext context = validate(command);                  // ① 参数与状态校验
        GatewayResult gateway = callGateway(context);            // ② 调用模拟网关
        PayOutcome outcome = applyStateChange(context, gateway); // ③ 状态迁移与明细落库
        publishEvents(context, outcome);                         // ④ 事件广播
        return outcome;
    }

    /** 钩子①：加载订单、校验本人归属与可支付状态，产出上下文。 */
    protected abstract PayContext validate(PayCommand command);

    /** 钩子②：调用模拟支付网关（失败率可配）。 */
    protected abstract GatewayResult callGateway(PayContext context);

    /**
     * 钩子③：状态迁移与明细落库（订单状态机迁移、座位条件更新售出、order_item 快照、
     * 取票码生成）。座位丢失/订单已取消时抛 {@link SeatLockLostException} 回滚整事务。
     */
    protected abstract PayOutcome applyStateChange(PayContext context, GatewayResult gateway);

    /** 钩子④：按结果广播领域事件（出票成功→TicketIssuedEvent；同事务，通知随本事务落库）。 */
    protected abstract void publishEvents(PayContext context, PayOutcome outcome);
}
