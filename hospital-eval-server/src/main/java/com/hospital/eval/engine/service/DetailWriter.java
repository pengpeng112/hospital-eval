package com.hospital.eval.engine.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 明细批量写入（TASK-07 步骤 4）：eval_metric_detail，每 1,000 行一批 batchUpdate。
 * 携带单任务缓冲状态——由 SqlExecutor 为每个子任务 new 一个实例，禁止并发共享。
 */
public class DetailWriter {

    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_SQL =
            "INSERT INTO eval_metric_detail (batch_id, metric_id, scope_id, stat_year, stat_month, role_type, "
                    + "manual_selected, patient_id, inp_no, visit_id, patient_name, admit_dept_name, "
                    + "discharge_dept_name, admit_time, discharge_time, main_diag_code, main_diag_name, "
                    + "main_oper_code, main_oper_name, emr_sys_source, emr_doc_id, emr_doc_title, ext_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))";

    private final JdbcTemplate jdbcTemplate;
    private final ResultSetParser parser;

    private long batchId;
    private long metricId;
    private long scopeId;
    private String roleType;
    private boolean manualSelected;
    private final List<Object[]> buffer = new ArrayList<>(BATCH_SIZE);

    public DetailWriter(JdbcTemplate jdbcTemplate, ResultSetParser parser) {
        this.jdbcTemplate = jdbcTemplate;
        this.parser = parser;
    }

    public void open(long batchId, long metricId, long scopeId, String sqlRole, boolean mixedPoolDen) {
        this.batchId = batchId;
        this.metricId = metricId;
        this.scopeId = scopeId;
        this.roleType = sqlRole.startsWith("NUM") ? "NUM" : "DEN";
        // 混合池（04 §9：HYBRID、有 DEN SQL、无 NUM SQL）的 DEN 明细默认不选为分子
        this.manualSelected = !(mixedPoolDen && "DEN".equals(this.roleType));
        buffer.clear();
    }

    /** ResultSetParser.DetailSink 实现。 */
    public void add(ResultSetParser.ParsedDetail d) {
        buffer.add(new Object[]{
                batchId, metricId, scopeId, d.getStatYear(), d.getStatMonth(), roleType, manualSelected,
                d.getPatientId(), d.getInpNo(), d.getVisitId(), d.getPatientName(),
                d.getAdmitDeptName(), d.getDischargeDeptName(), d.getAdmitTime(), d.getDischargeTime(),
                d.getMainDiagCode(), d.getMainDiagName(), d.getMainOperCode(), d.getMainOperName(),
                d.getEmrSysSource(), d.getEmrDocId(), d.getEmrDocTitle(), parser.extToJson(d.getExt())
        });
        if (buffer.size() >= BATCH_SIZE) {
            flush();
        }
    }

    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, buffer);
        buffer.clear();
    }

    /** 行数超限时删除本子任务已写入的明细（仅本批次本实例本角色）。 */
    public void deleteTaskDetails() {
        jdbcTemplate.update("DELETE FROM eval_metric_detail WHERE batch_id = ? AND metric_id = ? "
                + "AND scope_id = ? AND role_type = ?", batchId, metricId, scopeId, roleType);
    }
}
