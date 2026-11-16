package com.hospital.eval.report.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.engine.entity.MetricResult;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.mapper.MetricResultMapper;
import com.hospital.eval.engine.service.ResultAggregator;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.report.entity.AuditLog;
import com.hospital.eval.report.mapper.AuditLogMapper;
import com.hospital.eval.report.mapper.ManualEvidenceMapper;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.hospital.eval.system.service.OperLogService;
import com.hospital.eval.system.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三级复核状态机（TASK-11 步骤 6，04 §10.1 逐行实现）。
 * 全部状态变更放在事务中；每行写 eval_audit_log + 一条 sys_oper_log。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final MetricResultMapper resultMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final MetricDictMapper dictMapper;
    private final ManualEvidenceMapper evidenceMapper;
    private final AuditLogMapper auditLogMapper;
    private final SysDeptMapper deptMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ResultAggregator aggregator;
    private final UserService userService;
    private final OperLogService operLogService;

    // ---------------- submit / approve / reject ----------------

    public Map<String, Object> submit(List<Long> resultIds, long userId, String ip) {
        return process(resultIds, userId, ip, "SUBMIT", null);
    }

    public Map<String, Object> approve(List<Long> resultIds, String opinion, long userId, String ip) {
        return process(resultIds, userId, ip, "APPROVE", opinion);
    }

    public Map<String, Object> reject(List<Long> resultIds, String opinion, long userId, String ip) {
        if (opinion == null || opinion.strip().length() < 5) {
            throw new BizException(400, "退回意见至少 5 个字");
        }
        return process(resultIds, userId, ip, "REJECT", opinion);
    }

    private Map<String, Object> process(List<Long> resultIds, long userId, String ip,
                                        String action, String opinion) {
        List<Long> success = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Long resultId : resultIds) {
            MetricResult r = resultMapper.selectById(resultId);
            if (r == null) {
                failed.add(fail(resultId, "结果行不存在"));
                continue;
            }
            if (r.getScopeId() == null || r.getScopeId() == 0) {
                failed.add(fail(resultId, "全院行不能单独提交/审核"));
                continue;
            }
            if (!Boolean.TRUE.equals(r.getIsCurrent())) {
                failed.add(fail(resultId, "只能操作当期行"));
                continue;
            }
            String status = r.getAuditStatus();
            String rejectReason = null;
            boolean ok;
            switch (action) {
                case "SUBMIT" -> {
                    ok = "DRAFT".equals(status) || "L2_REJECT".equals(status);
                    if (!ok) {
                        rejectReason = "当前状态不允许提交";
                        break;
                    }
                    // 纯手工指标须上传佐证
                    if ("MANUAL".equals(effectiveExtractMode(r))) {
                        Integer ev = evidenceCount(r.getMetricId(), r.getScopeId(), r.getStatPeriod());
                        if (ev == null || ev < 1) {
                            ok = false;
                            rejectReason = "纯手工指标须上传原始台账佐证";
                            break;
                        }
                    }
                    // 分子/分母/定性 全空 → 尚未填报
                    if (r.getNumeratorVal() == null && r.getDenominatorVal() == null
                            && (r.getQualitativeResult() == null || r.getQualitativeResult().isBlank())) {
                        ok = false;
                        rejectReason = "尚未填报数据";
                        break;
                    }
                    r.setAuditStatus("L1_SUBMIT");
                }
                case "APPROVE" -> {
                    requireAuditor(r, userId);
                    ok = "L1_SUBMIT".equals(status);
                    if (!ok) {
                        rejectReason = "当前状态不是 L1_SUBMIT";
                        break;
                    }
                    r.setAuditStatus("L2_PASS");
                }
                case "REJECT" -> {
                    requireAuditor(r, userId);
                    ok = "L1_SUBMIT".equals(status)
                            && opinion != null && opinion.strip().length() >= 5;
                    if (!ok) {
                        rejectReason = "当前状态不是 L1_SUBMIT 或意见不足 5 个字";
                        break;
                    }
                    r.setAuditStatus("L2_REJECT");
                }
                default -> {
                    ok = false;
                    rejectReason = "未知动作";
                }
            }
            if (!ok) {
                failed.add(fail(resultId, rejectReason));
                continue;
            }
            r.setUpdateTime(LocalDateTime.now());
            resultMapper.updateById(r);
            writeAuditLog(r, levelOf(action), action, "REJECT".equals(action) ? opinion : null,
                    userId, ip);
            success.add(resultId);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", success);
        resp.put("failed", failed);
        return resp;
    }

    // ---------------- archive / unlock ----------------

    @Transactional
    public Map<String, Object> archive(long metricId, int year, long userId, String ip) {
        boolean funcPerm = hasRole(userId, "FUNC_LEADER") || hasRole(userId, "ADMIN");
        if (!funcPerm) {
            Integer mine = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM eval_metric_dept_scope WHERE metric_id = ? "
                            + "AND func_leader_user_id = ?",
                    Integer.class, metricId, userId);
            if (mine == null || mine == 0) {
                throw new BizException(403, "仅终审人 / FUNC_LEADER / ADMIN 可归档");
            }
        }
        List<String> notPassed = new ArrayList<>();
        List<MetricResult> toArchive = new ArrayList<>();
        List<MetricDeptScope> scopes = scopeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricDeptScope>()
                        .eq("metric_id", metricId).orderByAsc("id"));
        for (MetricDeptScope s : scopes) {
            if (Boolean.TRUE.equals(s.getSkipFlag())) {
                continue; // 免统科室不参与
            }
            MetricResult r = currentResult(metricId, s.getId(), String.valueOf(year));
            if (r == null) {
                notPassed.add(s.getRespDeptName() + "(无数据)");
            } else if (!"L2_PASS".equals(r.getAuditStatus())) {
                notPassed.add(s.getRespDeptName() + "(" + r.getAuditStatus() + ")");
            } else {
                toArchive.add(r);
            }
        }
        if (!notPassed.isEmpty()) {
            throw new BizException(400, "以下科室尚未通过内审：" + String.join("、", notPassed));
        }
        // 全院行若不存在先重算生成（TASK-11 允许的聚合器新增方法）
        MetricResult hospital = currentResult(metricId, 0L, String.valueOf(year));
        if (hospital == null) {
            aggregator.recomputeHospitalRow(metricId, year);
            hospital = currentResult(metricId, 0L, String.valueOf(year));
        }
        if (hospital != null) {
            toArchive.add(hospital);
        }
        for (MetricResult r : toArchive) {
            r.setAuditStatus("L3_ARCHIVED");
            r.setUpdateTime(LocalDateTime.now());
            resultMapper.updateById(r);
            writeAuditLog(r, "L3_FUNC", "ARCHIVE", null, userId, ip);
        }
        operLogService.log("AUDIT", "ARCHIVE", "eval_metric_result", metricId + "/" + year,
                Map.of("archived", toArchive.size()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("archived", toArchive.size());
        return resp;
    }

    @Transactional
    public int unlock(long metricId, int year, String opinion, long userId, String ip) {
        boolean perm = hasRole(userId, "ADMIN") || hasRole(userId, "FUNC_LEADER");
        if (!perm) {
            throw new BizException(403, "仅 ADMIN 或 FUNC_LEADER 可解锁");
        }
        List<MetricResult> rows = resultMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricResult>()
                        .eq("metric_id", metricId)
                        .eq("stat_period_type", "YEAR")
                        .eq("stat_period", String.valueOf(year))
                        .eq("audit_status", "L3_ARCHIVED"));
        String level = hasRole(userId, "ADMIN") ? "ADMIN" : "L3_FUNC";
        for (MetricResult r : rows) {
            r.setAuditStatus("DRAFT");
            r.setUpdateTime(LocalDateTime.now());
            resultMapper.updateById(r);
            writeAuditLog(r, level, "UNLOCK", opinion, userId, ip);
        }
        return rows.size();
    }

    // ---------------- 审计日志查询 ----------------

    public List<Map<String, Object>> logs(Long resultId, Long metricId, Integer year) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql;
        List<Object> args = new ArrayList<>();
        if (resultId != null) {
            sql = "SELECT id, result_id, audit_level, action, opinion, operator_name, operator_dept, "
                    + "operate_time FROM eval_audit_log WHERE result_id = ? ORDER BY operate_time DESC";
            args.add(resultId);
        } else if (metricId != null && year != null) {
            sql = "SELECT a.id, a.result_id, a.audit_level, a.action, a.opinion, a.operator_name, "
                    + "a.operator_dept, a.operate_time FROM eval_audit_log a "
                    + "JOIN eval_metric_result r ON r.id = a.result_id "
                    + "WHERE r.metric_id = ? AND r.stat_period = ? ORDER BY a.operate_time DESC";
            args.add(metricId);
            args.add(String.valueOf(year));
        } else {
            sql = "SELECT id, result_id, audit_level, action, opinion, operator_name, operator_dept, "
                    + "operate_time FROM eval_audit_log ORDER BY operate_time DESC LIMIT 100";
        }
        jdbcTemplate.query(sql, args.toArray(), rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("resultId", rs.getLong("result_id"));
            m.put("auditLevel", rs.getString("audit_level"));
            m.put("action", rs.getString("action"));
            m.put("opinion", rs.getString("opinion"));
            m.put("operatorName", rs.getString("operator_name"));
            m.put("operatorDept", rs.getString("operator_dept"));
            m.put("operateTime", rs.getString("operate_time"));
            out.add(m);
        });
        return out;
    }

    // ------------------------------------------------------------------

    private String levelOf(String action) {
        return switch (action) {
            case "SUBMIT" -> "L1_LIAISON";
            case "APPROVE", "REJECT" -> "L2_AUDITOR";
            case "ARCHIVE" -> "L3_FUNC";
            default -> "ADMIN";
        };
    }

    private MetricResult currentResult(long metricId, long scopeId, String period) {
        List<MetricResult> rows = resultMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricResult>()
                .eq("metric_id", metricId).eq("scope_id", scopeId)
                .eq("stat_period_type", "YEAR").eq("stat_period", period)
                .eq("is_current", true).orderByDesc("id").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> fail(Long resultId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resultId", resultId);
        m.put("reason", reason);
        return m;
    }

    /** 该实例有效提取方式：scope 覆盖优先，否则取指标字典。 */
    private String effectiveExtractMode(MetricResult r) {
        MetricDeptScope s = scopeMapper.selectById(r.getScopeId());
        if (s != null && s.getExtractModeOverride() != null) {
            return s.getExtractModeOverride();
        }
        MetricDict d = dictMapper.selectById(r.getMetricId());
        return d == null ? null : d.getExtractMode();
    }

    private Integer evidenceCount(long metricId, long scopeId, String period) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_manual_evidence WHERE metric_id = ? AND scope_id = ? "
                        + "AND stat_period = ?",
                Integer.class, metricId, scopeId, period);
        return n == null ? 0 : n;
    }

    private void requireAuditor(MetricResult r, long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_dept_scope WHERE id = ? AND auditor_user_id = ?",
                Integer.class, r.getScopeId(), userId);
        if (n == null || n == 0) {
            Integer admin = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id "
                            + "WHERE ur.user_id = ? AND r.role_code = 'ADMIN'",
                    Integer.class, userId);
            if (admin == null || admin == 0) {
                throw new BizException(403, "仅该科室内审员或管理员可复核");
            }
        }
    }

    private boolean hasRole(long userId, String roleCode) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.role_code = ?",
                Integer.class, userId, roleCode);
        return n != null && n > 0;
    }

    private void writeAuditLog(MetricResult r, String level, String action, String opinion,
                               long userId, String ip) {
        var me = userService.findById(userId);
        AuditLog log = new AuditLog();
        log.setResultId(r.getId());
        log.setAuditLevel(level);
        log.setAction(action);
        log.setOpinion(opinion);
        log.setOperatorId(userId);
        log.setOperatorName(me == null ? ("user#" + userId) : me.getRealName());
        log.setOperatorDept(deptNameOf(me == null ? null : me.getDeptId()));
        log.setOperateIp(ip);
        log.setOperateTime(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private String deptNameOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        var d = deptMapper.selectById(deptId);
        return d == null ? null : d.getDeptName();
    }
}
