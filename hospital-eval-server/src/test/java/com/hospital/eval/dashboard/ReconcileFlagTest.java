package com.hospital.eval.dashboard;

import com.hospital.eval.dashboard.service.ReconcileFlag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 省平台对账 flag 判定单元测试（TASK-17 步骤 2）：
 * 覆盖 5 种 flag 与"省值为 0 时比较分子分母"分支。
 */
class ReconcileFlagTest {

    private static final BigDecimal BD = BigDecimal.TEN;

    /** 院内行齐全、省值与院内一致 → OK。 */
    @Test
    void okWhenProvinceMatchesHospital() {
        assertEquals(ReconcileFlag.OK, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"),
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
    }

    /** 省结果值与院内偏差 > 5% → DIFF_GT_5；恰好 5% 不算偏差。 */
    @Test
    void diffGreaterThan5() {
        assertEquals(ReconcileFlag.DIFF_GT_5, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.2"),
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
        // 偏差恰为 5%：0.105 vs 0.1 → 5% 不大于 5% → OK
        assertEquals(ReconcileFlag.OK, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.105"),
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
    }

    /** 省分母为 0 → PROV_DEN_ZERO（优先于分子为 0 与偏差判定）。 */
    @Test
    void provDenZero() {
        assertEquals(ReconcileFlag.PROV_DEN_ZERO, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("0.1"),
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
    }

    /** 省分子为 0 且院内分子 > 0 → PROV_NUM_ZERO；院内分子也为 0 时不算。 */
    @Test
    void provNumZero() {
        assertEquals(ReconcileFlag.PROV_NUM_ZERO, ReconcileFlag.flagOf(true,
                BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0"),
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
        // 院内分子也为 0 → 不命中 PROV_NUM_ZERO，走偏差比较（省值 0 → 比分子分母）
        assertEquals(ReconcileFlag.OK, ReconcileFlag.flagOf(true,
                BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ZERO));
    }

    /** 指标编号找不到院内当期全院行 → NOT_FOUND。 */
    @Test
    void notFoundWhenHospitalRowMissing() {
        assertEquals(ReconcileFlag.NOT_FOUND, ReconcileFlag.flagOf(false,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"),
                null, null, null));
        assertEquals(ReconcileFlag.NOT_FOUND, ReconcileFlag.flagOf(false,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null));
    }

    /**
     * 省值为 0 时比较分子分母是否完全相等（04 §12.8 分支）：
     * 省结果值 = 0，省 10/100 vs 院内 11/100（分子不等）→ DIFF_GT_5；
     * 省结果值 = 0，省 10/100 vs 院内 10/100（完全相等）→ OK。
     */
    @Test
    void provValZeroComparesNumDen() {
        assertEquals(ReconcileFlag.DIFF_GT_5, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("11"), new BigDecimal("100"), new BigDecimal("0.11")));
        assertEquals(ReconcileFlag.OK, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1")));
    }

    /** 省结果值未填（null）同样退化为分子分母相等性比较。 */
    @Test
    void provValNullComparesNumDen() {
        assertEquals(ReconcileFlag.DIFF_GT_5, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), null,
                new BigDecimal("12"), new BigDecimal("100"), new BigDecimal("0.12")));
        assertEquals(ReconcileFlag.OK, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), null,
                new BigDecimal("10"), new BigDecimal("100"), BD));
    }

    /** 院内行存在但数值未填：省值非 0 时无法按值比较 → 退化为分子分母比较（省 10/100 vs null）→ DIFF_GT_5。 */
    @Test
    void hospValuesNullWhenFound() {
        assertEquals(ReconcileFlag.DIFF_GT_5, ReconcileFlag.flagOf(true,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.1"),
                null, null, null));
    }

    /** 中文说明映射完整。 */
    @Test
    void labelMapping() {
        assertEquals("一致", ReconcileFlag.labelOf(ReconcileFlag.OK));
        assertEquals("省分母为0", ReconcileFlag.labelOf(ReconcileFlag.PROV_DEN_ZERO));
        assertEquals("省分子为0", ReconcileFlag.labelOf(ReconcileFlag.PROV_NUM_ZERO));
        assertEquals("院省偏差>5%", ReconcileFlag.labelOf(ReconcileFlag.DIFF_GT_5));
        assertEquals("院内无此指标", ReconcileFlag.labelOf(ReconcileFlag.NOT_FOUND));
    }
}
