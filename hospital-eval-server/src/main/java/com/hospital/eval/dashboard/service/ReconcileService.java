package com.hospital.eval.dashboard.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.system.service.OperLogService;
import com.hospital.eval.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 省平台对账（TASK-17 步骤 2）：模板下载、导入（含 flag 判定）、结果查询与导出。
 * 表头按列名定位（不按列号）；跳过空行与 `#` 开头的说明行。
 */
@Service
@RequiredArgsConstructor
public class ReconcileService {

    private static final String[] TEMPLATE_HEADERS =
            {"指标编号", "指标名称", "统计年份", "省分子", "省分母", "省结果值"};
    private static final String[] IMPORT_HEADERS =
            {"指标编号", "指标名称", "统计年份", "省分子", "省分母", "省结果值"};

    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;
    private final OperLogService operLogService;

    // ---------------- 模板 ----------------

    public byte[] template() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("省平台对账");
            var headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row head = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(TEMPLATE_HEADERS[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 16 * 256);
            }
            // 2 行示例说明（`#` 开头的指标编号为说明行，导入时跳过）
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("#示例：填写省平台反馈的分子/分母/结果值");
            r1.createCell(2).setCellValue(2025);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("#如 25.2.3.3.5.1.1");
            r2.createCell(1).setCellValue("癫痫发作频率记录率（NEU-EPI-01）");
            r2.createCell(2).setCellValue(2025);
            r2.createCell(3).setCellValue(10);
            r2.createCell(4).setCellValue(100);
            r2.createCell(5).setCellValue(0.1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(500, "模板生成失败：" + e.getMessage());
        }
    }

    // ---------------- 导入 ----------------

    @Transactional
    public Map<String, Object> importFile(MultipartFile file, long userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的文件");
        }
        String importId = "REC_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        var user = userService.findById(userId);
        String userName = user == null ? ("user#" + userId) : user.getRealName();

        int total = 0;
        int issueCount = 0;
        List<Object[]> batchArgs = new ArrayList<>();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(file.getInputStream())) {
            var sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(sheet.getFirstRowNum());
            if (head == null) {
                throw new BizException(400, "文件缺少表头行");
            }
            Map<String, Integer> colIdx = headerIndex(head);
            for (String h : IMPORT_HEADERS) {
                if (!colIdx.containsKey(h)) {
                    throw new BizException(400, "表头缺少必需列：" + h);
                }
            }
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String code = stringOf(row, colIdx.get("指标编号"));
                if (code.isBlank() || code.startsWith("#")) {
                    continue; // 空行与说明行跳过
                }
                String name = stringOf(row, colIdx.get("指标名称"));
                Integer year = intOf(row, colIdx.get("统计年份"));
                if (year == null) {
                    throw new BizException(400, "第 " + (i + 1) + " 行统计年份为空或非法");
                }
                BigDecimal provNum = decimalOf(row, colIdx.get("省分子"));
                BigDecimal provDen = decimalOf(row, colIdx.get("省分母"));
                BigDecimal provVal = decimalOf(row, colIdx.get("省结果值"));

                // 院内当期全院行
                Map<String, Object> hosp = hospitalRow(code, year);
                boolean found = hosp != null;
                BigDecimal hospNum = found ? (BigDecimal) hosp.get("num") : null;
                BigDecimal hospDen = found ? (BigDecimal) hosp.get("den") : null;
                BigDecimal hospVal = found ? (BigDecimal) hosp.get("val") : null;
                String flag = ReconcileFlag.flagOf(found, provNum, provDen, provVal, hospNum, hospDen, hospVal);
                if (!ReconcileFlag.OK.equals(flag)) {
                    issueCount++;
                }
                String metricName = found ? (String) hosp.get("metricName") : name;
                batchArgs.add(new Object[]{importId, year, code, metricName,
                        provNum, provDen, provVal, hospNum, hospDen, hospVal, flag, null, userName});
                total++;
            }
        } catch (IOException e) {
            throw new BizException(400, "文件读取失败：" + e.getMessage());
        }
        if (total == 0) {
            throw new BizException(400, "未解析到有效数据行（空行与 # 说明行不计）");
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO eval_prov_reconcile (import_id, stat_year, metric_code, metric_name, "
                        + "prov_num, prov_den, prov_val, hosp_num, hosp_den, hosp_val, flag, remark, import_user) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                batchArgs);
        operLogService.log("RECONCILE", "IMPORT", "eval_prov_reconcile", importId,
                Map.of("total", total, "issueCount", issueCount));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("importId", importId);
        resp.put("total", total);
        resp.put("issueCount", issueCount);
        return resp;
    }

    /** 表头按列名定位。 */
    private Map<String, Integer> headerIndex(Row head) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < head.getLastCellNum(); i++) {
            Cell c = head.getCell(i);
            if (c == null) {
                continue;
            }
            String v = c.getCellType() == CellType.STRING ? c.getStringCellValue().trim() : "";
            if (!v.isBlank()) {
                idx.put(v, i);
            }
        }
        return idx;
    }

    private String stringOf(Row row, Integer col) {
        if (col == null) {
            return "";
        }
        Cell c = row.getCell(col);
        if (c == null) {
            return "";
        }
        if (c.getCellType() == CellType.STRING) {
            return c.getStringCellValue().trim();
        }
        if (c.getCellType() == CellType.NUMERIC) {
            double d = c.getNumericCellValue();
            if (d == Math.floor(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return "";
    }

    private Integer intOf(Row row, Integer col) {
        String s = stringOf(row, col);
        if (s.matches("^(19|20)\\d{2}$")) {
            return Integer.valueOf(s);
        }
        return null;
    }

    private BigDecimal decimalOf(Row row, Integer col) {
        if (col == null) {
            return null;
        }
        Cell c = row.getCell(col);
        if (c == null) {
            return null;
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(c.getNumericCellValue());
        }
        if (c.getCellType() == CellType.STRING) {
            String s = c.getStringCellValue().trim();
            if (s.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 按指标编号 + 年份取院内当期全院行（metric_code 精确匹配）。 */
    private Map<String, Object> hospitalRow(String metricCode, int year) {
        List<Integer> ids = jdbcTemplate.queryForList(
                "SELECT id FROM eval_metric_dict WHERE metric_code = ? LIMIT 1", Integer.class, metricCode);
        if (ids.isEmpty()) {
            return null; // 编号不存在 → NOT_FOUND
        }
        Integer dictId = ids.get(0);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.numerator_val, r.denominator_val, r.result_val, d.metric_name "
                        + "FROM eval_metric_result r JOIN eval_metric_dict d ON d.id = r.metric_id "
                        + "WHERE r.metric_id = ? AND r.scope_id = 0 AND r.is_current = true "
                        + "AND r.stat_period_type = 'YEAR' AND r.stat_period = ? "
                        + "ORDER BY r.id DESC LIMIT 1",
                dictId, String.valueOf(year));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("num", (BigDecimal) r.get("numerator_val"));
        out.put("den", (BigDecimal) r.get("denominator_val"));
        out.put("val", (BigDecimal) r.get("result_val"));
        out.put("metricName", r.get("metric_name"));
        return out;
    }

    // ---------------- 查询与导出 ----------------

    public List<Map<String, Object>> imports() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT import_id, max(import_user) import_user, max(import_time) import_time, "
                        + "count(*) total, count(*) FILTER (WHERE flag IS DISTINCT FROM 'OK') issue_count "
                        + "FROM eval_prov_reconcile GROUP BY import_id ORDER BY max(import_time) DESC",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("importId", rs.getString("import_id"));
                    m.put("importUser", rs.getString("import_user"));
                    m.put("importTime", rs.getString("import_time"));
                    m.put("total", rs.getInt("total"));
                    m.put("issueCount", rs.getInt("issue_count"));
                    out.add(m);
                });
        return out;
    }

    public Map<String, Object> rows(String importId, Boolean onlyIssue, int page, int size) {
        String where = "WHERE import_id = ?" + (Boolean.TRUE.equals(onlyIssue) ? " AND flag IS DISTINCT FROM 'OK'" : "");
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_prov_reconcile " + where, Integer.class, importId);
        List<Map<String, Object>> list = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id, stat_year, metric_code, metric_name, prov_num, prov_den, prov_val, "
                        + "hosp_num, hosp_den, hosp_val, flag, remark "
                        + "FROM eval_prov_reconcile " + where + " ORDER BY id LIMIT ? OFFSET ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("statYear", rs.getInt("stat_year"));
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("metricName", rs.getString("metric_name"));
                    m.put("provNum", rs.getBigDecimal("prov_num"));
                    m.put("provDen", rs.getBigDecimal("prov_den"));
                    m.put("provVal", rs.getBigDecimal("prov_val"));
                    m.put("hospNum", rs.getBigDecimal("hosp_num"));
                    m.put("hospDen", rs.getBigDecimal("hosp_den"));
                    m.put("hospVal", rs.getBigDecimal("hosp_val"));
                    m.put("flag", rs.getString("flag"));
                    m.put("remark", rs.getString("remark"));
                    list.add(m);
                }, importId, size, (page - 1) * size);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total == null ? 0 : total);
        resp.put("list", list);
        return resp;
    }

    public byte[] export(String importId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT stat_year, metric_code, metric_name, prov_num, prov_den, prov_val, "
                        + "hosp_num, hosp_den, hosp_val, flag, remark FROM eval_prov_reconcile "
                        + "WHERE import_id = ? ORDER BY id",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("statYear", rs.getInt("stat_year"));
                    m.put("metricCode", rs.getString("metric_code"));
                    m.put("metricName", rs.getString("metric_name"));
                    m.put("provNum", rs.getBigDecimal("prov_num"));
                    m.put("provDen", rs.getBigDecimal("prov_den"));
                    m.put("provVal", rs.getBigDecimal("prov_val"));
                    m.put("hospNum", rs.getBigDecimal("hosp_num"));
                    m.put("hospDen", rs.getBigDecimal("hosp_den"));
                    m.put("hospVal", rs.getBigDecimal("hosp_val"));
                    m.put("flag", rs.getString("flag"));
                    m.put("remark", rs.getString("remark"));
                    rows.add(m);
                }, importId);
        String[] headers = {"指标编号", "指标名称", "统计年份", "省分子", "省分母", "省结果值",
                "院内分子", "院内分母", "院内结果值", "问题类型", "问题说明", "备注"};
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("对账差异清单");
            var headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 15 * 256);
            }
            int r = 1;
            for (Map<String, Object> m : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) m.get("metricCode"));
                row.createCell(1).setCellValue((String) m.get("metricName"));
                row.createCell(2).setCellValue((Integer) m.get("statYear"));
                row.createCell(3).setCellValue(numText(m.get("provNum")));
                row.createCell(4).setCellValue(numText(m.get("provDen")));
                row.createCell(5).setCellValue(numText(m.get("provVal")));
                row.createCell(6).setCellValue(numText(m.get("hospNum")));
                row.createCell(7).setCellValue(numText(m.get("hospDen")));
                row.createCell(8).setCellValue(numText(m.get("hospVal")));
                row.createCell(9).setCellValue((String) m.get("flag"));
                row.createCell(10).setCellValue(ReconcileFlag.labelOf((String) m.get("flag")));
                row.createCell(11).setCellValue((String) m.get("remark"));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(500, "导出失败：" + e.getMessage());
        }
    }

    private static String numText(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
