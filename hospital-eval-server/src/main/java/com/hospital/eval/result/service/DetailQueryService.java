package com.hospital.eval.result.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 患者明细查询（TASK-10 步骤 5）。
 * 全院行（scope_id=0）= 各参与科室当期明细并集：按去重键（患者ID-住院次数，
 * 兜底 住院号-住院次数）取首行，depts 列出出现的全部科室名。
 */
@Service
@RequiredArgsConstructor
public class DetailQueryService {

    private static final int EXPORT_MAX = 100_000;

    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 明细查询条件。 */
    public record DetailQuery(long metricId, long scopeId, int year, String role,
                              Long batchId, String keyword) {
    }

    /** 查询结果：JSON 形如 {columns, total, list}（04 §12.6）。 */
    public record DetailRows(long total, List<Map<String, Object>> list,
                             List<Map<String, Object>> columns) {
    }

    private MetricDict requireDict(long metricId) {
        MetricDict dict = dictMapper.selectById(metricId);
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }
        return dict;
    }

    private Long currentBatchId(long metricId, long scopeId, int year, Long batchId) {
        if (batchId != null) {
            return batchId;
        }
        List<Long> rids = jdbcTemplate.queryForList(
                "SELECT id FROM eval_metric_result WHERE metric_id = ? AND scope_id = ? "
                        + "AND stat_period_type = 'YEAR' AND stat_period = ? AND is_current = true",
                Long.class, metricId, scopeId, String.valueOf(year));
        if (rids.isEmpty()) {
            return null;
        }
        List<Long> bids = jdbcTemplate.queryForList(
                "SELECT batch_id FROM eval_metric_result WHERE id = ?", Long.class, rids.get(0));
        return bids.isEmpty() ? null : bids.get(0);
    }

    /** 04 §9 混合池：有效提取方式 HYBRID、有 DEN SQL、无 NUM SQL。 */
    private boolean mixedPool(long metricId, long scopeId) {
        String mode = jdbcTemplate.queryForObject(
                "SELECT COALESCE(s.extract_mode_override, d.extract_mode) FROM eval_metric_dept_scope s "
                        + "JOIN eval_metric_dict d ON d.id = s.metric_id WHERE s.id = ?",
                String.class, scopeId);
        Integer numSql = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_sql WHERE metric_id = ? AND scope_id = ? "
                        + "AND is_active = true AND sql_role LIKE 'NUM%'", Integer.class, metricId, scopeId);
        Integer denSql = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_sql WHERE metric_id = ? AND scope_id = ? "
                        + "AND is_active = true AND sql_role LIKE 'DEN%'", Integer.class, metricId, scopeId);
        return "HYBRID".equals(mode)
                && denSql != null && denSql > 0
                && (numSql == null || numSql == 0);
    }

    private String dbRole(long metricId, long scopeId, String role) {
        return "NUM".equals(role) && mixedPool(metricId, scopeId) ? "DEN" : role;
    }

    private boolean selectedOnly(long metricId, long scopeId, String role) {
        return "NUM".equals(role) && mixedPool(metricId, scopeId);
    }

    private List<Map<String, Object>> fetchRows(long metricId, long scopeId, long batchId,
                                                int year, String dbRole, boolean selectedOnly,
                                                String keyword) {
        String sql = "SELECT id, patient_id, inp_no, visit_id, patient_name, admit_dept_name, "
                + "discharge_dept_name, admit_time, discharge_time, main_diag_code, main_diag_name, "
                + "main_oper_code, main_oper_name, emr_doc_title, ext_json "
                + "FROM eval_metric_detail WHERE batch_id = ? AND metric_id = ? AND scope_id = ? "
                + "AND stat_year = ? AND role_type = ?"
                + (selectedOnly ? " AND manual_selected = true" : "")
                + (keyword != null && !keyword.isBlank()
                        ? " AND (inp_no LIKE ? OR patient_id LIKE ? OR patient_name LIKE ? "
                        + "OR main_diag_code LIKE ? OR main_diag_name LIKE ?)" : "");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        args.add(metricId);
        args.add(scopeId);
        args.add(year);
        args.add(dbRole);
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
            args.add(k);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(sql, args.toArray(), rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("patientId", rs.getString("patient_id"));
            m.put("inpNo", rs.getString("inp_no"));
            m.put("visitId", rs.getString("visit_id"));
            m.put("patientName", rs.getString("patient_name"));
            m.put("admitDeptName", rs.getString("admit_dept_name"));
            m.put("dischargeDeptName", rs.getString("discharge_dept_name"));
            Object admit = rs.getTimestamp("admit_time");
            m.put("admitTime", admit == null ? null
                    : rs.getTimestamp("admit_time").toLocalDateTime().toString());
            Object dis = rs.getTimestamp("discharge_time");
            m.put("dischargeTime", dis == null ? null
                    : rs.getTimestamp("discharge_time").toLocalDateTime().toString());
            m.put("mainDiagCode", rs.getString("main_diag_code"));
            m.put("mainDiagName", rs.getString("main_diag_name"));
            m.put("mainOperCode", rs.getString("main_oper_code"));
            m.put("mainOperName", rs.getString("main_oper_name"));
            m.put("emrDocTitle", rs.getString("emr_doc_title"));
            Map<String, Object> ext = new LinkedHashMap<>();
            String extText = rs.getString("ext_json");
            if (extText != null && !extText.isBlank()) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extText);
                    node.fields().forEachRemaining(e -> ext.put(e.getKey(), e.getValue().asText()));
                } catch (Exception e2) {
                    ext.put("ext", extText);
                }
            }
            m.put("__ext", ext);
            rows.add(m);
        });
        return rows;
    }

    /** 全量行（含全院合并与混合池处理；不分页）。 */
    public DetailRows queryAll(DetailQuery q) {
        requireDict(q.metricId());
        List<Map<String, Object>> items = new ArrayList<>();
        if (q.scopeId() == 0) {
            // 全院：各参与科室当期明细并集，去重键取首行，depts 汇总科室
            List<MetricDeptScope> scopes = scopeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricDeptScope>()
                            .eq("metric_id", q.metricId()).orderByAsc("id"));
            Map<String, Map<String, Object>> firstByKey = new LinkedHashMap<>();
            Map<String, Set<String>> deptsByKey = new LinkedHashMap<>();
            List<String> keyOrder = new ArrayList<>();
            for (MetricDeptScope s : scopes) {
                if (Boolean.TRUE.equals(s.getSkipFlag())) {
                    continue;
                }
                Long batchId = currentBatchId(q.metricId(), s.getId(), q.year(), q.batchId());
                if (batchId == null) {
                    continue;
                }
                for (Map<String, Object> row : fetchRows(q.metricId(), s.getId(), batchId, q.year(),
                        dbRole(q.metricId(), s.getId(), q.role()),
                        selectedOnly(q.metricId(), s.getId(), q.role()), q.keyword())) {
                    String key = dedupKey(row);
                    if (!firstByKey.containsKey(key)) {
                        firstByKey.put(key, row);
                        keyOrder.add(key);
                    }
                    deptsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>())
                            .add(s.getRespDeptName());
                }
            }
            for (String key : keyOrder) {
                Map<String, Object> row = firstByKey.get(key);
                row.put("depts", String.join("、", deptsByKey.get(key)));
                items.add(row);
            }
        } else {
            Long batchId = currentBatchId(q.metricId(), q.scopeId(), q.year(), q.batchId());
            if (batchId != null) {
                items.addAll(fetchRows(q.metricId(), q.scopeId(), batchId, q.year(),
                        dbRole(q.metricId(), q.scopeId(), q.role()),
                        selectedOnly(q.metricId(), q.scopeId(), q.role()), q.keyword()));
            }
        }
        for (Map<String, Object> item : items) {
            Object ext = item.remove("__ext");
            if (ext != null) {
                item.put("ext", ext);
            }
        }
        List<Map<String, Object>> columns = columnsOf(items);
        return new DetailRows(items.size(), items, columns);
    }

    /** 分页查询：全量行内存分页（total 为全量行数）。 */
    public DetailRows queryPaged(DetailQuery q, int page, int size) {
        DetailRows full = queryAll(q);
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(full.list().size(), from + size);
        List<Map<String, Object>> pageRows = from >= full.list().size()
                ? List.of() : full.list().subList(from, to);
        List<Map<String, Object>> columns = columnsOf(pageRows);
        return new DetailRows(full.total(), pageRows, columns);
    }

    /** 导出用全量行（超过 100,000 行抛 400 提示缩小范围）。 */
    public DetailRows queryForExport(DetailQuery q) {
        DetailRows full = queryAll(q);
        if (full.total() > EXPORT_MAX) {
            throw new BizException(400, "结果共 " + full.total() + " 行，超过导出上限 100,000 行，请缩小范围");
        }
        return full;
    }

    private List<Map<String, Object>> columnsOf(List<Map<String, Object>> rows) {
        List<Map<String, Object>> columns = new ArrayList<>();
        String[][] fixed = {
                {"inpNo", "住院号"}, {"patientId", "患者ID"}, {"patientName", "姓名"},
                {"visitId", "住院次数"}, {"mainDiagCode", "主要诊断编码"}, {"mainDiagName", "主要诊断名称"},
                {"mainOperCode", "第一手术编码"}, {"mainOperName", "第一手术名称"},
                {"admitDeptName", "入院科室"}, {"dischargeDeptName", "出院科室"},
                {"admitTime", "入院时间"}, {"dischargeTime", "出院时间"}, {"emrDocTitle", "文书名称"}
        };
        for (String[] f : fixed) {
            columns.add(Map.of("prop", f[0], "label", f[1]));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) row.get("ext");
            if (ext != null) {
                seen.addAll(ext.keySet());
            }
        }
        for (String k : seen) {
            columns.add(Map.of("prop", "ext." + k, "label", k));
        }
        return columns;
    }

    private String dedupKey(Map<String, Object> row) {
        String patientId = str(row.get("patientId"));
        String visitId = str(row.get("visitId"));
        String inpNo = str(row.get("inpNo"));
        if (!patientId.isEmpty() && !visitId.isEmpty()) {
            return patientId + "-" + visitId;
        }
        if (!inpNo.isEmpty() && !visitId.isEmpty()) {
            return "INP:" + inpNo + "-" + visitId;
        }
        return "ROW:" + row.get("id");
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
