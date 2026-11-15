package com.hospital.eval.engine.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.engine.entity.ExecTask;
import com.hospital.eval.engine.entity.MetricResult;
import com.hospital.eval.engine.mapper.ExecTaskMapper;
import com.hospital.eval.engine.mapper.MetricResultMapper;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 结果汇总（TASK-07 步骤 5，04 §9 / §5.4）：
 * 科室行 = computeScope（混合池分子、SUM/DTL 取值、失败置 NULL）；
 * 全院行 = computeHospital（DEDUP_DTL 全员 DTL/混合池 → 跨科室当期批次明细合并去重；
 * 否则数值相加，NULL 不参与，全 NULL 则 NULL；单参与科室 = 该科室值）；
 * 当期切换 = 任一角色失败或旧当期 L3_ARCHIVED → 新行 is_current=false（后者追加
 * ARCHIVED_LOCKED），否则旧当期置 false、新行置 true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultAggregator {

    public static final String MANUAL_BATCH_NO = "BATCH_MANUAL";

    private final JdbcTemplate jdbcTemplate;
    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final MetricSqlMapper sqlMapper;
    private final ExecTaskMapper taskMapper;
    private final MetricResultMapper resultMapper;

    /** SqlExecutor 的单子任务产出（内存传递给聚合；elapsedMs/errorMsg 供 BatchRunner 回写）。 */
    public record TaskOutcome(long taskId, long metricId, long scopeId, Long sqlId, String sqlRole,
                              String status, int rawRows, long elapsedMs, String errorMsg,
                              Map<Integer, BigDecimal> sumByYear) {
    }

    // ============================ 纯函数（单测直接调用） ============================

    /** 角色（NUM/DEN）单侧产出。kind = SUM / DTL / null（无 SQL）。failed 表示该角色子任务失败。 */
    public record RoleOutcome(boolean failed, String kind, Double sumValue, Long distinctCount) {
        public static RoleOutcome sum(Double value) {
            return new RoleOutcome(false, "SUM", value, null);
        }

        public static RoleOutcome dtl(Long distinctCount) {
            return new RoleOutcome(false, "DTL", null, distinctCount);
        }

        public static RoleOutcome failedOutcome() {
            return new RoleOutcome(true, null, null, null);
        }

        public static RoleOutcome none() {
            return new RoleOutcome(false, null, null, null);
        }
    }

    /** 实例提取方式画像。 */
    public record ScopeExtract(String extractMode, boolean hasNumSql, boolean hasDenSql) {
        public boolean mixedPool() {
            return "HYBRID".equals(extractMode) && hasDenSql() && !hasNumSql();
        }
    }

    /** 科室行计算结果。 */
    public record NumDen(Double num, Double den, String tag, boolean numFailed, boolean denFailed,
                         boolean numFromDtl, boolean denFromDtl) {
    }

    /** 全院行参与科室条目（scopeId/batchId=该科室当期行所在批次，供合并去重取数）。 */
    public record HospitalPart(long scopeId, long batchId, boolean skipFlag, ScopeExtract extract,
                               boolean numFailed, boolean denFailed,
                               String numKind, Double numValue, Set<String> numKeys,
                               String denKind, Double denValue, Set<String> denKeys,
                               Long manualSelectedCount, boolean manualRow) {
    }

    /** 04 §9 第 1~2 条：科室行分子/分母。 */
    public static NumDen computeScope(ScopeExtract ex, RoleOutcome num, RoleOutcome den, Long denSelectedCount) {
        boolean mixedPool = ex.mixedPool();
        boolean numFailed = num != null && num.failed();
        boolean denFailed = den != null && den.failed();
        Double n = null;
        Double d = null;
        if (!numFailed) {
            if (num != null && "SUM".equals(num.kind())) {
                n = num.sumValue();
            } else if (num != null && "DTL".equals(num.kind())) {
                n = num.distinctCount() == null ? null : num.distinctCount().doubleValue();
            } else if (mixedPool) {
                n = denSelectedCount == null ? null : denSelectedCount.doubleValue();
            }
        }
        if (!denFailed) {
            if (den != null && "SUM".equals(den.kind())) {
                d = den.sumValue();
            } else if (den != null && "DTL".equals(den.kind())) {
                d = den.distinctCount() == null ? null : den.distinctCount().doubleValue();
            }
        }
        String tag = mixedPool ? "HYBRID" : "SYS";
        return new NumDen(n, d, tag, numFailed, denFailed,
                num != null && "DTL".equals(num.kind()), den != null && "DTL".equals(den.kind()));
    }

    /** 04 §9 第 3 条：结果值（% 类 ×100，保留 4 位；den 为 0/NULL 或 num NULL → null）。 */
    public static Double computeResultVal(Double num, Double den, String unit) {
        if (den == null || den == 0D || num == null) {
            return null;
        }
        double v = num / den;
        if (unit != null && (unit.contains("%") || unit.contains("百分"))) {
            v = v * 100;
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /** 04 §9 第 4 条：DEDUP_DTL 且全员 DTL/混合池 → 合并去重；否则数值相加。 */
    public static boolean shouldMergeDedup(String aggMode, List<HospitalPart> parts) {
        if (!"DEDUP_DTL".equals(aggMode)) {
            return false;
        }
        for (HospitalPart p : parts) {
            if (p.skipFlag()) {
                continue;
            }
            if (p.numKind() != null && !"DTL".equals(p.numKind())) {
                return false;
            }
            if (p.denKind() != null && !"DTL".equals(p.denKind())) {
                return false;
            }
        }
        return true;
    }

    private static Double numValueOf(HospitalPart p) {
        if (p.numFailed()) {
            return null;
        }
        if ("SUM".equals(p.numKind())) {
            return p.numValue();
        }
        if ("DTL".equals(p.numKind())) {
            return p.numKeys() == null ? p.numValue() : (double) p.numKeys().size();
        }
        return p.numValue(); // 无 SQL：人工/历史当期值
    }

    private static Double denValueOf(HospitalPart p) {
        if (p.denFailed()) {
            return null;
        }
        if ("SUM".equals(p.denKind())) {
            return p.denValue();
        }
        if ("DTL".equals(p.denKind())) {
            return p.denKeys() == null ? p.denValue() : (double) p.denKeys().size();
        }
        return p.denValue();
    }

    /** 04 §9 第 4 条：全院行。mergedNumKeys/mergedDenKeys 由调用方跨科室当期批次合并去重后传入。 */
    public static NumDen computeHospital(String aggMode, List<HospitalPart> parts,
                                         Set<String> mergedNumKeys, Set<String> mergedDenKeys) {
        List<HospitalPart> active = parts.stream().filter(p -> !p.skipFlag()).toList();
        boolean numFailed = active.stream().anyMatch(HospitalPart::numFailed);
        boolean denFailed = active.stream().anyMatch(HospitalPart::denFailed);
        Double n;
        Double d;
        if (shouldMergeDedup(aggMode, parts)) {
            n = mergedNumKeys == null ? null : (double) mergedNumKeys.size();
            d = mergedDenKeys == null ? null : (double) mergedDenKeys.size();
        } else {
            Double nAcc = null;
            for (HospitalPart p : active) {
                Double v = numValueOf(p);
                if (v != null) {
                    nAcc = (nAcc == null ? 0D : nAcc) + v;
                }
            }
            Double dAcc = null;
            for (HospitalPart p : active) {
                Double v = denValueOf(p);
                if (v != null) {
                    dAcc = (dAcc == null ? 0D : dAcc) + v;
                }
            }
            n = nAcc;
            d = dAcc;
        }
        boolean anyManual = active.stream().anyMatch(HospitalPart::manualRow);
        boolean anySql = active.stream().anyMatch(p ->
                "SUM".equals(p.numKind()) || "SUM".equals(p.denKind())
                        || p.numKeys() != null || p.denKeys() != null);
        boolean anyHybrid = active.stream()
                .anyMatch(p -> p.extract() != null && p.extract().mixedPool());
        String tag;
        if (anyHybrid || (anyManual && anySql)) {
            tag = "HYBRID";
        } else if (anyManual) {
            tag = "MANUAL";
        } else {
            tag = "SYS";
        }
        return new NumDen(n, d, tag, numFailed, denFailed, false, false);
    }

    // ============================ DB 编排 ============================

    private static final String DEDUP_KEY_EXPR =
            "CASE WHEN COALESCE(patient_id,'') <> '' OR COALESCE(visit_id,'') <> '' "
                    + "THEN COALESCE(patient_id,'') || '-' || COALESCE(visit_id,'') "
                    + "WHEN COALESCE(inp_no,'') <> '' THEN COALESCE(inp_no,'') || '-' || COALESCE(visit_id,'') "
                    + "ELSE 'ROW:' || id END";

    /** 批次全部子任务结束后的聚合入口（BatchRunner 调用）。 */
    public void aggregate(long batchId, List<TaskOutcome> outcomes) {
        Map<Long, Map<Long, List<TaskOutcome>>> byMetricScope = new LinkedHashMap<>();
        Map<Long, Set<Integer>> metricYears = new LinkedHashMap<>();
        Map<Long, Integer> taskVersion = new LinkedHashMap<>();
        for (TaskOutcome o : outcomes) {
            byMetricScope.computeIfAbsent(o.metricId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(o.scopeId(), k -> new ArrayList<>()).add(o);
            if (o.sqlId() != null) {
                MetricSql s = sqlMapper.selectById(o.sqlId());
                if (s != null && s.getVersionNo() != null) {
                    taskVersion.put(o.taskId(), s.getVersionNo());
                }
            }
        }
        Map<String, Integer> versionByTaskRole = new LinkedHashMap<>();
        for (TaskOutcome o : outcomes) {
            if (o.sqlRole() != null && taskVersion.containsKey(o.taskId())) {
                versionByTaskRole.put(o.taskId() + "|" + o.sqlRole(), taskVersion.get(o.taskId()));
            }
        }

        // —— 科室行 ——
        for (Map.Entry<Long, Map<Long, List<TaskOutcome>>> e : byMetricScope.entrySet()) {
            long metricId = e.getKey();
            MetricDict dict = dictMapper.selectById(metricId);
            for (Map.Entry<Long, List<TaskOutcome>> se : e.getValue().entrySet()) {
                long scopeId = se.getKey();
                MetricDeptScope scope = scopeMapper.selectById(scopeId);
                ScopeExtract ex = extractOf(scope, dict);
                Map<String, List<TaskOutcome>> byRole = new LinkedHashMap<>();
                Set<Integer> years = new TreeSet<>();
                for (TaskOutcome o : se.getValue()) {
                    if (o.sqlRole() != null) {
                        byRole.computeIfAbsent(o.sqlRole(), k -> new ArrayList<>()).add(o);
                    }
                    if (o.sumByYear() != null) {
                        years.addAll(o.sumByYear().keySet());
                    }
                    for (Integer y : detailYears(batchId, metricId, scopeId)) {
                        years.add(y);
                    }
                }
                boolean mixedPool = ex.mixedPool();
                for (Integer year : years) {
                    RoleOutcome num = roleOutcome(byRole.get("NUM"), "NUM", year, batchId, metricId, scopeId);
                    RoleOutcome den = roleOutcome(byRole.get("DEN"), "DEN", year, batchId, metricId, scopeId);
                    Long selected = mixedPool
                            ? countDetails(batchId, metricId, scopeId, year, "DEN", true) : null;
                    NumDen nd = computeScope(ex, num, den, selected);
                    Integer vNum = versionOfTaskRole(se.getValue(), "NUM", year, versionByTaskRole);
                    Integer vDen = versionOfTaskRole(se.getValue(), "DEN", year, versionByTaskRole);
                    boolean numFailed = nd.numFailed();
                    boolean denFailed = nd.denFailed();
                    insertResult(batchId, metricId, scopeId, year, nd, vNum, vDen,
                            numFailed || denFailed);
                }
            }
        }

        // —— 全院行 ——
        Set<Long> metricIds = new LinkedHashSet<>(byMetricScope.keySet());
        for (Long metricId : metricIds) {
            MetricDict dict = dictMapper.selectById(metricId);
            List<MetricDeptScope> scopes = scopeMapper.selectList(
                    new QueryWrapper<MetricDeptScope>().eq("metric_id", metricId).orderByAsc("id"));
            Set<Integer> years = new TreeSet<>();
            for (MetricDeptScope scope : scopes) {
                years.addAll(detailYears(batchId, metricId, scope.getId()));
                for (TaskOutcome o : outcomes) {
                    if (o.metricId() == metricId && o.sumByYear() != null) {
                        years.addAll(o.sumByYear().keySet());
                    }
                }
            }
            for (Integer year : years) {
                List<HospitalPart> parts = new ArrayList<>();
                boolean anyPartFailed = false;
                for (MetricDeptScope scope : scopes) {
                    ScopeExtract ex = extractOf(scope, dict);
                    RoleShapes shapes = roleShapes(scope.getId(), metricId);
                    MetricResult current = currentResult(metricId, scope.getId(), String.valueOf(year));
                    Set<String> numKeys = null;
                    Set<String> denKeys = null;
                    boolean numFailed = false;
                    boolean denFailed = false;
                    if (current != null) {
                        if (shapes.numKind() != null && !"SUM".equals(shapes.numKind())) {
                            numKeys = fetchKeys(metricId, year, scope.getId(), current.getBatchId(),
                                    "NUM", false);
                        }
                        if (shapes.denKind() != null && !"SUM".equals(shapes.denKind())) {
                            denKeys = fetchKeys(metricId, year, scope.getId(), current.getBatchId(),
                                    "DEN", ex.mixedPool());
                        }
                    }
                    // 本批次该 (scope, year, role) 失败且无旧当期行 → 该角色失败
                    for (TaskOutcome o : outcomes) {
                        if (o.metricId() != metricId || o.scopeId() != scope.getId()) {
                            continue;
                        }
                        boolean coversYear = (o.sumByYear() != null && o.sumByYear().containsKey(year))
                                || detailHasYear(batchId, metricId, scope.getId(), year);
                        if (!coversYear) {
                            continue;
                        }
                        if ("FAILED".equals(o.status())) {
                            if (o.sqlRole().startsWith("NUM") && current == null) {
                                numFailed = true;
                            }
                            if (o.sqlRole().startsWith("DEN") && current == null) {
                                denFailed = true;
                            }
                        }
                    }
                    Long manualSelected = null;
                    if (ex.mixedPool() && current != null) {
                        manualSelected = countDetails(current.getBatchId(), metricId, scope.getId(),
                                year, "DEN", true);
                    }
                    parts.add(new HospitalPart(scope.getId(),
                            current == null ? -1L : current.getBatchId(),
                            Boolean.TRUE.equals(scope.getSkipFlag()), ex,
                            numFailed, denFailed,
                            shapes.numKind(), current == null ? null : toDouble(current.getNumeratorVal()), numKeys,
                            shapes.denKind(), current == null ? null : toDouble(current.getDenominatorVal()), denKeys,
                            manualSelected,
                            current != null && isManualBatch(current.getBatchId())));
                }
                boolean merged = shouldMergeDedup(dict.getAggMode(), parts);
                Set<String> mergedNum = null;
                Set<String> mergedDen = null;
                if (merged) {
                    mergedNum = fetchMergedKeys(metricId, year, parts, true);
                    mergedDen = fetchMergedKeys(metricId, year, parts, false);
                }
                NumDen h = computeHospital(dict.getAggMode(), parts, mergedNum, mergedDen);
                boolean failed = h.numFailed() || h.denFailed()
                        || (h.num() == null && anyPartFailedForRole(parts, "NUM"))
                        || (h.den() == null && anyPartFailedForRole(parts, "DEN"));
                insertResult(batchId, metricId, 0L, year, h, null, null, failed);
            }
        }
    }

    // ------------------------------------------------------------------

    private boolean anyPartFailedForRole(List<HospitalPart> parts, String role) {
        for (HospitalPart p : parts) {
            if (p.skipFlag()) {
                continue;
            }
            if ("NUM".equals(role) && p.numFailed()) {
                return true;
            }
            if ("DEN".equals(role) && p.denFailed()) {
                return true;
            }
        }
        return false;
    }

    private Integer versionOfTaskRole(List<TaskOutcome> outs, String prefix, int year,
                                      Map<String, Integer> versionByTaskRole) {
        for (TaskOutcome o : outs) {
            if (o.sqlRole() != null && o.sqlRole().startsWith(prefix)) {
                boolean covers = (o.sumByYear() != null && o.sumByYear().containsKey(year))
                        || versionByTaskRole.containsKey(o.taskId() + "|" + o.sqlRole());
                Integer v = versionByTaskRole.get(o.taskId() + "|" + o.sqlRole());
                if (covers && v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private record RoleShapes(String numKind, String denKind) {
    }

    /** 该实例各角色的结果形态（SUM/DTL/null），混合池分子记 DTL（来自 DEN 勾选明细）。 */
    private RoleShapes roleShapes(long scopeId, long metricId) {
        List<MetricSql> sqls = sqlMapper.selectList(new QueryWrapper<MetricSql>()
                .eq("metric_id", metricId).eq("scope_id", scopeId).eq("is_active", true));
        String numKind = null;
        String denKind = null;
        for (MetricSql s : sqls) {
            if (s.getSqlRole().startsWith("NUM")) {
                numKind = s.getSqlRole().endsWith("_SUM") ? "SUM" : "DTL";
            } else if (s.getSqlRole().startsWith("DEN")) {
                denKind = s.getSqlRole().endsWith("_SUM") ? "SUM" : "DTL";
            }
        }
        ScopeExtract ex = extractOf(scopeMapper.selectOne(new QueryWrapper<MetricDeptScope>()
                .eq("id", scopeId)), dictMapper.selectById(metricId));
        if (ex.mixedPool()) {
            numKind = "DTL"; // 混合池分子 = DEN 明细勾选，参与 DEDUP 合并
        }
        return new RoleShapes(numKind, denKind);
    }

    private ScopeExtract extractOf(MetricDeptScope scope, MetricDict dict) {
        String mode = scope != null && scope.getExtractModeOverride() != null
                ? scope.getExtractModeOverride()
                : (dict != null ? dict.getExtractMode() : null);
        boolean hasNum = false;
        boolean hasDen = false;
        if (scope != null && dict != null) {
            List<MetricSql> sqls = sqlMapper.selectList(new QueryWrapper<MetricSql>()
                    .eq("metric_id", dict.getId()).eq("scope_id", scope.getId()).eq("is_active", true));
            for (MetricSql s : sqls) {
                if (s.getSqlRole().startsWith("NUM")) {
                    hasNum = true;
                }
                if (s.getSqlRole().startsWith("DEN")) {
                    hasDen = true;
                }
            }
        }
        return new ScopeExtract(mode, hasNum, hasDen);
    }

    private RoleOutcome roleOutcome(List<TaskOutcome> outs, String prefix, int year,
                                    long batchId, long metricId, long scopeId) {
        if (outs == null || outs.isEmpty()) {
            return RoleOutcome.none();
        }
        boolean anyFailed = false;
        for (TaskOutcome o : outs) {
            if (!o.sqlRole().startsWith(prefix)) {
                continue;
            }
            boolean coversYear = o.sumByYear() != null && o.sumByYear().containsKey(year);
            if (coversYear) {
                if ("FAILED".equals(o.status())) {
                    anyFailed = true;
                    continue;
                }
                return RoleOutcome.sum(o.sumByYear().get(year).doubleValue());
            }
            // DTL：明细表按年可查
            if (detailHasYear(batchId, metricId, scopeId, year)) {
                if ("FAILED".equals(o.status())) {
                    anyFailed = true;
                    continue;
                }
                long cnt = countDetails(batchId, metricId, scopeId, year,
                        prefix, prefix.equals("DEN") && false);
                return RoleOutcome.dtl(cnt);
            }
            if ("FAILED".equals(o.status())) {
                anyFailed = true;
            }
        }
        return anyFailed ? RoleOutcome.failedOutcome() : RoleOutcome.none();
    }

    private Set<Integer> detailYears(long batchId, long metricId, long scopeId) {
        List<Integer> years = jdbcTemplate.queryForList(
                "SELECT DISTINCT stat_year FROM eval_metric_detail WHERE batch_id = ? AND metric_id = ? "
                        + "AND scope_id = ?", Integer.class, batchId, metricId, scopeId);
        return new TreeSet<>(years);
    }

    private boolean detailHasYear(long batchId, long metricId, long scopeId, int year) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_detail WHERE batch_id = ? AND metric_id = ? "
                        + "AND scope_id = ? AND stat_year = ?", Integer.class,
                batchId, metricId, scopeId, year);
        return n != null && n > 0;
    }

    private long countDetails(long batchId, long metricId, long scopeId, int year,
                              String roleType, boolean manualSelectedOnly) {
        String sql = "SELECT count(*) FROM eval_metric_detail WHERE batch_id = ? AND metric_id = ? "
                + "AND scope_id = ? AND stat_year = ? AND role_type = ?"
                + (manualSelectedOnly ? " AND manual_selected = true" : "");
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class,
                batchId, metricId, scopeId, year, roleType);
        return n == null ? 0 : n;
    }

    private Set<String> fetchKeys(long metricId, int year, long scopeId, long batchId,
                                  String roleType, boolean manualSelectedOnly) {
        String sql = "SELECT DISTINCT " + DEDUP_KEY_EXPR + " FROM eval_metric_detail "
                + "WHERE metric_id = ? AND stat_year = ? AND scope_id = ? AND batch_id = ? AND role_type = ?"
                + (manualSelectedOnly ? " AND manual_selected = true" : "");
        return new HashSet<>(jdbcTemplate.queryForList(sql, String.class,
                metricId, year, scopeId, batchId, roleType));
    }

    private Set<String> fetchMergedKeys(long metricId, int year, List<HospitalPart> parts, boolean forNum) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (HospitalPart p : parts) {
            if (p.skipFlag()) {
                continue;
            }
            boolean mixed = p.extract() != null && p.extract().mixedPool();
            String role;
            boolean selectedOnly;
            if (forNum) {
                role = mixed ? "DEN" : "NUM";
                selectedOnly = mixed;
            } else {
                role = "DEN";
                selectedOnly = false;
            }
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("(scope_id = ? AND batch_id = ? AND role_type = ?");
            args.add(p.scopeId());
            args.add(p.batchId());
            args.add(role);
            if (selectedOnly) {
                where.append(" AND manual_selected = true");
            }
            where.append(")");
        }
        if (where.length() == 0) {
            return null;
        }
        String sql = "SELECT DISTINCT " + DEDUP_KEY_EXPR + " FROM eval_metric_detail "
                + "WHERE metric_id = ? AND stat_year = ? AND (" + where + ")";
        args.add(0, metricId);
        args.add(1, year);
        return new HashSet<>(jdbcTemplate.queryForList(sql, String.class, args.toArray()));
    }

    private boolean isManualBatch(Long batchId) {
        if (batchId == null) {
            return false;
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_exec_batch WHERE id = ? AND batch_no = ?",
                Integer.class, batchId, MANUAL_BATCH_NO);
        return n != null && n > 0;
    }

    private MetricResult currentResult(long metricId, long scopeId, String period) {
        List<MetricResult> rows = resultMapper.selectList(new QueryWrapper<MetricResult>()
                .eq("metric_id", metricId)
                .eq("scope_id", scopeId)
                .eq("stat_period_type", "YEAR")
                .eq("stat_period", period)
                .eq("is_current", true)
                .orderByDesc("id")
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private void insertResult(long batchId, long metricId, long scopeId, int year, NumDen nd,
                              Integer versionNum, Integer versionDen, boolean roleFailed) {
        String period = String.valueOf(year);
        MetricResult old = currentResult(metricId, scopeId, period);
        boolean archivedLocked = old != null && "L3_ARCHIVED".equals(old.getAuditStatus());
        boolean isCurrent = !roleFailed && !archivedLocked;
        if (old != null && !archivedLocked && !roleFailed) {
            old.setIsCurrent(false);
            resultMapper.updateById(old);
        }
        if (archivedLocked) {
            // 对应子任务 error_msg 追加 ARCHIVED_LOCKED（04 §5.4）
            List<ExecTask> tasks = taskMapper.selectList(new QueryWrapper<ExecTask>()
                    .eq("batch_id", batchId).eq("metric_id", metricId).eq("scope_id", scopeId));
            for (ExecTask t : tasks) {
                String msg = t.getErrorMsg() == null ? "" : t.getErrorMsg();
                if (!msg.contains("ARCHIVED_LOCKED")) {
                    ExecTask upd = new ExecTask();
                    upd.setId(t.getId());
                    upd.setErrorMsg((msg.isEmpty() ? "" : msg + " ") + "ARCHIVED_LOCKED");
                    taskMapper.updateById(upd);
                }
            }
        }
        MetricResult row = new MetricResult();
        row.setBatchId(batchId);
        row.setMetricId(metricId);
        row.setScopeId(scopeId);
        row.setStatPeriodType("YEAR");
        row.setStatPeriod(period);
        row.setNumeratorVal(nd.num() == null ? null : BigDecimal.valueOf(nd.num()));
        row.setDenominatorVal(nd.den() == null ? null : BigDecimal.valueOf(nd.den()));
        Double resultVal = computeResultVal(nd.num(), nd.den(), unitOf(metricId));
        row.setResultVal(resultVal == null ? null : BigDecimal.valueOf(resultVal));
        row.setDataSourceTag(nd.tag());
        row.setSqlVersionNum(versionNum);
        row.setSqlVersionDen(versionDen);
        row.setAuditStatus("DRAFT");
        row.setIsCurrent(isCurrent);
        row.setUpdateTime(LocalDateTime.now());
        resultMapper.insert(row);
    }

    private String unitOf(long metricId) {
        MetricDict dict = dictMapper.selectById(metricId);
        return dict == null ? null : dict.getUnit();
    }
}
