package com.hospital.eval.engine;

import com.hospital.eval.engine.service.BatchPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 年份选优纯函数用例（TASK-07 步骤 1 / 04 §8.1）。
 */
class BatchPlannerTest {

    private BatchPlanner.SqlCandidate cand(long id, String ps, String pe) {
        return new BatchPlanner.SqlCandidate(id, ps, pe, true);
    }

    // 跨度小者优先：id=1 覆盖 2022（202201~202512）、id=2 只覆盖 2025（202501~202512）
    @Test
    void assignBySpan() {
        Map<Long, Set<Integer>> a = BatchPlanner.assignYears(
                List.of(cand(1, "202201", "202512"), cand(2, "202501", "202512")),
                List.of(2022, 2025));
        assertEquals(Set.of(2022), a.get(1L));
        assertEquals(Set.of(2025), a.get(2L));
    }

    // 请求只含 2025 → 只为 id=2 生成子任务
    @Test
    void onlyWinnerGetsTask() {
        Map<Long, Set<Integer>> a = BatchPlanner.assignYears(
                List.of(cand(1, "202201", "202512"), cand(2, "202501", "202512")),
                List.of(2025));
        assertFalse(a.containsKey(1L));
        assertEquals(Set.of(2025), a.get(2L));
    }

    // 同跨度相同覆盖 → id 更大者优先
    @Test
    void tieBreakByLargerId() {
        Map<Long, Set<Integer>> a = BatchPlanner.assignYears(
                List.of(cand(1, "202201", "202512"), cand(2, "202201", "202512")),
                List.of(2022, 2025));
        assertFalse(a.containsKey(1L));
        assertEquals(Set.of(2022, 2025), a.get(2L));
    }

    // 不覆盖请求年份的 SQL 不参与
    @Test
    void nonCoveringCandidateIgnored() {
        Map<Long, Set<Integer>> a = BatchPlanner.assignYears(
                List.of(cand(3, "202001", "202012")),
                List.of(2025));
        assertFalse(a.containsKey(3L));
    }
}
