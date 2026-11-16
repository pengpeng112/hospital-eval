package com.hospital.eval.report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.common.BizException;
import com.hospital.eval.engine.entity.ExecBatch;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.service.ResultAggregator;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.report.dto.ReportDtos.ManualValueReq;
import com.hospital.eval.report.service.ScopePermission;
import com.hospital.eval.engine.entity.MetricResult;
import com.hospital.eval.engine.mapper.MetricResultMapper;
import com.hospital.eval.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工上报任务列表与人工填报（TASK-11 步骤 2~3，04 §12.7 / §10.1 / §9）。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final String MANUAL_BATCH_NO = "BATCH_MANUAL";

    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final MetricResultMapper resultMapper;
    private final ExecBatchMapper batchMapper;
    private final ScopePermission permission;
    private final ResultAggregator aggregator;
    private final OperLogService operLogService;
    private final com.hospital.eval.report.mapper.ManualEvidenceMapper evidenceMapper;

    // ---------------- 步骤 2：任务列表 ----------------

    public Map<String, Object> tasks(long userId, int year, String status, String keyword,
                                     int page, int size) {
        boolean admin = permission.isAdmin(userId);
        List<MetricDeptScope> scopes = admin
                ? scopeMapper.selectList(new QueryWrapper<MetricDeptScope>()
                        .eq("skip_flag", false).orderByAsc("id"))
                : scopeMapper.selectList(new QueryWrapper<MetricDeptScope>()
                        .eq("skip_flag", false)
                        .and(w -> w.eq("liaison_user_id", userId)
                                .or().eq("auditor_user_id", userId)
                                .or().eq("func_leader_user_id", userId))
                        .orderByAsc("id"));

        // 指标缓存（编号/名称/提取方式）
        Map<Long, MetricDict> dicts = new LinkedHashMap<>();
        for (MetricDeptScope s : scopes) {
            dicts.computeIfAbsent(s.getMetricId(), id -> dictMapper.selectById(id));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MetricDeptScope s : scopes) {
            MetricDict d = dicts.get(s.getMetricId());
            if (d == null) {
                continue;
            }
            if (keyword != null && !keyword.isBlank()
                    && !d.getMetricName().contains(keyword.trim())
                    && !d.getMetricCode().contains(keyword.trim())
                    && (d.getAliasCode() == null || !d.getAliasCode().contains(keyword.trim()))) {
                continue;
            }
            MetricResult r = currentResult(s.getMetricId(), s.getId(), String.valueOf(year));
            String auditStatus = r == null ? "TODO"
                    : ("L2_REJECT".equals(r.getAuditStatus()) ? "L2_REJECT"
                    : ("DRAFT".equals(r.getAuditStatus()) ? "DRAFT" : r.getAuditStatus()));
            String statusKey = r == null || "DRAFT".equals(auditStatus) ? "TODO" : auditStatus;
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)
                    && !statusKey.equalsIgnoreCase(status.trim())) {
                continue;
            }
            Long evCount = evidenceMapper.selectCount(new QueryWrapper<com.hospital.eval.report.entity.ManualEvidence>()
                    .eq("metric_id", s.getMetricId())
                    .eq("scope_id", s.getId())
                    .eq("stat_period", String.valueOf(year)));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scopeId", s.getId());
            m.put("metricId", s.getMetricId());
            m.put("metricCode", d.getMetricCode());
            m.put("metricName", d.getMetricName());
            m.put("deptName", s.getRespDeptName());
            m.put("extractMode", s.getExtractModeOverride() != null
                    ? s.getExtractModeOverride() : d.getExtractMode());
            m.put("resultId", r == null ? null : r.getId());
            m.put("num", r == null ? null : r.getNumeratorVal());
            m.put("den", r == null ? null : r.getDenominatorVal());
            m.put("val", r == null ? null : r.getResultVal());
            m.put("auditStatus", auditStatus);
            m.put("evidenceCount", evCount == null ? 0 : evCount);
            m.put("myRole", myRole(userId, s));
            rows.add(m);
        }
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", rows.size());
        resp.put("list", from >= rows.size() ? List.of() : rows.subList(from, to));
        return resp;
    }

    private String myRole(long userId, MetricDeptScope s) {
        if (userId == (s.getLiaisonUserId() == null ? -1 : s.getLiaisonUserId())) {
            return "LIAISON";
        }
        if (userId == (s.getAuditorUserId() == null ? -1 : s.getAuditorUserId())) {
            return "AUDITOR";
        }
        if (userId == (s.getFuncLeaderUserId() == null ? -1 : s.getFuncLeaderUserId())) {
            return "FUNC_LEADER";
        }
        return "LIAISON";
    }

    // ---------------- 步骤 3：人工填报 ----------------

    @Transactional
    public Map<String, Object> manualValue(ManualValueReq req, long userId, String operatorName) {
        MetricDeptScope scope = permission.scopeOf(req.getScopeId());
        if (scope == null) {
            throw new BizException(400, "科室实例不存在");
        }
        boolean admin = permission.isAdmin(userId);
        if (!admin && !permission.isLiaison(userId, req.getScopeId())) {
            throw new BizException(403, "仅本科室联络员或管理员可填报");
        }
        MetricResult current = currentResult(req.getMetricId(), req.getScopeId(), String.valueOf(req.getYear()));
        if (current != null) {
            String st = current.getAuditStatus();
            if (!"DRAFT".equals(st) && !"L2_REJECT".equals(st)) {
                throw new BizException(400, "当前状态不允许修改");
            }
        }
        MetricDict dict = dictMapper.selectById(req.getMetricId());
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }

        long batchId = manualBatchId();
        MetricResult old = current;
        MetricResult row = new MetricResult();
        row.setBatchId(batchId);
        row.setMetricId(req.getMetricId());
        row.setScopeId(req.getScopeId());
        row.setStatPeriodType("YEAR");
        row.setStatPeriod(String.valueOf(req.getYear()));
        row.setNumeratorVal(req.numeratorVal);
        row.setDenominatorVal(req.denominatorVal);
        row.setQualitativeResult(req.qualitativeResult);
        row.setResultVal(computeResultVal(req.numeratorVal, req.denominatorVal, dict.getUnit()));
        row.setDataSourceTag("MANUAL");
        row.setAuditStatus(old != null && "L2_REJECT".equals(old.getAuditStatus())
                ? "L2_REJECT" : "DRAFT");
        row.setIsCurrent(true);
        row.setUpdateTime(LocalDateTime.now());
        resultMapper.insert(row);

        if (old != null) {
            old.setIsCurrent(false);
            old.setUpdateTime(LocalDateTime.now());
            resultMapper.updateById(old);
        }
        aggregator.recomputeHospitalRow(req.getMetricId(), req.getYear());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", old == null ? null
                : Map.of("num", old.getNumeratorVal(), "den", old.getDenominatorVal()));
        detail.put("after", Map.of("num", req.numeratorVal, "den", req.denominatorVal));
        if (req.remark != null) {
            detail.put("remark", req.remark);
        }
        operLogService.log("REPORT", "MANUAL_VALUE", "eval_metric_result",
                String.valueOf(row.getId()), detail);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("resultId", row.getId());
        return resp;
    }

    private long manualBatchId() {
        ExecBatch b = batchMapper.selectOne(new QueryWrapper<ExecBatch>()
                .eq("batch_no", "BATCH_MANUAL").last("LIMIT 1"));
        if (b != null) {
            return b.getId();
        }
        ExecBatch nb = new ExecBatch();
        nb.setBatchNo("BATCH_MANUAL");
        nb.setTriggerType("MANUAL_INPUT");
        nb.setIsCurrentVersion(true);
        nb.setExecStatus("SUCCESS");
        nb.setStartTime(LocalDateTime.now());
        nb.setEndTime(LocalDateTime.now());
        batchMapper.insert(nb);
        return nb.getId();
    }

    private MetricResult currentResult(long metricId, long scopeId, String period) {
        List<MetricResult> rows = resultMapper.selectList(new QueryWrapper<MetricResult>()
                .eq("metric_id", metricId).eq("scope_id", scopeId)
                .eq("stat_period_type", "YEAR").eq("stat_period", period)
                .eq("is_current", true).orderByDesc("id").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 04 §9 第 3 条：den>0 → num/den（%类 ×100，4 位）；den 为 0/NULL → null。 */
    private BigDecimal computeResultVal(BigDecimal num, BigDecimal den, String unit) {
        if (den == null || den.compareTo(BigDecimal.ZERO) == 0 || num == null) {
            return null;
        }
        double v = num.doubleValue() / den.doubleValue();
        if (unit != null && (unit.contains("%") || unit.contains("百分"))) {
            v = v * 100;
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
