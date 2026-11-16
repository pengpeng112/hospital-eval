package com.hospital.eval.result.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hospital.eval.common.BizException;
import com.hospital.eval.engine.entity.ExecBatch;
import com.hospital.eval.engine.entity.MetricResult;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.mapper.MetricResultMapper;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结果汇总查询（TASK-10 步骤 1~4，04 §12.6）：当期/历史汇总、科室分布、
 * 核验面板、批次列表。只查全院行 scope_id=0 的当期或指定批次。
 */
@Service
@RequiredArgsConstructor
public class ResultQueryService {

    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final MetricResultMapper resultMapper;
    private final ExecBatchMapper batchMapper;
    private final JdbcTemplate jdbcTemplate;

    private static final List<String> DEFAULT_YEARS = List.of("2022", "2023", "2024", "2025");

    /** 请求年份规范化：为空取 2022~2025，去重按升序。 */
    public static List<String> normalizeYears(String yearsParam) {
        Set<String> set = new java.util.TreeSet<>();
        if (yearsParam != null && !yearsParam.isBlank()) {
            for (String y : yearsParam.split(",")) {
                String t = y.trim();
                if (t.matches("^(19|20)\\d{2}$")) {
                    set.add(t);
                }
            }
        }
        if (set.isEmpty()) {
            set.addAll(DEFAULT_YEARS);
        }
        return new ArrayList<>(set);
    }

    private void applyListFilters(QueryWrapper<MetricDict> qw, String chapterCode, String keyword,
                                  String extractMode, Boolean applicable, String specialty) {
        if (chapterCode != null && !chapterCode.isBlank()) {
            String code = chapterCode.trim();
            qw.and(w -> w.eq("chapter_code", code)
                    .or().likeRight("chapter_code", code + ".")
                    .or().likeRight("chapter_code", code + "("));
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("metric_code", k).or().like("alias_code", k).or().like("metric_name", k));
        }
        if (extractMode != null && !extractMode.isBlank()) {
            qw.eq("extract_mode", extractMode.trim());
        }
        if (applicable != null) {
            qw.eq("is_applicable", applicable);
        }
        if (specialty != null && !specialty.isBlank()) {
            qw.eq("specialty_code", specialty.trim());
        }
    }

    /** 步骤 1：汇总列表（只查全院行）。 */
    public Map<String, Object> summary(int page, int size, String yearsParam, Long batchId,
                                       String chapterCode, String specialty, String extractMode,
                                       Boolean zeroOnly, String keyword) {
        List<String> years = normalizeYears(yearsParam);
        QueryWrapper<MetricDict> qw = new QueryWrapper<>();
        applyListFilters(qw, chapterCode, keyword, extractMode, null, specialty);
        qw.orderByAsc("master_row_no");
        if (Boolean.TRUE.equals(zeroOnly)) {
            List<Object> yArgs = new ArrayList<>();
            StringBuilder in = new StringBuilder();
            for (String y : years) {
                if (in.length() > 0) {
                    in.append(",");
                }
                in.append("?");
                yArgs.add(y);
            }
            qw.and(w -> w.apply(
                    "EXISTS (SELECT 1 FROM eval_metric_result r0 WHERE r0.metric_id = eval_metric_dict.id "
                            + "AND r0.scope_id = 0 AND r0.is_current = true AND r0.stat_period IN (" + in + ") "
                            + "AND (r0.numerator_val = 0 OR r0.denominator_val = 0))", yArgs.toArray()));
        }
        Page<MetricDict> p = dictMapper.selectPage(new Page<>(page, size), qw);

        // 页内指标的结果（全院行；当期或指定批次）
        Map<Long, Map<String, Map<String, Object>>> resultByMetricYear =
                loadHospitalResults(p.getRecords().stream().map(MetricDict::getId).toList(), years, batchId);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricDict d : p.getRecords()) {
            Map<String, Object> m = baseMetricRow(d);
            Map<String, Object> yearsMap = new LinkedHashMap<>();
            Map<String, Map<String, Object>> byYear = resultByMetricYear.getOrDefault(d.getId(), Map.of());
            for (String y : years) {
                Map<String, Object> yr = byYear.get(y);
                if (yr == null) {
                    continue; // 没有结果的年份不出现
                }
                yearsMap.put(y, yr);
            }
            m.put("years", yearsMap);
            rows.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", p.getTotal());
        resp.put("list", rows);
        return resp;
    }

    private Map<Long, Map<String, Map<String, Object>>> loadHospitalResults(
            List<Long> metricIds, List<String> years, Long batchId) {
        Map<Long, Map<String, Map<String, Object>>> out = new LinkedHashMap<>();
        if (metricIds.isEmpty()) {
            return out;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, metric_id, stat_period, numerator_val, denominator_val, result_val, "
                        + "qualitative_result, data_source_tag, audit_status, batch_id "
                        + "FROM eval_metric_result WHERE scope_id = 0 AND metric_id IN (");
        for (int i = 0; i < metricIds.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(") AND stat_period IN (");
        for (int i = 0; i < years.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        List<Object> args = new ArrayList<>(metricIds);
        args.addAll(years);
        if (batchId != null) {
            sql.append(" AND batch_id = ?");
            args.add(batchId);
        } else {
            sql.append(" AND is_current = true");
        }
        jdbcTemplate.query(sql.toString(), args.toArray(), rs -> {
            long metricId = rs.getLong("metric_id");
            String period = rs.getString("stat_period");
            Map<String, Object> ym = new LinkedHashMap<>();
            ym.put("resultId", rs.getLong("id"));
            ym.put("num", rs.getBigDecimal("numerator_val"));
            ym.put("den", rs.getBigDecimal("denominator_val"));
            ym.put("val", rs.getBigDecimal("result_val"));
            ym.put("qualitative", rs.getString("qualitative_result"));
            ym.put("auditStatus", rs.getString("audit_status"));
            ym.put("dataSourceTag", rs.getString("data_source_tag"));
            out.computeIfAbsent(metricId, k -> new LinkedHashMap<>()).put(period, ym);
        });
        return out;
    }

    private Map<String, Object> baseMetricRow(MetricDict d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metricId", d.getId());
        m.put("metricCode", d.getMetricCode());
        m.put("aliasCode", d.getAliasCode());
        m.put("metricName", d.getMetricName());
        m.put("unit", d.getUnit());
        m.put("extractMode", d.getExtractMode());
        return m;
    }

    /** 步骤 2：科室分布（含免统实例；当期或指定批次）。 */
    public Map<String, Object> metricScopes(long metricId, String yearsParam, Long batchId) {
        List<String> years = normalizeYears(yearsParam);
        MetricDict dict = dictMapper.selectById(metricId);
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricDeptScope s : scopeMapper.selectList(new QueryWrapper<MetricDeptScope>()
                .eq("metric_id", metricId).orderByAsc("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scopeId", s.getId());
            m.put("deptName", s.getRespDeptName());
            m.put("skipFlag", Boolean.TRUE.equals(s.getSkipFlag()));
            Map<String, Object> yearsMap = new LinkedHashMap<>();
            if (!Boolean.TRUE.equals(s.getSkipFlag())) {
                for (String y : years) {
                    MetricResult r = currentOrBatch(metricId, s.getId(), y, batchId);
                    if (r != null) {
                        Map<String, Object> ym = new LinkedHashMap<>();
                        ym.put("resultId", r.getId());
                        ym.put("num", r.getNumeratorVal());
                        ym.put("den", r.getDenominatorVal());
                        ym.put("val", r.getResultVal());
                        ym.put("auditStatus", r.getAuditStatus());
                        ym.put("dataSourceTag", r.getDataSourceTag());
                        yearsMap.put(y, ym);
                    }
                }
            }
            m.put("years", yearsMap);
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("metricId", metricId);
        resp.put("metricCode", dict.getMetricCode());
        resp.put("metricName", dict.getMetricName());
        resp.put("scopes", out);
        return resp;
    }

    /** 步骤 3：核验面板。 */
    public Map<String, Object> panel(long metricId) {
        MetricDict dict = dictMapper.selectById(metricId);
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dict", dict);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (String y : DEFAULT_YEARS) {
            MetricResult r = currentResult(metricId, 0L, y);
            if (r == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", y);
            m.put("num", r.getNumeratorVal());
            m.put("den", r.getDenominatorVal());
            m.put("val", r.getResultVal());
            trend.add(m);
        }
        resp.put("trend", trend);

        List<Map<String, Object>> sqls = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT s.id, s.sql_role, s.period_start, s.period_end, s.sql_annotated, s.version_no, "
                        + "sc.resp_dept_name FROM eval_metric_sql s "
                        + "LEFT JOIN eval_metric_dept_scope sc ON sc.id = s.scope_id "
                        + "WHERE s.metric_id = ? AND s.is_active = true ORDER BY s.scope_id, s.sql_role",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sqlRole", rs.getString("sql_role"));
                    m.put("deptName", rs.getString("resp_dept_name"));
                    m.put("periodStart", rs.getString("period_start"));
                    m.put("periodEnd", rs.getString("period_end"));
                    m.put("sqlAnnotated", rs.getString("sql_annotated"));
                    m.put("versionNo", rs.getInt("version_no"));
                    sqls.add(m);
                }, metricId);
        resp.put("sqls", sqls);

        List<Map<String, Object>> auditLogs = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT a.audit_level, a.action, a.opinion, a.operator_name, a.operate_time "
                        + "FROM eval_audit_log a JOIN eval_metric_result r ON r.id = a.result_id "
                        + "WHERE r.metric_id = ? ORDER BY a.operate_time DESC LIMIT 100",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("auditLevel", rs.getString("audit_level"));
                    m.put("action", rs.getString("action"));
                    m.put("opinion", rs.getString("opinion"));
                    m.put("operatorName", rs.getString("operator_name"));
                    m.put("operateTime", rs.getString("operate_time"));
                    auditLogs.add(m);
                }, metricId);
        resp.put("auditLogs", auditLogs);
        return resp;
    }

    /** 步骤 4：批次列表（含基线/人工批次）。 */
    public Map<String, Object> batches(int page, int size, String triggerType) {
        QueryWrapper<ExecBatch> qw = new QueryWrapper<>();
        if (triggerType != null && !triggerType.isBlank()) {
            qw.eq("trigger_type", triggerType.trim());
        }
        qw.orderByDesc("start_time");
        Page<ExecBatch> p = batchMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> rows = p.getRecords().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", b.getId());
            m.put("batchNo", b.getBatchNo());
            m.put("triggerType", b.getTriggerType());
            m.put("versionTag", b.getVersionTag());
            m.put("execStatus", b.getExecStatus());
            m.put("isCurrentVersion", b.getIsCurrentVersion());
            m.put("operatorName", b.getOperatorName());
            m.put("startTime", b.getStartTime());
            m.put("endTime", b.getEndTime());
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return PageResultCompat.page(p.getTotal(), rows);
    }

    MetricResult currentOrBatch(long metricId, long scopeId, String year, Long batchId) {
        QueryWrapper<MetricResult> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId).eq("scope_id", scopeId)
                .eq("stat_period_type", "YEAR").eq("stat_period", year);
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        } else {
            qw.eq("is_current", true);
        }
        List<MetricResult> rows = resultMapper.selectList(qw);
        return rows.isEmpty() ? null : rows.get(0);
    }

    MetricResult currentResult(long metricId, long scopeId, String year) {
        return currentOrBatch(metricId, scopeId, year, null);
    }

    /** 兼容返回（避免为纯 Map 响应再造 DTO）。 */
    static final class PageResultCompat {
        private PageResultCompat() {
        }

        static Map<String, Object> page(long total, List<Map<String, Object>> rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("list", rows);
            return m;
        }
    }
}
