package com.hospital.eval.result.controller;

import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.R;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.result.service.DetailQueryService;
import com.hospital.eval.result.service.DetailQueryService.DetailQuery;
import com.hospital.eval.system.service.OperLogService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者明细分页与导出（04 §12.6 / TASK-10 步骤 5）。
 * 病历接口禁止无患者条件查询；导出以流返回，不落项目目录。
 */
@RestController
@RequestMapping("/api/result")
@RequiredArgsConstructor
public class DetailController {

    private final DetailQueryService detailQueryService;
    private final MetricDictMapper dictMapper;
    private final OperLogService operLogService;

    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam long metricId,
                                         @RequestParam long scopeId,
                                         @RequestParam int year,
                                         @RequestParam(defaultValue = "DEN") String role,
                                         @RequestParam(required = false) Long batchId,
                                         @RequestParam(required = false) String keyword,
                                         PageQuery pageQuery) {
        var rows = detailQueryService.queryPaged(
                new DetailQuery(metricId, scopeId, year, role, batchId, keyword),
                pageQuery.getPage(), pageQuery.getSize());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("columns", rows.columns());
        resp.put("total", rows.total());
        resp.put("list", rows.list());
        return R.ok(resp);
    }

    @GetMapping("/detail/export")
    public void export(@RequestParam long metricId,
                       @RequestParam long scopeId,
                       @RequestParam int year,
                       @RequestParam(defaultValue = "DEN") String role,
                       @RequestParam(required = false) Long batchId,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws java.io.IOException {
        var query = new DetailQuery(metricId, scopeId, year, role, batchId, keyword);
        var rows = detailQueryService.queryForExport(query);
        String metricCode = dictMapper.selectById(metricId) == null ? ""
                : (dictMapper.selectById(metricId).getMetricCode() == null ? ""
                : dictMapper.selectById(metricId).getMetricCode());
        String fileName = "明细_" + metricCode + "_" + year + "_" + role + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        SXSSFWorkbook wb = new SXSSFWorkbook(1_000);
        Sheet sheet = wb.createSheet("明细");
        String[] headers = {"住院号", "患者ID", "姓名", "住院次数", "主要诊断编码", "主要诊断名称",
                "第一手术编码", "第一手术名称", "入院科室", "出院科室", "入院时间", "出院时间", "文书名称"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        int r = 1;
        for (Map<String, Object> row : rows.list()) {
            Row xr = sheet.createRow(r++);
            xr.createCell(0).setCellValue(text(row.get("inpNo")));
            xr.createCell(1).setCellValue(text(row.get("patientId")));
            xr.createCell(2).setCellValue(text(row.get("patientName")));
            xr.createCell(3).setCellValue(text(row.get("visitId")));
            xr.createCell(4).setCellValue(text(row.get("mainDiagCode")));
            xr.createCell(5).setCellValue(text(row.get("mainDiagName")));
            xr.createCell(6).setCellValue(text(row.get("mainOperCode")));
            xr.createCell(7).setCellValue(text(row.get("mainOperName")));
            xr.createCell(8).setCellValue(text(row.get("admitDeptName")));
            xr.createCell(9).setCellValue(text(row.get("dischargeDeptName")));
            xr.createCell(10).setCellValue(text(row.get("admitTime")));
            xr.createCell(11).setCellValue(text(row.get("dischargeTime")));
            xr.createCell(12).setCellValue(text(row.get("emrDocTitle")));
        }
        wb.write(response.getOutputStream());
        wb.dispose();
        operLogService.log("RESULT", "EXPORT_DETAIL", "eval_metric_detail", metricCode,
                Map.of("year", year, "role", role, "rows", rows.list().size()));
    }

    private String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
