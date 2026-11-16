package com.hospital.eval.engine;

import com.hospital.eval.engine.service.ResultAggregator.HospitalPart;
import com.hospital.eval.engine.service.ResultAggregator.NumDen;
import com.hospital.eval.engine.service.ResultAggregator.RoleOutcome;
import com.hospital.eval.engine.service.ResultAggregator.ScopeExtract;
import com.hospital.eval.engine.service.ResultAggregator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汇总计算纯函数用例（TASK-07 步骤 7，04 §9；不连数据库）。
 */
class ResultAggregatorTest {

    private static final ScopeExtract HYBRID_DEN = new ScopeExtract("HYBRID", false, true);

    // 1. 两科室 DTL，患者 A 同时出现在两科 → 全院合并去重（不是简单相加）
    @Test
    void hospitalDedupMergesAcrossScopes() {
        Set<String> keysA = new LinkedHashSet<>(List.of("P001-1", "P002-1", "P003-1"));
        Set<String> keysB = new LinkedHashSet<>(List.of("P001-2", "P003-1", "P004-1"));
        List<HospitalPart> parts = List.of(
                part(1, keysA, null),
                part(2, keysB, null));
        assertTrue(ResultAggregator.shouldMergeDedup("DEDUP_DTL", parts));
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(keysA);
        merged.addAll(keysB);
        NumDen h = ResultAggregator.computeHospital("DEDUP_DTL", parts, merged, merged);
        assertEquals(5.0, h.num()); // 3+3 中的交集 P003-1 合并后 5，不是 6
        assertEquals(5.0, h.den());
    }

    // 2. 一科 SUM、一科 DTL → 全院为相加（不合并去重）
    @Test
    void hospitalSumPlusDtlAdds() {
        Set<String> keys = new LinkedHashSet<>(List.of("P001-1", "P002-1"));
        HospitalPart sumPart = new HospitalPart(1, -1, false,
                new ScopeExtract("SYS", true, false), false, false,
                "SUM", 10.0, null, "SUM", 50.0, null, null, false);
        HospitalPart dtlPart = new HospitalPart(2, -1, false,
                new ScopeExtract("SYS", true, true), false, false,
                "DTL", null, keys, "DTL", null, keys, null, false);
        List<HospitalPart> parts = List.of(sumPart, dtlPart);
        assertFalse(ResultAggregator.shouldMergeDedup("DEDUP_DTL", parts)); // SUM 参与即不合并不去重
        NumDen h = ResultAggregator.computeHospital("DEDUP_DTL", parts, null, null);
        assertEquals(12.0, h.num()); // 10 + 2
        assertEquals(52.0, h.den()); // 50 + 2
    }

    // 3. 混合池：DEN 5 人，勾选 2 人 → 分子 2
    @Test
    void scopeMixedPoolUsesSelectedCount() {
        NumDen nd = ResultAggregator.computeScope(HYBRID_DEN,
                RoleOutcome.none(), RoleOutcome.dtl(5L), 2L);
        assertEquals(2.0, nd.num());
        assertEquals(5.0, nd.den());
        assertEquals("HYBRID", nd.tag());
    }

    // 4. unit='百分比（%）'，num=33、den=129 → result_val=25.5814
    @Test
    void resultValPercentScale4() {
        Double v = ResultAggregator.computeResultVal(33.0, 129.0, "百分比（%）");
        assertEquals(25.5814, v);
    }

    // 5. den=0 → result_val=null
    @Test
    void resultValZeroDenNull() {
        assertNull(ResultAggregator.computeResultVal(33.0, 0.0, "百分比（%）"));
        assertNull(ResultAggregator.computeResultVal(null, 10.0, "个"));
    }

    // 6. skip_flag=true 的科室不参与全院计算
    @Test
    void hospitalSkipsSkipFlagScopes() {
        HospitalPart normal = new HospitalPart(1, -1, false,
                new ScopeExtract("SYS", true, false), false, false,
                "SUM", 10.0, null, "SUM", 20.0, null, null, false);
        HospitalPart skipped = new HospitalPart(2, -1, true,
                new ScopeExtract("SYS", true, false), false, false,
                "SUM", 999.0, null, "SUM", 999.0, null, null, false);
        NumDen h = ResultAggregator.computeHospital("DEDUP_DTL", List.of(normal, skipped), null, null);
        assertEquals(10.0, h.num());
        assertEquals(20.0, h.den());
    }

    // 7. 纯手工指标 / 含手工行时即使 aggMode=DEDUP_DTL 也必须走各科累加（不得误算为 0/0）
    @Test
    void hospitalManualRowsSumInsteadOfDedupZero() {
        HospitalPart deptA = new HospitalPart(1, 10L, false,
                new ScopeExtract("MANUAL", false, false), false, false,
                null, 3.0, null, null, 10.0, null, null, true);
        HospitalPart deptB = new HospitalPart(2, 10L, false,
                new ScopeExtract("MANUAL", false, false), false, false,
                null, 5.0, null, null, 20.0, null, null, true);
        List<HospitalPart> parts = List.of(deptA, deptB);
        assertFalse(ResultAggregator.shouldMergeDedup("DEDUP_DTL", parts));
        NumDen h = ResultAggregator.computeHospital("DEDUP_DTL", parts, null, null);
        assertEquals(8.0, h.num());
        assertEquals(30.0, h.den());
        assertEquals("MANUAL", h.tag());
    }

    private HospitalPart part(long scopeId, Set<String> numKeys, Set<String> denKeys) {
        return new HospitalPart(scopeId, -1, false,
                new ScopeExtract("SYS", true, true), false, false,
                "DTL", null, numKeys, "DTL", null, denKeys, null, false);
    }
}
