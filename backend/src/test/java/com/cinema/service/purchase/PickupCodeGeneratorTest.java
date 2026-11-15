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
 * <p>断言口径（测试计划 U8：格式正确、不重复；任务卡补充：含模拟冲突重试；
 * <b>task-05 打回修复修订"不重复"口径——原 5000/2000 连发"零重复"断言踩生日悖论</b>：
 * 5000 连发在 1e8 空间出现至少一对重复的概率 ≈1-e^(-5000×4999/2e8)≈11.7%，2000 连发 ≈2.0%，
 * 与打回验收门"偶发 1 败"（expected &lt;5000&gt; but was &lt;4999&gt;，证据
 * /d/tools/flake-evidence.log）频次吻合。修订后口径：
 * <ol>
 *   <li>格式：{@code ^\d{8}$}（8 位数字，允许前导 0，确定性断言）；</li>
 *   <li>批量候选 distinct 率健康下界（≥99%，失败概率 &lt;1e-100）——拦截退化为
 *       常量/短周期的坏随机源，同时容忍正常随机源的生日碰撞；</li>
 *   <li>"生成码不重复"改为对 {@link PickupCodeGenerator#generateUnique()} 的
 *       <b>契约级确定性验证</b>：存在性谓词记录已发码并拒绝复现（模拟"已出票码占用"），
 *       generateUnique 的先查后插重试机制保证返回序列必然互不相同——这正是全库唯一性的
 *       实现机制本身（生产路径另有 uk_ticket_order_pickup_code 唯一索引兜底）；</li>
 *   <li>冲突重试：存在性谓词连续拒绝前 3 个候选 → 生成器必须重试并成功返回第 4 个候选；</li>
 *   <li>重试上限：谓词恒拒绝 → 达到 {@link PickupCodeGenerator#MAX_TRIES} 次后抛系统异常，
 *       且谓词恰好被调用 MAX_TRIES 次（不多不少）。</li>
 * </ol>
 * 真实数据库存在性检查（全库唯一索引兜底）由 I3 支付全链集成测试覆盖。
 */
@DisplayName("U8 · 取票码生成：格式 ^\\d{8}$、批量无重复、模拟冲突重试")
class PickupCodeGeneratorTest {

    @Test
    @DisplayName("U8-1 格式与随机质量：批量 5000 候选全部匹配 ^\\d{8}$，distinct 率 ≥99%（容忍生日碰撞）")
    void generatedCodesMatchEightDigitPattern() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String code = PickupCodeGenerator.nextCode();
            assertTrue(code.matches("^\\d{8}$"), "应为 8 位数字，实际：" + code);
            codes.add(code);
        }
        // 生日悖论修正（task-05 打回修复）：5000 连发在 1e8 空间的期望碰撞对数 λ≈0.125，
        // 原口径 assertEquals(5000) 有 ≈11.7% 概率出现 1 对重复（打回门偶发失败根因，
        // 实录 expected <5000> but was <4999>）。nextCode() 是"候选生成器"，唯一性契约
        // 由 generateUnique 存在性检查 + 库表唯一索引承担；此处改为统计学安全下界：
        // 正常随机源 distinct 期望 ≈4988，P(distinct<4950) < 1e-100，同时仍能拦截
        // 退化为常量/短周期的坏随机源。
        assertTrue(codes.size() >= 4950,
                "批量候选 distinct 数应 ≥4950（正常随机源期望≈4988，生日碰撞仅数对），实际：" + codes.size());
    }

    @Test
    @DisplayName("U8-2 契约级不重复：谓词记录已发码并拒绝复现 → generateUnique 返回序列必然互不相同")
    void generateWithoutConflictReturnsDistinctCodes() {
        // 谓词口径=「该码已存在」：记录所有已产出码，复现即返回 true 触发重试。
        // generateUnique 的先查后插重试保证"绝不返回已存在码"→ 2000 连发互不相同成为
        // 确定性断言（替代原"谓词恒 false + 零重复"，后者有 ≈2% 生日碰撞偶发）。
        Set<String> issued = new HashSet<>();
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> !issued.add(code));
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
    @DisplayName("U8-5 两次生成互不相同（存在性守卫确定性断言，防同码返回）")
    void twoConsecutiveGenerationsDiffer() {
        // 与 U8-2 同法：谓词记录已发码，第二次生成若复现首次码必然被拒并重试 → 确定性互异
        Set<String> issued = new HashSet<>();
        PickupCodeGenerator generator = new PickupCodeGenerator(code -> !issued.add(code));
        assertNotEquals(generator.generateUnique(), generator.generateUnique());
    }
}
