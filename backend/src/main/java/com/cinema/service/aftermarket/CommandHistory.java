package com.cinema.service.aftermarket;

import com.cinema.service.aftermarket.command.AftermarketCommand;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 命令历史（命令模式 · 客户端侧历史栈，spec §5：退改签命令接 CommandHistory）。
 *
 * <p>// @Pattern: Command(History——已执行命令的栈,支撑 undo), @UseCase: UC302/303
 *
 * <p>实现口径（spec 沉默处最简解读，记录于字段表-C模块.md）：进程内单例栈（synchronized），
 * 只做"记入 / 撤销最近一条 / 查看"三个动作；不持久化、不按用户隔离（演示规模单栈够用）。
 * undo 不经 HTTP 暴露（20 接口清单之外不加端点），供模式能力验证与后续扩展。
 */
// @Pattern: Command(CommandHistory角色), @UseCase: UC302/UC303
@Component
public class CommandHistory {

    private final Deque<AftermarketCommand> history = new ArrayDeque<>();

    /** 已执行命令入栈（调用方保证 execute 成功后才记入）。 */
    public synchronized void record(AftermarketCommand command) {
        history.push(command);
    }

    /** 撤销最近一条命令（undo 成功后出栈；undo 抛异常则保留在栈中，可重试）。 */
    public synchronized AftermarketCommand undoLast() {
        AftermarketCommand command = history.peek();
        if (command == null) {
            return null;
        }
        command.undo();
        history.pop();
        return command;
    }

    /** 当前栈深（测试断言用）。 */
    public synchronized int size() {
        return history.size();
    }

    /** 清空历史（仅测试隔离用：单例栈跨测试方法累积，用例入口先清零）。 */
    public synchronized void clear() {
        history.clear();
    }

    /** 栈内命令名快照（栈顶在前，只读）。 */
    public synchronized List<String> commandNames() {
        List<String> names = new ArrayList<>();
        for (AftermarketCommand command : history) {
            names.add(command.name());
        }
        return Collections.unmodifiableList(names);
    }
}
