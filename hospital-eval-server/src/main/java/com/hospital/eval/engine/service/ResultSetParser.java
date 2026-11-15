package com.hospital.eval.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果集解析器（TASK-07 步骤 3，04 §8.1~8.3）。
 *
 * 以 RowCallbackHandler 逐行流式处理（配合 JdbcTemplate fetchSize 流式读取）：
 * - SUM：年份列识别 → 年份过滤 → 数值列（优先别名）→ 同年累加（MULTI_ROWS_SUMMED）；
 * - DTL：固定列映射 + 其余列进 ext（截断 500 字符，CLOB 只取前 500）；
 * - 年份列别名顺序：年份 / 统计年份 / STAT_YEAR / YEAR / YR；
 * - 无年份列且 year_from != year_to → NoYearColumnException（NO_YEAR_COLUMN）；
 * - 原始行数 > 500,000 → RowLimitExceededException（ROW_LIMIT_EXCEEDED）。
 * 每个子任务新建一个解析器实例（内部保存列映射状态），并发子任务互不共享。
 */
public class ResultSetParser {

    private static final String[] YEAR_ALIASES = {"年份", "统计年份", "STAT_YEAR", "YEAR", "YR"};
    private static final String[][] FIELD_ALIASES = {
            {"patientId", "患者ID", "PATIENT_ID"},
            {"inpNo", "住院号", "INP_NO", "门诊号"},
            {"visitId", "住院次数", "VISIT_ID", "就诊次数"},
            {"patientName", "姓名", "NAME", "PATIENT_NAME"},
            {"admitDeptName", "入院科室"},
            {"dischargeDeptName", "出院科室", "患者科室"},
            {"admitTime", "入院时间"},
            {"dischargeTime", "出院时间"},
            {"mainDiagCode", "出院主要诊断编码", "主要诊断编码"},
            {"mainDiagName", "诊断名称", "出院主要诊断名称"},
            {"mainOperCode", "第一位手术编码", "手术编码"},
            {"mainOperName", "第一位手术名称", "手术名称"},
            {"emrSysSource", "文书系统"},
            {"emrDocId", "文书唯一标识", "文书序号"},
            {"emrDocTitle", "文书名称", "文书类别"},
    };
    private static final String[] SUM_PRIORITY = {"分子", "分母", "NUMERATOR", "DENOMINATOR", "人次", "例数", "CNT"};
    private static final int ROW_LIMIT = 500_000;
    private static final int EXT_MAX_CHARS = 500;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern YEAR_IN_STR = Pattern.compile("\\d{4}");

    /** 解析上下文（04 §8.1）。 */
    public record ParseContext(String sqlRole, int yearFrom, int yearTo, Set<Integer> assignedYears) {
    }

    /** SUM 累积结果。 */
    @Data
    public static class SumResult {
        private final Map<Integer, BigDecimal> sumByYear = new TreeMap<>();
        private boolean multiRowsSummed;
        private int rawRowCount;
    }

    /** DTL 一行的解析产物。 */
    @Data
    public static class ParsedDetail {
        private int statYear;
        private String statMonth;
        private String patientId;
        private String inpNo;
        private String visitId;
        private String patientName;
        private String admitDeptName;
        private String dischargeDeptName;
        private LocalDateTime admitTime;
        private LocalDateTime dischargeTime;
        private String mainDiagCode;
        private String mainDiagName;
        private String mainOperCode;
        private String mainOperName;
        private String emrSysSource;
        private String emrDocId;
        private String emrDocTitle;
        private Map<String, String> ext = new LinkedHashMap<>();
    }

    public static class NoYearColumnException extends RuntimeException {
        public NoYearColumnException() {
            super("NO_YEAR_COLUMN");
        }
    }

    public static class RowLimitExceededException extends RuntimeException {
        public RowLimitExceededException() {
            super("ROW_LIMIT_EXCEEDED");
        }
    }

    /** DTL 行消费者（DetailWriter 实现）。 */
    public interface DetailSink {
        void row(ParsedDetail detail) throws SQLException;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    // —— 每任务状态（首次行回调时惰性解析元数据）——
    private int rawRowCount;
    private boolean columnsResolved;
    private int yearColIndex = -1;          // -1 = 无年份列
    private Integer fixedYear;              // 无年份列且 year_from==year_to 时的固定年份
    private int sumValueColIndex = -1;      // SUM：优先别名列
    private final int[] fieldColIndex = new int[FIELD_ALIASES.length];
    private final int[] extColumns = new int[256];
    private int extCount;
    private int sumFallbackCount;           // SUM：非年份、非优先列数

    public RowCallbackHandler sumHandler(ParseContext ctx, SumResult out) {
        return rs -> {
            out.setRawRowCount(++rawRowCount);
            ensureColumns(rs, ctx);
            Integer year = resolveYear(rs, ctx);
            if (year == null) {
                return;
            }
            BigDecimal value = extractSumValue(rs);
            if (value == null) {
                return;
            }
            if (out.getSumByYear().containsKey(year)) {
                out.setMultiRowsSummed(true);
                out.getSumByYear().put(year, out.getSumByYear().get(year).add(value));
            } else {
                out.getSumByYear().put(year, value);
            }
        };
    }

    public RowCallbackHandler detailHandler(ParseContext ctx, DetailSink sink) {
        return rs -> {
            rawRowCount++;
            if (rawRowCount > ROW_LIMIT) {
                throw new RowLimitExceededException();
            }
            ensureColumns(rs, ctx);
            Integer year = resolveYear(rs, ctx);
            if (year == null) {
                return;
            }
            ParsedDetail d = new ParsedDetail();
            d.setStatYear(year);
            for (int f = 0; f < FIELD_ALIASES.length; f++) {
                int col = fieldColIndex[f];
                if (col <= 0) {
                    continue;
                }
                assignField(d, FIELD_ALIASES[f][0], col <= rs.getMetaData().getColumnCount() ? rs.getObject(col) : null);
            }
            for (int i = 0; i < extCount; i++) {
                int col = extColumns[i];
                String v = extString(rs.getObject(col));
                if (v != null) {
                    d.getExt().put(label(rs, col), v);
                }
            }
            if (d.getDischargeTime() != null) {
                d.setStatMonth(d.getDischargeTime().format(DateTimeFormatter.ofPattern("yyyy-MM")));
            }
            sink.row(d);
        };
    }

    public int getRawRowCount() {
        return rawRowCount;
    }

    // ------------------------------------------------------------------

    private void ensureColumns(ResultSet rs, ParseContext ctx) throws SQLException {
        if (columnsResolved) {
            return;
        }
        columnsResolved = true;
        ResultSetMetaData md = rs.getMetaData();
        int count = md.getColumnCount();
        // 年份列：按别名顺序优先
        for (String alias : YEAR_ALIASES) {
            for (int i = 1; i <= count; i++) {
                if (matches(md.getColumnLabel(i), alias)) {
                    yearColIndex = i;
                    break;
                }
            }
            if (yearColIndex > 0) {
                break;
            }
        }
        if (yearColIndex <= 0) {
            if (ctx.yearFrom() != ctx.yearTo()) {
                throw new NoYearColumnException();
            }
            fixedYear = ctx.yearFrom();
        }
        if (ctx.sqlRole().endsWith("_SUM")) {
            // 优先别名列
            for (String alias : SUM_PRIORITY) {
                for (int i = 1; i <= count; i++) {
                    if (i == yearColIndex || matches(md.getColumnLabel(i), alias)) {
                        sumValueColIndex = i == yearColIndex ? -1 : i;
                        break;
                    }
                }
                if (sumValueColIndex > 0) {
                    break;
                }
            }
            sumFallbackCount = count; // 兜底逐列扫描的列数上限
        } else {
            // DTL 字段映射 + 其余列进 ext（年份列除外）
            boolean[] mapped = new boolean[count + 1];
            if (yearColIndex > 0) {
                mapped[yearColIndex] = true;
            }
            for (int f = 0; f < FIELD_ALIASES.length; f++) {
                fieldColIndex[f] = -1;
                for (int i = 1; i <= count; i++) {
                    if (mapped[i]) {
                        continue;
                    }
                    boolean hit = false;
                    for (String alias : FIELD_ALIASES[f]) {
                        if (matches(md.getColumnLabel(i), alias)) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        fieldColIndex[f] = i;
                        mapped[i] = true;
                        break;
                    }
                }
            }
            for (int i = 1; i <= count; i++) {
                if (!mapped[i] && extCount < extColumns.length) {
                    extColumns[extCount++] = i;
                }
            }
        }
    }

    private boolean matches(String label, String alias) {
        return label != null && label.trim().equalsIgnoreCase(alias);
    }

    private String label(ResultSet rs, int col) throws SQLException {
        return rs.getMetaData().getColumnLabel(col);
    }

    /** 年份归一：数字 / 字符串前 4 位 / 日期；过滤掉范围外或不在 assignedYears 的行（返回 null 跳过）。 */
    private Integer resolveYear(ResultSet rs, ParseContext ctx) throws SQLException {
        Integer year;
        if (yearColIndex <= 0) {
            year = fixedYear;
        } else {
            Object v = rs.getObject(yearColIndex);
            year = toYear(v);
            if (year == null) {
                return null;
            }
        }
        if (year < ctx.yearFrom() || year > ctx.yearTo()) {
            return null;
        }
        if (ctx.assignedYears() != null && !ctx.assignedYears().contains(year)) {
            return null;
        }
        return year;
    }

    private Integer toYear(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number num) {
            return num.intValue();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().getYear();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().getYear();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.getYear();
        }
        Matcher m = YEAR_IN_STR.matcher(v.toString());
        return m.find() ? Integer.valueOf(m.group()) : null;
    }

    /** SUM 数值列：优先别名列有值且可转数字则用之；否则取第一个能转成数字的非年份列。 */
    private BigDecimal extractSumValue(ResultSet rs) throws SQLException {
        if (sumValueColIndex > 0) {
            return toBigDecimal(rs.getObject(sumValueColIndex));
        }
        for (int i = 1; i <= sumFallbackCount; i++) {
            if (i == yearColIndex) {
                continue;
            }
            BigDecimal v = toBigDecimal(rs.getObject(i));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number num) {
            try {
                return new BigDecimal(num.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            String s = v.toString().trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void assignField(ParsedDetail d, String field, Object v) {
        if (v == null) {
            return;
        }
        switch (field) {
            case "patientId" -> d.setPatientId(stringOf(v));
            case "inpNo" -> d.setInpNo(stringOf(v));
            case "visitId" -> d.setVisitId(stringOf(v));
            case "patientName" -> d.setPatientName(stringOf(v));
            case "admitDeptName" -> d.setAdmitDeptName(stringOf(v));
            case "dischargeDeptName" -> d.setDischargeDeptName(stringOf(v));
            case "admitTime" -> d.setAdmitTime(toLocalDateTime(v));
            case "dischargeTime" -> d.setDischargeTime(toLocalDateTime(v));
            case "mainDiagCode" -> d.setMainDiagCode(stringOf(v));
            case "mainDiagName" -> d.setMainDiagName(stringOf(v));
            case "mainOperCode" -> d.setMainOperCode(stringOf(v));
            case "mainOperName" -> d.setMainOperName(stringOf(v));
            case "emrSysSource" -> d.setEmrSysSource(stringOf(v));
            case "emrDocId" -> d.setEmrDocId(stringOf(v));
            case "emrDocTitle" -> d.setEmrDocTitle(stringOf(v));
            default -> { /* 不可达：FIELD_ALIASES 与此 switch 同步维护 */ }
        }
    }

    private String stringOf(Object v) {
        if (v instanceof Clob clob) {
            try {
                long len = clob.length();
                return len == 0 ? "" : clob.getSubString(1, (int) Math.min(len, EXT_MAX_CHARS));
            } catch (SQLException e) {
                return "(clob)";
            }
        }
        return v.toString();
    }

    private LocalDateTime toLocalDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        return null;
    }

    private String extString(Object v) {
        if (v == null) {
            return null;
        }
        String s;
        if (v instanceof LocalDateTime ldt) {
            s = TS_FMT.format(ldt);
        } else if (v instanceof java.sql.Timestamp ts) {
            s = TS_FMT.format(ts.toLocalDateTime());
        } else {
            s = stringOf(v);
        }
        return s.length() > EXT_MAX_CHARS ? s.substring(0, EXT_MAX_CHARS) : s;
    }

    /** ext Map → JSON 字符串（写入 eval_metric_detail.ext_json）。 */
    public String extToJson(Map<String, String> ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ext);
        } catch (Exception e) {
            return null;
        }
    }
}
