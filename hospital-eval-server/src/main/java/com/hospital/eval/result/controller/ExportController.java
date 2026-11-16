package com.hospital.eval.result.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.R;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.entity.MetricMasterRow;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.metric.mapper.MetricMasterRowMapper;
import com.hospital.eval.result.service.MasterExportService;
import com.hospital.eval.system.service.OperLogService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总表回填导出（TASK-10 步骤 8）：与《数据指标导出调整版.xlsx》同结构。
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final MetricMasterRowMapper masterRowMapper;
    private final MetricDictMapper dictMapper;
    private final MasterExportService masterExportService;
    private final OperLogService operLogService;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/master")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER", "EXPERT", "LEADER"},
            mode = cn.dev33.satoken.annotation.SaMode.OR)
    public void master(@RequestParam(defaultValue = "2022,2023,2024,2025") String years,
                       @RequestParam(required = false) Long batchId,
                       HttpServletResponse response) throws IOException {
        List<Integer> yearList = new ArrayList<>();
        for (String y : years.split(",")) {
            String t = y.trim();
            if (t.matches("^(19|20)\\d{2}$")) {
                yearList.add(Integer.valueOf(t));
            }
        }
        if (yearList.isEmpty()) {
            throw new BizException(400, "years 必须至少包含一个有效年份");
        }

        // 装载总表 4,718 行（按 row_no 升序）与 JSONB 兜底原值
        List<MetricMasterRow> rows = masterRowMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricMasterRow>()
                        .orderByAsc("row_no"));
        List<MasterExportService.MasterRowData> data = new ArrayList<>();
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (MetricMasterRow row : rows) {
            var d = new MasterExportService.MasterRowData();
            d.rowNo = row.getRowNo();
            d.code = row.getCode();
            d.name = row.getName();
            d.rowType = row.getRowType();
            d.metricCode = row.getMetricCode();
            d.atomRole = row.getAtomRole();
            d.origValuesJsonRaw = row.getOrigValuesJson();
            if (d.origValuesJsonRaw != null && !d.origValuesJsonRaw.isBlank()) {
                try {
                    var node = objectMapper.readTree(d.origValuesJsonRaw);
                    node.fields().forEachRemaining(e -> {
                        String year = e.getKey();
                        JsonNode inner = e.getValue();
                        String origVal = inner.path("data").asText(null);
                        if (origVal != null && !origVal.isBlank()) {
                            d.origData.put(year, origVal);
                            d.origSource.put(year, inner.path("source").asText(null));
                        }
                    });
                } catch (Exception ignore) {
                    // JSONB 原文格式异常时该行按无兜底处理
                }
            }
            data.add(d);
        }

        // 结果键：全院行（当期或指定批次）
        Map<String, MasterExportService.HospitalYearValue> results = new java.util.LinkedHashMap<>();
        StringBuilder inMetric = new StringBuilder();
        StringBuilder inPeriod = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int y : yearList) {
            if (inPeriod.length() > 0) {
                inPeriod.append(",");
            }
            inPeriod.append("?");
            args.add(String.valueOf(y));
        }
        String extra = batchId == null ? " AND is_current = true" : " AND batch_id = ?";
        if (batchId != null) {
            args.add(batchId);
        }
        List<Map<String, Object>> rows2 = jdbcTemplate.query(
                "SELECT id, metric_id, scope_id, stat_period, numerator_val, denominator_val, result_val, "
                        + "qualitative_result, atom_values_json, data_source_tag FROM eval_metric_result "
                        + "WHERE scope_id = 0 AND stat_period IN (" + inPeriod + ")" + extra,
                args.toArray(), (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rowNo", rs.getLong("id"));
                    m.put("metricId", rs.getLong("metric_id"));
                    m.put("period", rs.getString("stat_period"));
                    m.put("num", rs.getBigDecimal("numerator_val"));
                    m.put("den", rs.getBigDecimal("denominator_val"));
                    m.put("val", rs.getBigDecimal("result_val"));
                    m.put("qual", rs.getString("qualitative_result"));
                    m.put("tag", rs.getString("data_source_tag"));
                    m.put("atoms", rs.getString("atom_values_json"));
                    return m;
                });
        var applicableByMetric = new java.util.LinkedHashMap<Long, Boolean>();
        for (MetricDict d : dictMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricDict>()
                .select("id", "is_applicable"))) {
            applicableByMetric.put(d.getId(), d.getIsApplicable());
        }
        for (Map<String, Object> m : rows2) {
            long metricId = (long) m.get("metricId");
            String year = (String) m.get("period");
            Integer masterRowNo = masterRowNoByMetricId(metricId);
            if (masterRowNo == null) {
                continue;
            }
            String metricCode = metricCodeByMetricId(metricId);
            var v = new MasterExportService.HospitalYearValue();
            v.numeratorVal = (BigDecimal) m.get("num");
            v.denominatorVal = (BigDecimal) m.get("den");
            v.resultVal = (BigDecimal) m.get("val");
            v.qualitativeResult = (String) m.get("qual");
            v.dataSourceTag = (String) m.get("tag");
            Boolean appl = applicableByMetric.get(metricId);
            v.applicable = appl == null || appl;
            if (m.get("atoms") instanceof String atomsJson && !atomsJson.isBlank()) {
                try {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(atomsJson);
                    node.fields().forEachRemaining(e -> {
                        if (e.getValue().isNumber()) {
                            v.atomValues.put(e.getKey(), e.getValue().decimalValue());
                        }
                    });
                } catch (Exception ignore) {
                    // atom_values_json 非法时该 EXTRA 键留空
                }
            }
            // 双键写入：METRIC 行按 master_row_no|year 命中；ATOM 行按 metric_code|year 兜底命中
            results.put(masterRowNo + "|" + year, v);
            results.put(metricCode + "|" + year, v);
        }

        String exportTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String batchLabel = batchId == null ? "当期" : ("批次 " + batchId);
        String operator = "admin";
        try {
            operator = "user#" + StpUtil.getLoginIdAsLong();
        } catch (Exception ignore) {
            // 无登录上下文（系统调用）
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = "三级医院评审指标回填_"
                + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")) + ".xlsx";
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));

        var wb = new org.apache.poi.xssf.streaming.SXSSFWorkbook(1_000);
        masterExportService.fillWorkbook(wb, data, yearList, results,
                exportTime, operator, batchLabel);
        wb.write(response.getOutputStream());
        wb.dispose();
        operLogService.log("RESULT", "EXPORT_MASTER", "eval_master_row", null,
                Map.of("years", years, "batchId", batchId == null ? "当期" : batchId));
    }

    private Integer masterRowNoByMetricId(long metricId) {
        MetricDict d = dictMapper.selectById(metricId);
        return d == null ? null : d.getMasterRowNo();
    }

    private String metricCodeByMetricId(long metricId) {
        MetricDict d = dictMapper.selectById(metricId);
        return d == null ? null : d.getMetricCode();
    }
}
