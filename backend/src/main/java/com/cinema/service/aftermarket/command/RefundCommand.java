package com.cinema.service.aftermarket.command;

import com.cinema.service.aftermarket.RefundService;

/**
 * 退票命令（UC302，命令模式 · ConcreteCommand 角色——封装对 RefundService 的调用并实现 undo）。
 *
 * <p>// @Pattern: Command(ConcreteCommand——execute 调用 Receiver.doRefund,
 * <br>undo 调用 Receiver.undoRefund), @UseCase: UC302(申请退票)
 *
 * <p>持有撤销补偿所需的执行结果快照（座位集合），undo 时交回 Receiver 守卫恢复。
 */
// @Pattern: Command(ConcreteCommand), @UseCase: UC302
public class RefundCommand implements AftermarketCommand {

    /** Receiver：退票业务实现。 */
    private final RefundService receiver;

    private final Long orderId;
    private final Long requesterId;

    /** 执行结果快照（undo 补偿依据；execute 成功后填充）。 */
    private RefundService.RefundOutcome executedOutcome;

    public RefundCommand(RefundService receiver, Long orderId, Long requesterId) {
        this.receiver = receiver;
        this.orderId = orderId;
        this.requesterId = requesterId;
    }

    @Override
    public String name() {
        return "RefundCommand";
    }

    /** 执行退票（UC302 全语义：校验→状态机→明细失效→座位释放→事件广播）。 */
    @Override
    public Object execute() {
        executedOutcome = receiver.doRefund(orderId, requesterId);
        return executedOutcome;
    }

    /** 撤销退票（补偿语义：座位未被重售时恢复 ISSUED/明细有效/座位已售，守卫见 RefundService.undoRefund）。 */
    @Override
    public void undo() {
        if (executedOutcome == null) {
            throw new IllegalStateException("RefundCommand 尚未执行，不可撤销");
        }
        receiver.undoRefund(executedOutcome);
    }

    /** 已执行结果（只读快照）。 */
    public RefundService.RefundOutcome outcome() {
        return executedOutcome;
    }
}
