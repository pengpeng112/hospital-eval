package com.hospital.eval.dashboard.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 省平台对账 flag 判定（TASK-17 步骤 2，04 §12.8 规则的纯函数实现，供单元测试）。
 *
 * 判定顺序：NOT_FOUND → PROV_DEN_ZERO → PROV_NUM_ZERO → DIFF_GT_5 → OK。
 * 省结果值为 0（或未填）时按“分子分母是否完全相等”比较。
 */
public final class ReconcileFlag {

    public static final String OK = "OK";
    public static final String PROV_DEN_ZERO = "PROV_DEN_ZERO";
    public static final String PROV_NUM_ZERO = "PROV_NUM_ZERO";
    public static final String DIFF_GT_5 = "DIFF_GT_5";
    public static final String NOT_FOUND = "NOT_FOUND";

    private ReconcileFlag() {
    }

    /** flag 对应的导出中文说明。 */
    public static String labelOf(String flag) {
        if (flag == null) {
            return "";
        }
        return switch (flag) {
            case PROV_DEN_ZERO -> "省分母为0";
            case PROV_NUM_ZERO -> "省分子为0";
            case DIFF_GT_5 -> "院省偏差>5%";
            case NOT_FOUND -> "院内无此指标";
            case OK -> "一致";
            default -> flag;
        };
    }

    /**
     * @param found  按指标编号是否找到院内当期全院行（false → NOT_FOUND）
     * @param provNum / provDen / provVal  省平台三个值（可为 null = 未填）
     * @param hospNum / hospDen / hospVal  院内当期全院行三值（found=true 时可为 null = 该行数值未填）
     */
    public static String flagOf(boolean found, BigDecimal provNum, BigDecimal provDen, BigDecimal provVal,
                                BigDecimal hospNum, BigDecimal hospDen, BigDecimal hospVal) {
        if (!found) {
            return NOT_FOUND;
        }
        if (isZero(provDen)) {
            return PROV_DEN_ZERO;
        }
        if (isZero(provNum) && gtZero(hospNum)) {
            return PROV_NUM_ZERO;
        }
        if (diffGreaterThan5(provNum, provDen, provVal, hospNum, hospDen, hospVal)) {
            return DIFF_GT_5;
        }
        return OK;
    }

    /** 院省偏差判定：省值非 0 用结果值相对偏差；省值为 0 或未填则比较分子分母是否完全相等。 */
    static boolean diffGreaterThan5(BigDecimal provNum, BigDecimal provDen, BigDecimal provVal,
                                    BigDecimal hospNum, BigDecimal hospDen, BigDecimal hospVal) {
        if (provVal != null && provVal.compareTo(BigDecimal.ZERO) != 0) {
            if (hospVal == null) {
                // 院内无结果值无法按值比较，退化为分子分母相等性比较
                return !numDenEquals(provNum, provDen, hospNum, hospDen);
            }
            BigDecimal diff = hospVal.subtract(provVal).abs()
                    .divide(provVal.abs(), 6, RoundingMode.HALF_UP);
            return diff.compareTo(new BigDecimal("0.05")) > 0;
        }
        // 省结果值为 0 或未填：比较分子分母是否完全相等
        return !numDenEquals(provNum, provDen, hospNum, hospDen);
    }

    private static boolean numDenEquals(BigDecimal provNum, BigDecimal provDen,
                                        BigDecimal hospNum, BigDecimal hospDen) {
        return compareTo0(provNum, hospNum) && compareTo0(provDen, hospDen);
    }

    /** 相等比较：null 视为与 null 相等；数值按 BigDecimal 数值比较。 */
    private static boolean compareTo0(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private static boolean isZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) == 0;
    }

    private static boolean gtZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }
}
