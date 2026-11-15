package com.cinema.service.aftermarket.command;

/**
 * 售后命令接口（命令模式 · Command 角色，spec §5：退改签以命令对象承载，均实现 undo）。
 *
 * <p>// @Pattern: Command(Command角色), @UseCase: UC302(退票)/303(改签)
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #execute()}：执行命令（调用 Receiver=对应服务），成功后由调用方记入
 *       {@link com.cinema.service.aftermarket.CommandHistory}；</li>
 *   <li>{@link #undo()}：补偿性撤销——尽力而为的业务逆操作，前置状态已被并发改动时
 *       抛 BizException 拒绝（undo 为模式能力演示路径，不经 HTTP 暴露，spec §5）。</li>
 * </ul>
 */
// @Pattern: Command(Command角色), @UseCase: UC302/UC303
public interface AftermarketCommand {

    /** 命令名（CommandHistory 展示/日志用，如 "RefundCommand"）。 */
    String name();

    /** 执行命令；返回执行结果（RefundOutcome / ChangeOutcome）。 */
    Object execute();

    /**
     * 补偿性撤销本命令（spec §5"均实现 undo"）。
     *
     * @throws com.cinema.common.BizException 前置状态不符（如座位已被重新售出）时拒绝撤销
     */
    void undo();
}
