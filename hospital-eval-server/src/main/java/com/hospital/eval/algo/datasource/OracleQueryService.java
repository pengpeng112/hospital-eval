package com.hospital.eval.algo.datasource;

import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.ValidationResult;
import com.hospital.eval.common.BizException;
import com.hospital.eval.config.OracleDataSourceConfig.JdbcTemplateSpec;
import com.hospital.eval.config.OracleDataSourceConfig.OraclePools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle 只读查询服务（TASK-06 步骤 4）：ping 连通测试与 trial 试跑（≤20 行）。
 * 所有异常信息只保留简短类名与 ORA-xxxxx / vendorCode，杜绝账号与连接串泄漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OracleQueryService {

    private static final Pattern ORA_RE = Pattern.compile("ORA-\\d{4,5}");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TRIAL_MAX_ROWS = 20;
    private static final int TRIAL_TIMEOUT_SECONDS = 120;
    private static final int CLOB_MAX_CHARS = 200;

    private final OraclePools pools;
    private final ReadOnlySqlValidator validator;

    public List<Map<String, Object>> datasources() {
        List<Map<String, Object>> out = new ArrayList<>();
        pools.infos().forEach((key, info) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", info.key());
            row.put("host", info.host());
            row.put("configured", info.configured());
            out.add(row);
        });
        return out;
    }

    public Map<String, Object> ping(String key) {
        JdbcTemplateSpec spec = specOf(key);
        JdbcTemplate t = templateOf(spec, spec.queryTimeoutSeconds());
        long t0 = System.nanoTime();
        String message;
        boolean ok;
        try {
            t.queryForObject("SELECT 1 FROM DUAL", Integer.class);
            ok = true;
            message = "OK";
        } catch (Exception e) {
            ok = false;
            message = sanitizeException(e);
            log.info("Oracle ping {} 失败: {}", key, message);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", ok);
        resp.put("elapsedMs", elapsedMs);
        resp.put("message", message);
        return resp;
    }

    public Map<String, Object> trial(String sqlText, String key) {
        ValidationResult vr = validator.validate(sqlText);
        if (!vr.isValid()) {
            throw new BizException(400, "SQL 校验未通过：" + String.join("；", vr.getErrors()));
        }
        JdbcTemplateSpec spec = specOf(key);
        JdbcTemplate t = templateOf(spec, TRIAL_TIMEOUT_SECONDS);
        // 禁止无 ROWNUM 包裹试跑（TASK-06 禁止事项）
        String wrapped = "SELECT * FROM (" + vr.getExecutableSql() + ") WHERE ROWNUM <= " + TRIAL_MAX_ROWS;
        long t0 = System.nanoTime();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try {
            t.query(wrapped, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                if (columns.isEmpty()) {
                    int cnt = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= cnt; i++) {
                        columns.add(rs.getMetaData().getColumnLabel(i));
                    }
                }
                if (rows.size() >= TRIAL_MAX_ROWS) {
                    return;
                }
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(convertValue(rs.getObject(i)));
                }
                rows.add(row);
            });
        } catch (Exception e) {
            String sanitized = sanitizeException(e);
            log.info("Oracle trial {} 执行失败: {}", key, sanitized);
            throw new BizException(400, "试跑执行失败: " + sanitized);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("columns", columns);
        resp.put("rows", rows);
        resp.put("rowCount", rows.size());
        resp.put("elapsedMs", elapsedMs);
        return resp;
    }

    private JdbcTemplateSpec specOf(String key) {
        if (key == null || key.isBlank() || !pools.templates().containsKey(key)) {
            throw new BizException(400, "未知或未配置的数据源: " + key);
        }
        return pools.templates().get(key);
    }

    private JdbcTemplate templateOf(JdbcTemplateSpec spec, int queryTimeoutSeconds) {
        JdbcTemplate t = new JdbcTemplate(spec.dataSource());
        t.setQueryTimeout(queryTimeoutSeconds);
        t.setFetchSize(spec.fetchSize());
        return t;
    }

    private Object convertValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                return len == 0 ? "" : clob.getSubString(1, (int) Math.min(len, CLOB_MAX_CHARS));
            } catch (SQLException e) {
                return "(clob)";
            }
        }
        if (v instanceof java.sql.Timestamp ts) {
            return TS_FMT.format(ts.toLocalDateTime());
        }
        if (v instanceof java.sql.Date d) {
            return TS_FMT.format(d.toLocalDate().atStartOfDay());
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof LocalDateTime ldt) {
            return TS_FMT.format(ldt);
        }
        if (v instanceof byte[] bytes) {
            return "(binary " + bytes.length + "B)";
        }
        return v.toString();
    }

    /** 异常信息脱敏：仅简短类名 + ORA-xxxxx 或 (vendorCode=xxxx)。 */
    private String sanitizeException(Exception e) {
        String cls = e.getClass().getSimpleName();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        Matcher m = ORA_RE.matcher(msg);
        if (m.find()) {
            return cls + " " + m.group();
        }
        if (e instanceof SQLException se && se.getErrorCode() != 0) {
            return cls + " (vendorCode=" + se.getErrorCode() + ")";
        }
        Throwable cause = e.getCause();
        if (cause instanceof SQLException cse) {
            Matcher cm = ORA_RE.matcher(cse.getMessage() == null ? "" : cse.getMessage());
            if (cm.find()) {
                return cause.getClass().getSimpleName() + " " + cm.group();
            }
            if (cse.getErrorCode() != 0) {
                return cause.getClass().getSimpleName() + " (vendorCode=" + cse.getErrorCode() + ")";
            }
            return cause.getClass().getSimpleName();
        }
        return cls;
    }
}
