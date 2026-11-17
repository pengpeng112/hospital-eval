package com.hospital.eval.dashboard.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监测大屏汇总（TASK-17 步骤 1，04 §12.8）。
 * 全部数字来自 SQL 实时统计，前端不做汇总计算。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> overview(int year) {
        Map<String, Object> resp = new LinkedHashMap<>();

        // ---- cards ----
        Integer metricTotal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dict WHERE is_applicable = true", Integer.class);
        Integer sysCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dict WHERE is_applicable = true "
                        + "AND extract_mode IN ('SYS','HYBRID')", Integer.class);
        Integer manualCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dict WHERE is_applicable = true "
                        + "AND extract_mode = 'MANUAL'", Integer.class);
        Integer archived = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_result r JOIN eval_metric_dict d ON d.id = r.metric_id "
                        + "WHERE r.scope_id = 0 AND r.is_current = true AND r.stat_period_type = 'YEAR' "
                        + "AND r.stat_period = ? AND r.audit_status = 'L3_ARCHIVED' AND d.is_applicable = true",
                Integer.class, String.valueOf(year));
        int total = metricTotal == null ? 0 : metricTotal;
        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("metricTotal", total);
        cards.put("sysRate", total == 0 ? 0.0 : round1(sysCount == null ? 0 : sysCount * 100.0 / total));
        cards.put("manualRate", total == 0 ? 0.0 : round1(manualCount == null ? 0 : manualCount * 100.0 / total));
        cards.put("archivedRate", total == 0 ? 0.0 : round1((archived == null ? 0 : archived) * 100.0 / total));
        resp.put("cards", cards);

        // ---- chapters：level=4 章节，按 chapter_code 前缀（含更深）归属 ----
        String chapterSql =
                "SELECT m.code, m.name, count(d.id) metric_count, "
                        + "count(r.id) FILTER (WHERE r.numerator_val IS NOT NULL OR r.result_val IS NOT NULL) filled_count, "
                        + "count(r.id) FILTER (WHERE r.numerator_val = 0 OR r.denominator_val = 0) zero_count "
                        + "FROM eval_master_row m "
                        + "LEFT JOIN eval_metric_dict d ON d.is_applicable = true "
                        + "  AND (d.chapter_code = m.code OR d.chapter_code LIKE m.code || '.%') "
                        + "LEFT JOIN eval_metric_result r ON r.metric_id = d.id AND r.scope_id = 0 "
                        + "  AND r.is_current = true AND r.stat_period_type = 'YEAR' AND r.stat_period = ? "
                        + "WHERE m.level = 4 "
                        + "GROUP BY m.code, m.name ORDER BY m.code";
        List<Map<String, Object>> chapters = new ArrayList<>();
        jdbcTemplate.query(chapterSql, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapterCode", rs.getString("code"));
            m.put("chapterName", rs.getString("name"));
            m.put("metricCount", rs.getInt("metric_count"));
            m.put("filledCount", rs.getInt("filled_count"));
            m.put("zeroCount", rs.getInt("zero_count"));
            chapters.add(m);
        }, String.valueOf(year));
        resp.put("chapters", chapters);

        // ---- specialties：25.2.3.3. 下第 5 级章节（level=5 且前缀 25.2.3.3.）----
        String specialtySql =
                "SELECT m.code, m.name, count(d.id) metric_count, "
                        + "count(r.id) FILTER (WHERE r.numerator_val IS NOT NULL OR r.result_val IS NOT NULL) filled_count, "
                        + "count(r.id) FILTER (WHERE r.audit_status = 'L3_ARCHIVED') archived_count "
                        + "FROM eval_master_row m "
                        + "LEFT JOIN eval_metric_dict d ON d.is_applicable = true "
                        + "  AND (d.chapter_code = m.code OR d.chapter_code LIKE m.code || '.%') "
                        + "LEFT JOIN eval_metric_result r ON r.metric_id = d.id AND r.scope_id = 0 "
                        + "  AND r.is_current = true AND r.stat_period_type = 'YEAR' AND r.stat_period = ? "
                        + "WHERE m.level = 5 AND m.code LIKE '25.2.3.3.%' "
                        + "GROUP BY m.code, m.name ORDER BY m.code";
        List<Map<String, Object>> specialties = new ArrayList<>();
        jdbcTemplate.query(specialtySql, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapterCode", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("metricCount", rs.getInt("metric_count"));
            m.put("filledCount", rs.getInt("filled_count"));
            m.put("archivedCount", rs.getInt("archived_count"));
            specialties.add(m);
        }, String.valueOf(year));
        resp.put("specialties", specialties);

        // ---- trend：2022~2025 每年 filledCount（全院当期行分子或结果值非空的适用指标数）----
        List<Map<String, Object>> trend = new ArrayList<>();
        for (String y : List.of("2022", "2023", "2024", "2025")) {
            Integer filled = jdbcTemplate.queryForObject(
                    "SELECT count(r.id) FROM eval_metric_dict d "
                            + "LEFT JOIN eval_metric_result r ON r.metric_id = d.id AND r.scope_id = 0 "
                            + "  AND r.is_current = true AND r.stat_period_type = 'YEAR' AND r.stat_period = ? "
                            + "  AND (r.numerator_val IS NOT NULL OR r.result_val IS NOT NULL) "
                            + "WHERE d.is_applicable = true",
                    Integer.class, y);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("year", y);
            t.put("filledCount", filled == null ? 0 : filled);
            trend.add(t);
        }
        resp.put("trend", trend);
        return resp;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
