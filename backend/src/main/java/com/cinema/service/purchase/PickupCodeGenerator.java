package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * 取票码生成器（spec §1：8 位数字，出票时生成，全库唯一索引 uk_ticket_order_pickup_code 兜底）。
 *
 * <p>// @UseCase: UC205(出票生成取票码)/UC307(检票凭码核销)
 *
 * <p>生成方式（本卡决策）：8 位随机数字（%08d，允许前导 0，满足 {@code ^\d{8}$}），
 * "先查后插"规避唯一索引冲突 + 冲突重试（上限 {@link #MAX_TRIES} 次，超限抛系统异常——
 * 1e8 空间下连续冲突概率可忽略）；最终正确性由全库唯一索引兜底。
 *
 * <p>可测试性：提供包级构造（注入"码是否已存在"谓词），U8 单测据此模拟冲突与重试。
 */
// @UseCase: UC205/UC307
@Component
public class PickupCodeGenerator {

    /** 单次出票允许的冲突重试上限。 */
    static final int MAX_TRIES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EntityManager entityManager;
    private final Predicate<String> existsCheck;

    /** 生产构造：以 ticket_order.pickup_code 全库查询作为存在性判断（双构造须显式指定装配入口）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public PickupCodeGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.existsCheck = this::existsInDb;
    }

    /** 测试构造：注入存在性谓词（模拟冲突重试，不依赖数据库）。 */
    PickupCodeGenerator(Predicate<String> existsCheck) {
        this.entityManager = null;
        this.existsCheck = existsCheck;
    }

    /**
     * 生成一个当前不存在的 8 位数字取票码。
     *
     * @throws BizException 连续 {@value #MAX_TRIES} 次冲突（理论不可达）时抛系统异常
     */
    public String generateUnique() {
        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            String code = nextCode();
            if (!existsCheck.test(code)) {
                return code;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "取票码生成连续冲突，请重试");
    }

    /** 生成候选码：8 位数字（允许前导 0）。 */
    static String nextCode() {
        return String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    /** 全库存在性查询（唯一索引 uk_ticket_order_pickup_code 的前置规避）。 */
    private boolean existsInDb(String code) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(o) FROM TicketOrder o WHERE o.pickupCode = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult();
        return count != null && count > 0;
    }
}
