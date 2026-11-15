package com.hospital.eval.engine.service;

import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.ValidationResult;
import com.hospital.eval.config.OracleDataSourceConfig.JdbcTemplateSpec;
import com.hospital.eval.config.OracleDataSourceConfig.OraclePools;
import com.hospital.eval.engine.entity.ExecTask;
import com.hospital.eval.engine.service.ResultAggregator.TaskOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单条 SQL 的校验与执行（TASK-07 步骤 3，04 §8.4）：
 * validate 不通过 → SANDBOX_REJECTED；数据源未配置 → DATASOURCE_NOT_CONFIGURED；
 * JdbcTemplate 流式读取（queryTimeout/fetchSize 取 eval.engine.*）；
 * SUM → SumResult（同年累加记 MULTI_ROWS_SUMMED）；DTL → DetailWriter 流式落库；
 * 行数超限删除已写明细 → ROW_LIMIT_EXCEEDED；无年份列 → NO_YEAR_COLUMN；
 * Oracle 异常保留 ORA 号与前 500 字符消息。对 Oracle 只执行 SELECT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutor {

    private static final Pattern ORA_RE = Pattern.compile("ORA-\\d{4,5}");
    private static final int ERROR_MSG_MAX = 2000;
    private static final int MESSAGE_KEEP = 500;

    private final OraclePools pools;
    private final ReadOnlySqlValidator validator;
    private final JdbcTemplate jdbcTemplate;

    /** 校验 + 执行 + 明细落库；返回子任务产出（BatchRunner 据此更新 exec_task 并聚合）。 */
    public TaskOutcome execute(ExecTask task, MetricSql sql, Set<Integer> assignedYears, boolean mixedPoolDen) {
        long t0 = System.nanoTime();
        // 1. 沙箱校验（04 §8.4：执行前必须通过）
        ValidationResult vr = validator.validate(sql.getSqlAnnotated());
        if (!vr.isValid()) {
            return outcome(task, "FAILED", 0, t0, "SANDBOX_REJECTED: " + String.join("；", vr.getErrors()), null);
        }
        // 2. 数据源已配置？
        JdbcTemplateSpec spec = pools.templates().get(sql.getDatasourceKey());
        if (spec == null) {
            return outcome(task, "FAILED", 0, t0, "DATASOURCE_NOT_CONFIGURED: " + sql.getDatasourceKey(), null);
        }
        // 3. 流式执行（只读 SELECT）
        ResultSetParser.ParseContext ctx = new ResultSetParser.ParseContext(
                sql.getSqlRole(),
                Integer.parseInt(sql.getPeriodStart().substring(0, 4)),
                Integer.parseInt(sql.getPeriodEnd().substring(0, 4)),
                assignedYears);
        JdbcTemplate oracle = new JdbcTemplate(spec.dataSource());
        oracle.setQueryTimeout(spec.queryTimeoutSeconds());
        oracle.setFetchSize(spec.fetchSize());
        ResultSetParser parser = new ResultSetParser();
        DetailWriter writer = new DetailWriter(jdbcTemplate, parser);
        writer.open(task.getBatchId(), task.getMetricId(), task.getScopeId(),
                sql.getSqlRole(), mixedPoolDen);
        try {
            if (sql.getSqlRole().endsWith("_SUM")) {
                ResultSetParser.SumResult sum = new ResultSetParser.SumResult();
                oracle.query(sql.getSqlExecutable(), parser.sumHandler(ctx, sum));
                String note = sum.isMultiRowsSummed() ? "MULTI_ROWS_SUMMED" : null;
                return outcome(task, "SUCCESS", sum.getRawRowCount(), t0, note, sum.getSumByYear());
            }
            oracle.query(sql.getSqlExecutable(), parser.detailHandler(ctx, writer::add));
            writer.flush();
            return outcome(task, "SUCCESS", parser.getRawRowCount(), t0, null, null);
        } catch (ResultSetParser.RowLimitExceededException e) {
            writer.deleteTaskDetails();
            return outcome(task, "FAILED", parser.getRawRowCount(), t0, "ROW_LIMIT_EXCEEDED", null);
        } catch (ResultSetParser.NoYearColumnException e) {
            return outcome(task, "FAILED", parser.getRawRowCount(), t0, "NO_YEAR_COLUMN", null);
        } catch (Exception e) {
            return outcome(task, "FAILED", parser.getRawRowCount(), t0, sanitize(e), null);
        }
    }

    private TaskOutcome outcome(ExecTask task, String status, int rowCount, long t0,
                                String errorMsg, Map<Integer, BigDecimal> sumByYear) {
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        if (errorMsg != null && errorMsg.length() > ERROR_MSG_MAX) {
            errorMsg = errorMsg.substring(0, ERROR_MSG_MAX);
        }
        if (errorMsg != null && !errorMsg.isBlank()) {
            log.warn("子任务 {} 状态 {}: {}", task.getId(), status, errorMsg);
        }
        return new TaskOutcome(task.getId(), task.getMetricId(), task.getScopeId(), task.getSqlId(),
                task.getSqlRole(), status, rowCount, elapsed, errorMsg, sumByYear);
    }

    /** 异常脱敏：ORA 错误号 + 前 500 字符消息（04 §8.4 / TASK-07 步骤 3.10）。 */
    private String sanitize(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String ora = "";
        Matcher m = ORA_RE.matcher(msg);
        if (m.find()) {
            ora = m.group() + " ";
        } else {
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }
            if (cause instanceof SQLException se && se.getErrorCode() != 0) {
                ora = "(vendorCode=" + se.getErrorCode() + ") ";
            }
        }
        String text = msg.length() > MESSAGE_KEEP ? msg.substring(0, MESSAGE_KEEP) : msg;
        return (ora + text).trim();
    }
}
