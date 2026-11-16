package com.hospital.eval.report.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.engine.service.ResultAggregator;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.result.service.DetailQueryService;
import com.hospital.eval.result.service.DetailQueryService.DetailQuery;
import com.hospital.eval.result.service.DetailQueryService.DetailRows;
import com.hospital.eval.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 混合池（04 §9：有效提取方式 HYBRID、有 DEN SQL、无 NUM SQL）：
 * 筛分子 → 原地更新当期行 → 重算科室行与全院行（TASK-11 步骤 4）。
 */
@Service
@RequiredArgsConstructor
public class HybridService {

    private final DetailQueryService detailQueryService;
    private final MetricDeptScopeMapper scopeMapper;
    private final ResultAggregator aggregator;
    private final OperLogService operLogService;
    private final ScopePermission permission;
    private final com.hospital.eval.system.service.UserService userService;
    private final JdbcTemplate jdbcTemplate;

    /** 与 /api/result/detail?role=DEN 相同结构（复用 TASK-10 查询服务）。 */
    public Map<String, Object> pool(long metricId, long scopeId, int year, String keyword,
                                    int page, int size) {
        requireMixedPool(metricId, scopeId);
        DetailRows rows = detailQueryService.queryPaged(
                new DetailQuery(metricId, scopeId, year, "DEN", null, keyword), page, size);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("columns", rows.columns());
        resp.put("total", rows.total());
        resp.put("list", rows.list());
        return resp;
    }

    @Transactional
    public BigDecimal select(long metricId, long scopeId, int year, List<Long> detailIds,
                             boolean selected, String remark, long userId) {
        requireMixedPool(metricId, scopeId);
        var me = userService.findById(userId);
        if (me == null || !permission.isAdmin(userId) && !permission.isLiaison(userId, scopeId)) {
            throw new BizException(403, "仅本科室联络员或管理员可勾选");
        }
        Long batchId = currentBatchId(metricId, scopeId, year);
        if (batchId == null) {
            throw new BizException(400, "该实例该年没有当期结果行");
        }
        String st = jdbcTemplate.queryForObject(
                "SELECT audit_status FROM eval_metric_result WHERE metric_id = ? AND scope_id = ? "
                        + "AND stat_period_type = 'YEAR' AND stat_period = ? AND is_current = true",
                String.class, metricId, scopeId, String.valueOf(year));
        if (st == null || (!"DRAFT".equals(st) && !"L2_REJECT".equals(st))) {
            throw new BizException(400, "当前状态不允许修改");
        }
        if (Boolean.TRUE.equals(selected) && (remark == null || remark.isBlank())) {
            throw new BizException(400, "selected=true 时 remark 必填");
        }
        // detailIds 必须都属于该实例当期行所在批次的 DEN 明细
        for (Long d : detailIds) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM eval_metric_detail WHERE id = ? AND metric_id = ? "
                            + "AND scope_id = ? AND stat_year = ? AND role_type = 'DEN' AND batch_id = ?",
                    Integer.class, d, metricId, scopeId, year, batchId);
            if (n == null || n == 0) {
                throw new BizException(400, "明细 id=" + d + " 不属于该实例当期 DEN 明细");
            }
        }
        for (Long d : detailIds) {
            jdbcTemplate.update(
                    "UPDATE eval_metric_detail SET manual_selected = ?, select_remark = ? WHERE id = ?",
                    selected, selected ? (remark == null ? "" : remark) : null, d);
        }
        BigDecimal numerator = aggregator.recomputeScopeRow(metricId, scopeId, year);
        aggregator.recomputeHospitalRow(metricId, year);
        operLogService.log("REPORT", "HYBRID_SELECT", "eval_metric_dept_scope", String.valueOf(scopeId),
                Map.of("detailCount", detailIds.size(), "selected", String.valueOf(selected),
                        "numerator", String.valueOf(numerator)));
        return numerator;
    }

    private void requireMixedPool(long metricId, long scopeId) {
        Map<String, Object> info = jdbcTemplate.queryForMap(
                "SELECT COALESCE(s.extract_mode_override, d.extract_mode) AS mode, "
                        + "(SELECT count(*) FROM eval_metric_sql x WHERE x.metric_id = d.id "
                        + "AND x.scope_id = s.id AND x.is_active = true AND x.sql_role LIKE 'NUM%') AS num_sql, "
                        + "(SELECT count(*) FROM eval_metric_sql x WHERE x.metric_id = d.id "
                        + "AND x.scope_id = s.id AND x.is_active = true AND x.sql_role LIKE 'DEN%') AS den_sql "
                        + "FROM eval_metric_dept_scope s JOIN eval_metric_dict d ON d.id = s.metric_id "
                        + "WHERE s.id = ?", scopeId);
        boolean mixed = "HYBRID".equals(info.get("mode"))
                && ((Number) info.get("den_sql")).intValue() > 0
                && ((Number) info.get("num_sql")).intValue() == 0;
        if (!mixed) {
            throw new BizException(400, "该实例不是“系统提分母+人工筛分子”模式");
        }
    }

    private Long currentBatchId(long metricId, long scopeId, int year) {
        return jdbcTemplate.queryForObject(
                "SELECT batch_id FROM eval_metric_result WHERE metric_id = ? AND scope_id = ? "
                        + "AND stat_period_type = 'YEAR' AND stat_period = ? AND is_current = true "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, metricId, scopeId, String.valueOf(year));
    }
}
