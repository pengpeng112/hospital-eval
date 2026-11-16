package com.hospital.eval.report.service;

import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台（TASK-11 步骤 7）：overview / todos / dept-progress / alerts。
 */
@Service
@RequiredArgsConstructor
public class WorkbenchService {

    private final MetricDeptScopeMapper scopeMapper;
    private final JdbcTemplate jdbcTemplate;

    /** overview：指标总数、适用/不适用、extract_mode 分布、审核状态计数。 */
    public Map<String, Object> overview(int year) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("metricTotal", queryNum("SELECT count(*) FROM eval_metric_dict"));
        resp.put("applicable", queryNum("SELECT count(*) FROM eval_metric_dict WHERE is_applicable = true"));
        resp.put("inapplicable", queryNum("SELECT count(*) FROM eval_metric_dict WHERE is_applicable = false"));
        Map<String, Object> byMode = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT extract_mode, count(*) n FROM eval_metric_dict GROUP BY extract_mode",
                rs -> {
                    byMode.put(rs.getString("extract_mode"), rs.getInt("n"));
                });
        resp.put("byExtractMode", byMode);

        Map<String, Object> audit = new LinkedHashMap<>();
        for (String st : List.of("DRAFT", "L1_SUBMIT", "L2_PASS", "L2_REJECT", "L3_ARCHIVED")) {
            audit.put(st, 0);
        }
        Integer totalScopes = null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(audit_status,'DRAFT') st, count(*) n FROM ("
                        + " SELECT r.audit_status, s.id FROM eval_metric_dept_scope s"
                        + " LEFT JOIN eval_metric_result r ON r.metric_id = s.metric_id AND r.scope_id = s.id"
                        + " AND r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true"
                        + " WHERE COALESCE(s.skip_flag,false) = false) t GROUP BY COALESCE(audit_status,'DRAFT')",
                String.valueOf(year));
        int withRow = 0;
        for (Map<String, Object> r : rows) {
            audit.put((String) r.get("st"), ((Number) r.get("n")).intValue());
            withRow += ((Number) r.get("n")).intValue();
        }
        int scopeTotal = queryNum(
                "SELECT count(*) FROM eval_metric_dept_scope WHERE COALESCE(skip_flag,false) = false");
        audit.put("DRAFT", ((Number) audit.get("DRAFT")).intValue() + (scopeTotal - withRow));
        resp.put("audit", audit);
        return resp;
    }

    private int queryNum(String sql) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    /** 当前用户可见实例的任务状态计数（TaskService 列表口径的轻量版）。 */
    public List<Map<String, Object>> todos(int year, long userId, boolean admin, boolean funcLeader) {
        List<Map<String, Object>> out = new ArrayList<>();
        // stat_period 为 varchar，绑定参数必须用字符串年份
        String period = String.valueOf(year);
        // TO_FILL / TO_RESUBMIT 以联络员口径统计
        String sqlFill = "SELECT count(*) FROM eval_metric_dept_scope s "
                + "LEFT JOIN eval_metric_result r ON r.metric_id = s.metric_id AND r.scope_id = s.id "
                + "AND r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true "
                + "WHERE COALESCE(s.skip_flag,false) = false"
                + (admin || funcLeader ? "" : " AND s.liaison_user_id = " + userId)
                + " AND (r.id IS NULL OR r.audit_status = 'DRAFT')";
        String sqlArchive = "SELECT count(DISTINCT r.metric_id) FROM eval_metric_result r "
                + "JOIN eval_metric_dept_scope s ON s.metric_id = r.metric_id AND s.id = r.scope_id "
                + "WHERE r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true "
                + "AND r.audit_status = 'L2_PASS' "
                + "AND NOT EXISTS (SELECT 1 FROM eval_metric_dept_scope s2 "
                + "  WHERE s2.metric_id = r.metric_id AND COALESCE(s2.skip_flag,false) = false "
                + "  AND COALESCE(s2.id = r.scope_id, false) = false "
                + "  AND NOT EXISTS (SELECT 1 FROM eval_metric_result r2 WHERE r2.metric_id = r.metric_id "
                + "      AND r2.scope_id = s2.id AND r2.stat_period = ? AND r2.is_current = true "
                + "      AND r2.audit_status = 'L2_PASS'))";

        Integer toFill = jdbcTemplate.queryForObject(sqlFill, Integer.class, period);
        Integer toResub = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dept_scope s "
                        + "LEFT JOIN eval_metric_result r ON r.metric_id = s.metric_id AND r.scope_id = s.id "
                        + "AND r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true "
                        + "WHERE COALESCE(s.skip_flag,false) = false"
                        + (admin || funcLeader ? "" : " AND s.liaison_user_id = " + userId)
                        + " AND r.audit_status = 'L2_REJECT'", Integer.class, period);
        Integer toAudit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dept_scope s "
                        + "JOIN eval_metric_result r ON r.metric_id = s.metric_id AND r.scope_id = s.id "
                        + "AND r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true "
                        + "WHERE COALESCE(s.skip_flag,false) = false"
                        + (admin || funcLeader ? "" : " AND s.auditor_user_id = " + userId)
                        + " AND r.audit_status = 'L1_SUBMIT'", Integer.class, period);
        Integer toArchive = jdbcTemplate.queryForObject(sqlArchive, Integer.class, period, period);
        out.add(todo("TO_FILL", toFill));
        out.add(todo("TO_RESUBMIT", toResub));
        out.add(todo("TO_AUDIT", toAudit));
        out.add(todo("TO_ARCHIVE", toArchive));
        return out;
    }

    private Map<String, Object> todo(String type, Integer count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("count", count == null ? 0 : count);
        return m;
    }

    /** dept-progress：按责任科室分组的阶段计数。 */
    public List<Map<String, Object>> deptProgress(int year) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT COALESCE(dept.dept_name, s.resp_dept_name) dept_name, "
                + "count(*) total, "
                + "sum(CASE WHEN r.audit_status IN ('L1_SUBMIT','L2_PASS','L2_REJECT','L3_ARCHIVED') "
                + "  THEN 1 ELSE 0 END) submitted, "
                + "sum(CASE WHEN r.audit_status IN ('L2_PASS','L3_ARCHIVED') THEN 1 ELSE 0 END) passed, "
                + "sum(CASE WHEN r.audit_status = 'L3_ARCHIVED' THEN 1 ELSE 0 END) archived "
                + "FROM eval_metric_dept_scope s "
                + "LEFT JOIN eval_metric_result r ON r.metric_id = s.metric_id AND r.scope_id = s.id "
                + " AND r.stat_period_type = 'YEAR' AND r.stat_period = ? AND r.is_current = true "
                + "LEFT JOIN sys_dept dept ON dept.id = s.resp_dept_id "
                + "WHERE COALESCE(s.skip_flag,false) = false "
                + "GROUP BY COALESCE(dept.dept_name, s.resp_dept_name) ORDER BY 1";
        jdbcTemplate.query(sql, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deptName", rs.getString("dept_name"));
            int total = rs.getInt("total");
            int archived = rs.getInt("archived");
            m.put("total", total);
            m.put("submitted", rs.getInt("submitted"));
            m.put("passed", rs.getInt("passed"));
            m.put("archived", archived);
            m.put("percent", total == 0 ? 0.0
                    : Math.round(archived * 1000.0 / total) / 10.0);
            out.add(m);
        }, String.valueOf(year));
        return out;
    }

    /** alerts：全院行 0 分母 / 0 分子；近 7 天 FAILED 子任务（≤200 条）。 */
    public List<Map<String, Object>> alerts(int year) {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT d.metric_code, d.metric_name, r.numerator_val, r.denominator_val "
                        + "FROM eval_metric_result r JOIN eval_metric_dict d ON d.id = r.metric_id "
                        + "WHERE r.scope_id = 0 AND r.stat_period = ? AND r.is_current = true",
                rs -> {
                    java.math.BigDecimal num = rs.getBigDecimal("numerator_val");
                    java.math.BigDecimal den = rs.getBigDecimal("denominator_val");
                    if (den != null && den.compareTo(java.math.BigDecimal.ZERO) == 0) {
                        out.add(alert("ZERO_DEN", rs.getString("metric_code"),
                                rs.getString("metric_name"), "分母为 0"));
                    } else if (num != null && num.compareTo(java.math.BigDecimal.ZERO) == 0
                            && (den == null || den.compareTo(java.math.BigDecimal.ZERO) > 0)) {
                        out.add(alert("ZERO_NUM", rs.getString("metric_code"),
                                rs.getString("metric_name"), "分子为 0"));
                    }
                }, String.valueOf(year));
        Integer taskFailed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_exec_task WHERE status = 'FAILED' "
                        + "AND start_time >= CURRENT_TIMESTAMP - INTERVAL '7 days'", Integer.class);
        if (taskFailed != null && taskFailed > 0) {
            out.add(alert("TASK_FAILED", null, null, "最近 7 天有 " + taskFailed + " 个失败子任务"));
        }
        while (out.size() > 200) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private Map<String, Object> alert(String type, String code, String name, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("metricCode", code);
        m.put("metricName", name);
        m.put("message", message);
        return m;
    }
}
