package com.hospital.eval.result.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 总表回填导出（TASK-10 步骤 8）：与《数据指标导出调整版.xlsx》同结构重建。
 * 单元格取值优先级：系统结果（当期或指定批次的全院行）→ 兜底 orig_values_json 原值 → 空。
 * 核心构建逻辑为纯函数（buildCore），可脱离 POI 单测。
 */
@Service
public class MasterExportService {

    /** 年份单元格取值。 */
    public record YearCell(String data, String source, String origin) {
        // origin: SYS / ORIG / EMPTY
    }

    /** 单行内存模型。 */
    public static class MasterRowData {
        public int rowNo;
        public String code;
        public String name;
        public String rowType;          // CHAPTER / METRIC / ATOM
        public String metricCode;       // METRIC=自身编号；ATOM=所属指标编号
        public String atomRole;         // NUM / DEN / SINGLE / EXTRA
        public boolean applicable = true;
        public Map<String, String> origData = new TreeMap<>();
        public Map<String, String> origSource = new TreeMap<>();
        public String origValuesJsonRaw; // 原始 JSONB 文本（ExportController 解析后填充）
    }

    /** (metricId, year) 全院行结果。 */
    public static class HospitalYearValue {
        public BigDecimal numeratorVal;
        public BigDecimal denominatorVal;
        public BigDecimal resultVal;
        public String qualitativeResult;
        public String dataSourceTag;    // SYS / MANUAL / HYBRID
        public boolean applicable = true;
        public Map<String, BigDecimal> atomValues = new LinkedHashMap<>();
    }

    /** 导出统计。 */
    public static class ExportCounters {
        public int sysCells;
        public int origCells;
        public int emptyCells;
    }

    /** 核心构建（纯逻辑，按行按年精确取值）：rows 按 row_no 升序；results 键 = rowNo + "|" + 年份。 */
    public static ExportCore buildCore(List<MasterRowData> rows, List<Integer> years,
                                       Map<String, HospitalYearValue> results) {
        ExportCore core = new ExportCore();
        for (MasterRowData r : rows) {
            for (int year : years) {
                String key = r.rowNo + "|" + year;
                String data = null;
                String source = null;
                if ("CHAPTER".equals(r.rowType)) {
                    core.cellKeys.put(key, new YearCell(null, null, "EMPTY"));
                    core.counters.emptyCells++; // 章节两列恒空
                    continue;
                }
                // METRIC 行按自身 (rowNo, year) 取结果；ATOM 行优先自身结果，
                // 无则兜底所属指标 (metricCode, year) 的结果
                HospitalYearValue own = results.get(r.rowNo + "|" + year);
                HospitalYearValue metric = results.get(metricCodeOf(r) + "|" + year);
                HospitalYearValue effective = "ATOM".equals(r.rowType)
                        ? (own != null ? own : metric)
                        : own;
                if (effective != null) {
                    BigDecimal dataNum = null;
                    String text = null;
                    switch (r.atomRole == null ? "METRIC" : r.atomRole) {
                        case "METRIC" -> {
                            if (effective.qualitativeResult != null && !effective.qualitativeResult.isBlank()) {
                                text = effective.qualitativeResult;
                            } else {
                                dataNum = effective.resultVal;
                            }
                        }
                        case "NUM" -> dataNum = effective.numeratorVal;
                        case "DEN" -> dataNum = effective.denominatorVal;
                        case "SINGLE" -> {
                            dataNum = effective.numeratorVal;
                            if (dataNum == null && effective.qualitativeResult != null) {
                                text = effective.qualitativeResult;
                            }
                        }
                        case "EXTRA" -> dataNum = effective.atomValues.get(r.code);
                        default -> { }
                    }
                    if (text != null) {
                        data = text;
                    } else if (dataNum != null) {
                        data = plainString(dataNum);
                    }
                    source = sourceOf(effective);
                }
                if (data != null && !data.isBlank()) {
                    core.cellKeys.put(key, new YearCell(data, source, "SYS"));
                    core.counters.sysCells++;
                } else if (r.origData.containsKey(String.valueOf(year))) {
                    data = r.origData.get(String.valueOf(year));
                    source = r.origSource.get(String.valueOf(year));
                    core.cellKeys.put(key, new YearCell(data, source, "ORIG"));
                    core.counters.origCells++;
                } else {
                    core.cellKeys.put(key, new YearCell(null, null, "EMPTY"));
                    core.counters.emptyCells++;
                }
            }
        }
        return core;
    }

    private static String metricCodeOf(MasterRowData r) {
        // ATOM 行的所属指标编号；METRIC/CHAPTER 用自身编号
        return "ATOM".equals(r.rowType) ? r.metricCode : r.code;
    }

    private static String sourceOf(HospitalYearValue v) {
        if (!v.applicable) {
            return "不适用";
        }
        return switch (v.dataSourceTag == null ? "" : v.dataSourceTag) {
            case "SYS" -> "医院系统提取";
            case "HYBRID" -> "医院系统提取+手工统计";
            case "MANUAL" -> "手工统计";
            case "PROV" -> "省级系统提取";
            default -> "";
        };
    }

    private static String plainString(BigDecimal v) {
        if (v == null) {
            return null;
        }
        return v.stripTrailingZeros().toPlainString();
    }

    /** 构建产物：cellKeys 键 = "rowNo|year"（写 POI 用），另有说明 Sheet 计数。 */
    public static class ExportCore {
        public final Map<String, YearCell> cellKeys = new LinkedHashMap<>();
        public final ExportCounters counters = new ExportCounters();
    }

    // ============ POI 组装（由 Controller/服务调用） ============

    public void fillWorkbook(SXSSFWorkbook wb, List<MasterRowData> rows, List<Integer> years,
                             Map<String, HospitalYearValue> results,
                             String exportTime, String operator, String batchLabel) {
        ExportCore core = buildCore(rows, years, results);
        Sheet sheet = wb.createSheet("sheet1");
        Row h1 = sheet.createRow(0);
        Row h2 = sheet.createRow(1);
        h1.createCell(0).setCellValue("编号");
        h1.createCell(1).setCellValue("名称");
        h1.createCell(2).setCellValue("类型");
        h2.createCell(0).setCellValue("编号");
        h2.createCell(1).setCellValue("名称");
        h2.createCell(2).setCellValue("类型");
        for (int i = 0; i < years.size(); i++) {
            h1.createCell(3 + i * 2).setCellValue(String.valueOf(years.get(i)));
            h1.createCell(4 + i * 2).setCellValue(String.valueOf(years.get(i)));
            h2.createCell(3 + i * 2).setCellValue("数据");
            h2.createCell(4 + i * 2).setCellValue("数据来源");
        }
        int r = 2;
        for (MasterRowData row : rows) {
            Row xr = sheet.createRow(r);
            xr.createCell(0).setCellValue(row.code);
            xr.createCell(1).setCellValue(row.name);
            xr.createCell(2).setCellValue(chineseType(row.rowType));
            for (int i = 0; i < years.size(); i++) {
                YearCell yc = core.cellKeys.get(row.rowNo + "|" + years.get(i));
                Cell dataCell = xr.createCell(3 + i * 2);
                Cell sourceCell = xr.createCell(4 + i * 2);
                if (yc == null || yc.data() == null) {
                    continue; // EMPTY：留空
                }
                BigDecimal num = toNumberOrNull(yc.data());
                if (num != null && !"ORIG".equals(yc.origin())) {
                    dataCell.setCellType(CellType.NUMERIC);
                    dataCell.setCellValue(num.doubleValue());
                } else {
                    dataCell.setCellValue(yc.data());
                }
                if (yc.source() != null) {
                    sourceCell.setCellValue(yc.source());
                }
            }
            r++;
        }
        Sheet note = wb.createSheet("导出说明");
        Row n0 = note.createRow(0);
        n0.createCell(0).setCellValue("导出时间");
        n0.createCell(1).setCellValue(exportTime);
        Row n1 = note.createRow(1);
        n1.createCell(0).setCellValue("导出人");
        n1.createCell(1).setCellValue(operator);
        Row n2 = note.createRow(2);
        n2.createCell(0).setCellValue("批次");
        n2.createCell(1).setCellValue(batchLabel);
        Row n3 = note.createRow(3);
        n3.createCell(0).setCellValue("年份");
        n3.createCell(1).setCellValue(years.toString());
        Row n4 = note.createRow(4);
        n4.createCell(0).setCellValue("系统结果填写单元格数");
        n4.createCell(1).setCellValue(core.counters.sysCells);
        Row n5 = note.createRow(5);
        n5.createCell(0).setCellValue("沿用原表值单元格数");
        n5.createCell(1).setCellValue(core.counters.origCells);
        Row n6 = note.createRow(6);
        n6.createCell(0).setCellValue("空单元格数");
        n6.createCell(1).setCellValue(core.counters.emptyCells);
    }

    private static String chineseType(String rowType) {
        return switch (rowType) {
            case "CHAPTER" -> "章节";
            case "METRIC" -> "指标";
            case "ATOM" -> "基础指标";
            default -> rowType;
        };
    }

    private static BigDecimal toNumberOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
