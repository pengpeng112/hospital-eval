package com.hospital.eval.result.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.config.OracleDataSourceConfig.JdbcTemplateSpec;
import com.hospital.eval.config.OracleDataSourceConfig.OraclePools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MTL / JHEMR 病历文书调阅（TASK-10 步骤 7；SQL 照抄任务书，全部 ? 绑定，
 * 执行前经 ReadOnlySqlValidator 静态校验，queryTimeout=30）。
 * 禁止无患者条件查询；日志只记 sys/docId/调用人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmrDocService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String MTL_LIST_SQL = """
            SELECT * FROM (
              SELECT 'MTL' AS SYS,
                     dix.UHID || '-' || TO_CHAR(doc.ID) AS DOC_ID,
                     NVL(doc.DOC_TITLE, doc.DOC_TYPE_NAME) AS TITLE,
                     doc.DOC_CREATE_DATE AS DOC_TIME
              FROM MTL.EPR_INPATIENT_DOCINDEX dix
              JOIN MTL.EPR_INPATIENT doc
                ON doc.UHID = dix.UHID
               AND NVL(doc.DELETEMARK, 0) = 0
              WHERE dix.P_INPATIENT_NO = ?
                AND dix.P_INPATIENT_TIMES = ?
              ORDER BY doc.DOC_CREATE_DATE
            ) WHERE ROWNUM <= 300""";

    private static final String MTL_CONTENT_SQL = """
            SELECT NVL(doc.DOC_TITLE, doc.DOC_TYPE_NAME) AS TITLE,
                   doc.DOC_CREATE_DATE AS DOC_TIME,
                   content.段落内容 AS CONTENT
            FROM MTL.EPR_INPATIENT_DOCINDEX dix
            JOIN MTL.EPR_INPATIENT doc
              ON doc.UHID = dix.UHID
             AND NVL(doc.DELETEMARK, 0) = 0
            JOIN MTL.EMRSD_CONTENT content
              ON content.UHID = doc.UHID
             AND content.段落编号 = doc.ID
            WHERE dix.P_INPATIENT_NO = ?
              AND dix.P_INPATIENT_TIMES = ?
              AND dix.UHID = ?
              AND TO_CHAR(doc.ID) = ?
              AND ROWNUM <= 1""";

    private static final String JHEMR_LIST_SQL = """
            SELECT * FROM (
              SELECT 'JHEMR' AS SYS,
                     TO_CHAR(idx.FILE_UNIQUE_ID) AS DOC_ID,
                     idx.TOPIC AS TITLE,
                     idx.CREATE_DATE_TIME AS DOC_TIME
              FROM JHEMR.JHMR_FILE_INDEX idx
              WHERE idx.PATIENT_ID = ?
                AND idx.VISIT_ID = ?
                AND NVL(idx.DELETE_FLAG, 0) = 0
              ORDER BY idx.CREATE_DATE_TIME
            ) WHERE ROWNUM <= 300""";

    private static final String JHEMR_CONTENT_SQL = """
            SELECT idx.TOPIC AS TITLE,
                   idx.CREATE_DATE_TIME AS DOC_TIME,
                   txt.MR_CONTENT AS CONTENT
            FROM JHEMR.JHMR_FILE_INDEX idx
            LEFT JOIN JHEMR.JHMR_FILE_CONTENT_TEXT txt
              ON txt.FILE_UNIQUE_ID = idx.FILE_UNIQUE_ID
            WHERE idx.PATIENT_ID = ?
              AND idx.VISIT_ID = ?
              AND TO_CHAR(idx.FILE_UNIQUE_ID) = ?
              AND ROWNUM <= 1""";

    private final OraclePools pools;
    private final com.hospital.eval.algo.sandbox.ReadOnlySqlValidator validator;

    /** DOC_ID 按最后一个 - 拆成 UHID 与 文书ID："ABC-12-345" → {"ABC-12","345"}。 */
    public static String[] splitDocId(String docId) {
        int idx = docId == null ? -1 : docId.lastIndexOf('-');
        if (idx <= 0 || idx == docId.length() - 1) {
            throw new BizException(400, "docId 格式非法（应为 UHID-文书ID）");
        }
        return new String[]{docId.substring(0, idx), docId.substring(idx + 1)};
    }

    private JdbcTemplate oracle() {
        JdbcTemplateSpec spec = pools.templates().get("ODS_RO");
        if (spec == null) {
            throw new BizException(400, "ODS_RO 未配置");
        }
        JdbcTemplate t = new JdbcTemplate(spec.dataSource());
        t.setQueryTimeout(30);
        t.setFetchSize(500);
        return t;
    }

    private void validateStatic(String sql) {
        var vr = validator.validate(sql);
        if (!vr.isValid()) {
            throw new BizException(400, "SQL 校验未通过：" + String.join("；", vr.getErrors()));
        }
    }

    /** 病历文书列表：MTL/JHEMR 双系统合并，按 docTime 升序；单系统失败不影响另一系统。 */
    public Map<String, Object> documents(String patientId, String visitId, String inpNo) {
        JdbcTemplate oracle = oracle();
        List<Map<String, Object>> docs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (notBlank(inpNo) && notBlank(visitId)) {
            try {
                validateStatic(MTL_LIST_SQL);
                oracle.query(MTL_LIST_SQL, rs -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sys", "MTL");
                    d.put("docId", rs.getString("DOC_ID"));
                    d.put("title", rs.getString("TITLE"));
                    LocalDateTime dt = toLocal(rs.getTimestamp("DOC_TIME"));
                    d.put("docTime", dt == null ? null : TS_FMT.format(dt));
                    d.put("_sort", dt == null ? "" : TS_FMT.format(dt));
                    docs.add(d);
                });
            } catch (Exception e) {
                warnings.add("MTL 查询失败: " + oraOf(e));
            }
        }
        if (notBlank(patientId) && notBlank(visitId)) {
            try {
                validateStatic(JHEMR_LIST_SQL);
                oracle.query(JHEMR_LIST_SQL, rs -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("sys", "JHEMR");
                    d.put("docId", rs.getString("DOC_ID"));
                    d.put("title", rs.getString("TITLE"));
                    LocalDateTime dt = toLocal(rs.getTimestamp("DOC_TIME"));
                    d.put("docTime", dt == null ? null : TS_FMT.format(dt));
                    d.put("_sort", dt == null ? "" : TS_FMT.format(dt));
                    docs.add(d);
                });
            } catch (Exception e) {
                warnings.add("JHEMR 查询失败: " + oraOf(e));
            }
        }
        docs.sort(Comparator.comparing(d -> (String) d.get("_sort")));
        docs.forEach(d -> d.remove("_sort"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("documents", docs);
        resp.put("warnings", warnings);
        return resp;
    }

    /** 病历正文：sys=MTL 按 UHID+文书ID 查段落内容；sys=JHEMR 按唯一标识查正文；CLOB 只读前 20000 字符。 */
    public Map<String, Object> content(String sys, String docId, String patientId,
                                       String visitId, String inpNo) {
        JdbcTemplate oracle = oracle();
        String sql;
        org.springframework.jdbc.core.PreparedStatementSetter setter;
        if ("MTL".equals(sys)) {
            if (!notBlank(inpNo) || !notBlank(visitId)) {
                throw new BizException(400, "MTL 正文调阅需要 住院号+住院次数");
            }
            String[] parts = splitDocId(docId);
            validateStatic(MTL_CONTENT_SQL);
            sql = MTL_CONTENT_SQL;
            setter = ps -> {
                ps.setString(1, inpNo);
                ps.setString(2, visitId);
                ps.setString(3, parts[0]);
                ps.setString(4, parts[1]);
            };
        } else if ("JHEMR".equals(sys)) {
            if (!notBlank(patientId) || !notBlank(visitId)) {
                throw new BizException(400, "JHEMR 正文调阅需要 患者ID+住院次数");
            }
            validateStatic(JHEMR_CONTENT_SQL);
            sql = JHEMR_CONTENT_SQL;
            setter = ps -> {
                ps.setString(1, patientId);
                ps.setString(2, visitId);
                ps.setString(3, docId);
            };
        } else {
            throw new BizException(400, "sys 必须为 MTL / JHEMR");
        }
        List<Map<String, Object>> rows = oracle.query(sql, setter, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", rs.getString("TITLE"));
            LocalDateTime dt = toLocal(rs.getTimestamp("DOC_TIME"));
            m.put("docTime", dt == null ? null : TS_FMT.format(dt));
            m.put("content", clobToText(rs.getObject("CONTENT")));
            return m;
        });
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("title", rows.isEmpty() ? null : rows.get(0).get("title"));
        resp.put("docTime", rows.isEmpty() ? null : rows.get(0).get("docTime"));
        resp.put("content", rows.isEmpty() ? null : rows.get(0).get("content"));
        return resp;
    }

    private String clobToText(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                return len == 0 ? "" : clob.getSubString(1, (int) Math.min(len, 20_000));
            } catch (SQLException e) {
                return "(clob 读取失败)";
            }
        }
        String s = v.toString();
        return s.length() > 20_000 ? s.substring(0, 20_000) : s;
    }

    private LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String oraOf(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage() == null ? "" : root.getMessage();
        Matcher m = Pattern.compile("ORA-\\d{4,5}").matcher(msg);
        if (m.find()) {
            return m.group();
        }
        if (root instanceof SQLException se && se.getErrorCode() != 0) {
            return se.getClass().getSimpleName() + " (vendorCode=" + se.getErrorCode() + ")";
        }
        return root.getClass().getSimpleName();
    }
}
