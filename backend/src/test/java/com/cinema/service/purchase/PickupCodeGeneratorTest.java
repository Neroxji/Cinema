package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U8 · 取票码生成单元测试（测试计划 §2 U8，纯逻辑不碰数据库）。
 *
 * <p>断言口径（测试计划 U8：格式正确、不重复；任务卡补充：含模拟冲突重试）：
 * <ol>
 *   <li>格式：{@code ^\d{8}$}（8 位数字，允许前导 0）；</li>
 *   <li>批量生成无重复（5000 连发）；</li>
 *   <li>冲突重试：存在性谓词连续拒绝前 3 个候选 → 生成器必须重试并成功返回第 4 个候选；</li>
 *   <li>重试上限：谓词恒拒绝 → 达到 {@link PickupCodeGenerator#MAX_TRIES} 次后抛系统异常，
 *       且谓词恰好被调用 MAX_TRIES 次（不多不少）。</li>
 * </ol>
 * 真实数据库存在性检查（全库唯一索引兜底）由 I3 支付全链集成测试覆盖。
 */
@DisplayName("U8 · 取票码生成：格式 ^\\d{8}$、批量无重复、模拟冲突重试")
class PickupCodeGeneratorTest {

    @Test
    @DisplayName("U8-1 格式：批量 5000 个候选码全部匹配 ^\\d{8}$")
    void generatedCodesMatchEightDigitPattern() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String code = PickupCodeGenerator.nextCode();
            assertTrue(code.matches("^\\d{8}$"), "应为 8 位数字，实际：" + code);
            codes.add(code);
        }
        assertEquals(5000, codes.size(), "批量生成应无重复（1e8 空间 5000 连发碰撞概率可忽略）");
    }

    @Test
    @DisplayName("U8-2 无冲突批量生成：谓词恒不占用 → 每次生成成功且互不相同")
    void generateWithoutConflictReturnsDistinctCodes() {
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> false);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            codes.add(generator.generateUnique());
        }
        assertEquals(2000, codes.size());
        codes.forEach(code -> assertTrue(code.matches("^\\d{8}$")));
    }

    @Test
    @DisplayName("U8-3 模拟冲突重试：前 3 个候选被占用 → 重试后返回可用码")
    void retriesOnConflictUntilAvailable() {
        AtomicInteger calls = new AtomicInteger();
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> {
            // 前 3 个候选视为已存在（模拟唯一索引冲突），第 4 个起可用
            return calls.incrementAndGet() <= 3;
        });
        String code = generator.generateUnique();
        assertTrue(code.matches("^\\d{8}$"));
        assertEquals(4, calls.get(), "应恰好重试到第 4 个候选（冲突重试生效）");
    }

    @Test
    @DisplayName("U8-4 重试上限：谓词恒占用 → 恰好 MAX_TRIES 次后抛系统异常")
    void exhaustsRetriesThenFails() {
        AtomicInteger calls = new AtomicInteger();
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> {
            calls.incrementAndGet();
            return true;
        });
        BizException ex = assertThrows(BizException.class, generator::generateUnique);
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
        assertEquals(PickupCodeGenerator.MAX_TRIES, calls.get(),
                "达上限即失败，不得继续占用候选（防死循环）");
    }

    @Test
    @DisplayName("U8-5 两次生成互不相同（抽样断言，防同码返回）")
    void twoConsecutiveGenerationsDiffer() {
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> false);
        assertNotEquals(generator.generateUnique(), generator.generateUnique());
    }
}
