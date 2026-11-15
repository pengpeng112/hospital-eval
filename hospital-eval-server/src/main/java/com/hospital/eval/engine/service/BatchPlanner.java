package com.hospital.eval.engine.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跑批规划（TASK-07 步骤 1）：
 * 确定科室实例集合 → skip_flag 实例写 SKIPPED(SKIP_FLAG) → 按角色分组取 is_active SQL →
 * 按 04 §8.1 年份选优为每个请求年份分派唯一胜出 SQL → 无任何 SQL 的实例写 SKIPPED(NO_SQL)。
 */
@Service
@RequiredArgsConstructor
public class BatchPlanner {

    private final MetricDeptScopeMapper scopeMapper;
    private final MetricSqlMapper sqlMapper;

    /**
     * 规划结果条目：tasks 为 PENDING 子任务（携带内存态 assignedYears 与 mixedPoolDen）；
     * skippedTasks 为 SKIPPED 子任务（sqlId/sqlRole/datasourceKey 为 null，reason=SKIP_FLAG/NO_SQL）。
     */
    public record PlannedTask(Long metricId, Long scopeId, Long sqlId, String sqlRole,
                              String datasourceKey, Set<Integer> assignedYears,
                              boolean mixedPoolDen, String reason) {
    }

    public record Plan(List<PlannedTask> tasks, List<PlannedTask> skippedTasks) {
    }

    public Plan plan(List<Long> metricIds, List<Long> scopeIds, List<Integer> years) {
        if ((metricIds == null || metricIds.isEmpty()) && (scopeIds == null || scopeIds.isEmpty())) {
            throw new BizException(400, "必须指定指标或科室实例");
        }
        List<MetricDeptScope> scopes;
        if (scopeIds != null && !scopeIds.isEmpty()) {
            scopes = scopeMapper.selectBatchIds(scopeIds);
        } else {
            scopes = scopeMapper.selectList(
                    new QueryWrapper<MetricDeptScope>().in("metric_id", metricIds).orderByAsc("id"));
        }

        List<PlannedTask> tasks = new ArrayList<>();
        List<PlannedTask> skipped = new ArrayList<>();
        for (MetricDeptScope scope : scopes) {
            if (Boolean.TRUE.equals(scope.getSkipFlag())) {
                skipped.add(new PlannedTask(scope.getMetricId(), scope.getId(), null, null,
                        null, Set.of(), false, "SKIP_FLAG"));
                continue;
            }
            List<MetricSql> sqls = sqlMapper.selectList(new QueryWrapper<MetricSql>()
                    .eq("metric_id", scope.getMetricId())
                    .eq("scope_id", scope.getId())
                    .eq("is_active", true)
                    .orderByAsc("id"));
            if (sqls.isEmpty()) {
                skipped.add(new PlannedTask(scope.getMetricId(), scope.getId(), null, null,
                        null, Set.of(), false, "NO_SQL"));
                continue;
            }
            // 按 sql_role 分组 → 每组年份选优
            Map<String, List<MetricSql>> byRole = new LinkedHashMap<>();
            for (MetricSql s : sqls) {
                byRole.computeIfAbsent(s.getSqlRole(), k -> new ArrayList<>()).add(s);
            }
            boolean hasNum = byRole.keySet().stream().anyMatch(k -> k.startsWith("NUM"));
            boolean hasDen = byRole.keySet().stream().anyMatch(k -> k.startsWith("DEN"));
            String extractMode = scope.getExtractModeOverride();
            boolean mixedPool = "HYBRID".equals(extractMode) && hasDen && !hasNum;
            for (Map.Entry<String, List<MetricSql>> e : byRole.entrySet()) {
                Map<Long, Set<Integer>> assigned = assignYears(
                        e.getValue().stream()
                                .map(s -> new SqlCandidate(s.getId(), s.getPeriodStart(), s.getPeriodEnd(),
                                        Boolean.TRUE.equals(s.getIsActive())))
                                .toList(),
                        years);
                boolean isDenRole = e.getKey().startsWith("DEN");
                for (Map.Entry<Long, Set<Integer>> a : assigned.entrySet()) {
                    MetricSql s = sqls.stream().filter(x -> x.getId().equals(a.getKey())).findFirst().orElseThrow();
                    tasks.add(new PlannedTask(scope.getMetricId(), scope.getId(), s.getId(),
                            s.getSqlRole(), s.getDatasourceKey(), a.getValue(),
                            mixedPool && isDenRole, null));
                }
            }
        }
        return new Plan(tasks, skipped);
    }

    /**
     * 04 §8.1 年份选优（纯函数）：对每个请求年份 y，在覆盖 y 的有效 SQL 中选
     * 「periodEndYear - periodStartYear 跨度最小者，相同则 id 更大者」为唯一胜出；
     * 返回 sqlId → 分到的年份集合（只为分到年份的 SQL 生成条目）。
     */
    public static Map<Long, Set<Integer>> assignYears(List<SqlCandidate> candidates, List<Integer> years) {
        Map<Long, Set<Integer>> assigned = new LinkedHashMap<>();
        for (Integer y : years) {
            Long winner = null;
            int winnerSpan = Integer.MAX_VALUE;
            for (SqlCandidate c : candidates) {
                if (!c.active()) {
                    continue;
                }
                int ps = Integer.parseInt(c.periodStart().substring(0, 4));
                int pe = Integer.parseInt(c.periodEnd().substring(0, 4));
                if (y < ps || y > pe) {
                    continue;
                }
                int span = pe - ps;
                if (winner == null || span < winnerSpan || (span == winnerSpan && c.id() > winner)) {
                    winner = c.id();
                    winnerSpan = span;
                }
            }
            if (winner != null) {
                assigned.computeIfAbsent(winner, k -> new TreeSet<>()).add(y);
            }
        }
        return assigned;
    }

    /** 选优入参（id / periodStart / periodEnd / isActive）。 */
    public record SqlCandidate(long id, String periodStart, String periodEnd, boolean active) {
    }
}
