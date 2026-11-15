package com.cinema.service.aftermarket.command;

import com.cinema.service.aftermarket.ChangeShowingService;

/**
 * 改签命令（UC303，命令模式 · ConcreteCommand 角色——封装对 ChangeShowingService 的调用并实现 undo）。
 *
 * <p>// @Pattern: Command(ConcreteCommand——execute 调用 Receiver.doChange,
 * <br>undo 调用 Receiver.undoChange), @UseCase: UC303(改签至同影片其他场次)
 *
 * <p>undo 语义：换回原场次（释放新场次座位 + 原座位恢复 + 留痕还原），原座位被重售则拒绝。
 */
// @Pattern: Command(ConcreteCommand), @UseCase: UC303
public class ChangeShowingCommand implements AftermarketCommand {

    /** Receiver：改签业务实现。 */
    private final ChangeShowingService receiver;

    private final Long orderId;
    private final Long requesterId;
    private final Long targetShowingId;

    /** 执行结果快照（undo 补偿依据；execute 成功后填充）。 */
    private ChangeShowingService.ChangeOutcome executedOutcome;

    public ChangeShowingCommand(ChangeShowingService receiver, Long orderId, Long requesterId,
                                Long targetShowingId) {
        this.receiver = receiver;
        this.orderId = orderId;
        this.requesterId = requesterId;
        this.targetShowingId = targetShowingId;
    }

    @Override
    public String name() {
        return "ChangeShowingCommand";
    }

    /** 执行改签（UC303 全语义：校验→原座位释放→新场次锁座出票→留痕→事件广播）。 */
    @Override
    public Object execute() {
        executedOutcome = receiver.doChange(orderId, requesterId, targetShowingId);
        return executedOutcome;
    }

    /** 撤销改签（补偿语义：换回原场次，守卫见 ChangeShowingService.undoChange）。 */
    @Override
    public void undo() {
        if (executedOutcome == null) {
            throw new IllegalStateException("ChangeShowingCommand 尚未执行，不可撤销");
        }
        receiver.undoChange(executedOutcome);
    }

    /** 已执行结果（只读快照）。 */
    public ChangeShowingService.ChangeOutcome outcome() {
        return executedOutcome;
    }
}
