package com.hospital.eval.algo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.algo.datasource.DatasourceRouter;
import com.hospital.eval.algo.dto.AlgoDtos.CreateSqlReq;
import com.hospital.eval.algo.dto.AlgoDtos.ToggleReq;
import com.hospital.eval.algo.dto.AlgoDtos.UpdateSqlReq;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.entity.MetricSqlHis;
import com.hospital.eval.algo.mapper.MetricSqlHisMapper;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.ValidationResult;
import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 算法管理（TASK-09，04 §12.5）：SQL 查询、新增、修改（版本留痕）、启停、历史。
 * 禁止物理删除；禁止保存未通过沙箱校验的 SQL；sql_executable 一律由后端生成。
 */
@Service
@RequiredArgsConstructor
public class SqlVersionService {

    private static final Pattern PERIOD_RE = Pattern.compile("^(19|20)\\d{2}(0[1-9]|1[0-2])$");
    private static final Set<String> SQL_ROLES = Set.of("NUM_DTL", "DEN_DTL", "NUM_SUM", "DEN_SUM");
    private static final Set<String> DATA_SOURCES = Set.of("HIS_RO", "ODS_RO");
    private static final Set<String> UPDATE_CHANGE_TYPES = Set.of("口径升级", "算法Bug修复");

    private final MetricSqlMapper sqlMapper;
    private final MetricSqlHisMapper hisMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final ReadOnlySqlValidator validator;

    // ---------------- 步骤 1：查询 ----------------

    public List<Map<String, Object>> listSqls(Long metricId, Long scopeId) {
        QueryWrapper<MetricSql> qw = new QueryWrapper<>();
        qw.eq("metric_id", metricId);
        if (scopeId != null) {
            qw.eq("scope_id", scopeId);
        }
        qw.orderByAsc("scope_id", "sql_role", "period_start");
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricSql s : sqlMapper.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("scopeId", s.getScopeId());
            m.put("deptName", deptNameOf(s.getScopeId()));
            m.put("sqlRole", s.getSqlRole());
            m.put("datasourceKey", s.getDatasourceKey());
            m.put("periodStart", s.getPeriodStart());
            m.put("periodEnd", s.getPeriodEnd());
            m.put("versionNo", s.getVersionNo());
            m.put("isActive", s.getIsActive());
            m.put("lastVerifyStatus", s.getLastVerifyStatus());
            m.put("dsFlag", s.getDsFlag());
            m.put("periodFlag", s.getPeriodFlag());
            m.put("emrSys", s.getEmrSys());
            m.put("updateTime", s.getUpdateTime());
            out.add(m);
        }
        return out;
    }

    public MetricSql getSql(long id) {
        MetricSql s = sqlMapper.selectById(id);
        if (s == null) {
            throw new BizException(400, "SQL 不存在");
        }
        return s;
    }

    private String deptNameOf(Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        MetricDeptScope s = scopeMapper.selectById(scopeId);
        return s == null ? null : s.getRespDeptName();
    }

    // ---------------- 步骤 2：新增 ----------------

    public record CreateResult(long id, List<String> warnings) {
    }

    @Transactional
    public CreateResult create(CreateSqlReq req, String operatorName) {
        validateCommon(req.getSqlRole(), req.getDatasourceKey(), req.getPeriodStart(), req.getPeriodEnd());
        ValidationResult vr = validator.validate(req.getSqlAnnotated());
        if (!vr.isValid()) {
            throw new BizException(400, "SQL 校验未通过：" + String.join("；", vr.getErrors()));
        }
        List<String> warnings = new ArrayList<>();
        DatasourceRouter.Route route = DatasourceRouter.route(vr.getExecutableSql());
        if (!route.datasourceKey().equals(req.getDatasourceKey())) {
            warnings.add("SQL 引用的模式名建议使用 " + route.datasourceKey());
        }

        MetricSql s = new MetricSql();
        s.setMetricId(req.getMetricId());
        s.setScopeId(req.getScopeId());
        s.setSqlRole(req.getSqlRole());
        s.setDatasourceKey(req.getDatasourceKey());
        s.setPeriodStart(req.getPeriodStart());
        s.setPeriodEnd(req.getPeriodEnd());
        s.setSqlAnnotated(req.getSqlAnnotated());
        s.setSqlExecutable(vr.getExecutableSql());
        s.setVersionNo(1);
        s.setIsActive(true);
        s.setLastVerifyStatus(0);
        s.setLastModifyUser(operatorName);
        s.setLastModifyReason(req.getChangeReason());
        s.setUpdateTime(LocalDateTime.now());
        s.setExecMode("RAW");
        s.setDsFlag(route.dsFlag());
        s.setPeriodFlag(null);
        s.setEmrSys(route.emrSys());
        sqlMapper.insert(s);

        MetricSqlHis his = new MetricSqlHis();
        his.setSqlId(s.getId());
        his.setMetricId(req.getMetricId());
        his.setSqlRole(req.getSqlRole());
        his.setVersionNo(1);
        his.setChangeType("新增");
        his.setChangeReason(req.getChangeReason());
        his.setPeriodStart(req.getPeriodStart());
        his.setPeriodEnd(req.getPeriodEnd());
        his.setOldSqlAnnotated(null);
        his.setNewSqlAnnotated(req.getSqlAnnotated());
        his.setModifyUserName(operatorName);
        his.setModifyTime(LocalDateTime.now());
        hisMapper.insert(his);
        return new CreateResult(s.getId(), warnings);
    }

    // ---------------- 步骤 3：修改 ----------------

    @Transactional
    public record UpdateResult(int oldVersionNo, int newVersionNo) {
    }

    @Transactional
    public UpdateResult update(long id, UpdateSqlReq req, String operatorName) {
        if ("新增".equals(req.getChangeType()) || !UPDATE_CHANGE_TYPES.contains(req.getChangeType())) {
            throw new BizException(400, "changeType 必须为 口径升级 / 算法Bug修复");
        }
        if (req.getChangeReason().trim().length() < 5) {
            throw new BizException(400, "changeReason 至少 5 个字");
        }
        validatePeriod(req.getPeriodStart(), req.getPeriodEnd());
        MetricSql old = getSql(id);
        String newDs = (req.getDatasourceKey() == null || req.getDatasourceKey().isBlank())
                ? old.getDatasourceKey() : req.getDatasourceKey().trim();
        if (!DATA_SOURCES.contains(newDs)) {
            throw new BizException(400, "datasourceKey 必须为 HIS_RO / ODS_RO");
        }
        boolean sqlSame = old.getSqlAnnotated().equals(req.getSqlAnnotated());
        boolean periodSame = old.getPeriodStart().equals(req.getPeriodStart())
                && old.getPeriodEnd().equals(req.getPeriodEnd());
        boolean dsSame = java.util.Objects.equals(old.getDatasourceKey(), newDs);
        if (sqlSame && periodSame && dsSame) {
            throw new BizException(400, "未检测到修改");
        }
        ValidationResult vr = validator.validate(req.getSqlAnnotated());
        if (!vr.isValid()) {
            throw new BizException(400, "SQL 校验未通过：" + String.join("；", vr.getErrors()));
        }
        DatasourceRouter.Route route = DatasourceRouter.route(vr.getExecutableSql());
        int newVersion = old.getVersionNo() + 1;

        MetricSqlHis his = new MetricSqlHis();
        his.setSqlId(id);
        his.setMetricId(old.getMetricId());
        his.setSqlRole(old.getSqlRole());
        his.setVersionNo(newVersion);
        his.setChangeType(req.getChangeType());
        his.setChangeReason(req.getChangeReason());
        his.setPeriodStart(req.getPeriodStart());
        his.setPeriodEnd(req.getPeriodEnd());
        his.setOldSqlAnnotated(old.getSqlAnnotated());
        his.setNewSqlAnnotated(req.getSqlAnnotated());
        his.setModifyUserName(operatorName);
        his.setModifyTime(LocalDateTime.now());
        hisMapper.insert(his);

        MetricSql upd = new MetricSql();
        upd.setId(id);
        upd.setDatasourceKey(newDs);
        upd.setPeriodStart(req.getPeriodStart());
        upd.setPeriodEnd(req.getPeriodEnd());
        upd.setSqlAnnotated(req.getSqlAnnotated());
        upd.setSqlExecutable(vr.getExecutableSql());
        upd.setVersionNo(newVersion);
        upd.setLastVerifyStatus(0);
        upd.setDsFlag(route.dsFlag());
        upd.setPeriodFlag(null);
        upd.setEmrSys(route.emrSys());
        upd.setLastModifyUser(operatorName);
        upd.setLastModifyReason(req.getChangeReason());
        upd.setUpdateTime(LocalDateTime.now());
        sqlMapper.updateById(upd);
        // updateById 默认忽略 null 值，显式同步可空标记位以清除旧 period_flag/ds_flag
        sqlMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MetricSql>()
                .eq("id", id)
                .set("ds_flag", route.dsFlag())
                .set("period_flag", null)
                .set("emr_sys", route.emrSys()));
        return new UpdateResult(old.getVersionNo(), newVersion);
    }

    // ---------------- 步骤 4：启用/停用 ----------------

    @Transactional
    public void toggle(long id, ToggleReq req, String operatorName) {
        MetricSql old = getSql(id);
        String prefix = Boolean.TRUE.equals(req.getActive()) ? "[启用]" : "[停用]";
        MetricSqlHis his = new MetricSqlHis();
        his.setSqlId(id);
        his.setMetricId(old.getMetricId());
        his.setSqlRole(old.getSqlRole());
        his.setVersionNo(old.getVersionNo());
        his.setChangeType("口径升级");
        his.setChangeReason(prefix + req.getChangeReason());
        his.setPeriodStart(old.getPeriodStart());
        his.setPeriodEnd(old.getPeriodEnd());
        his.setOldSqlAnnotated(old.getSqlAnnotated());
        his.setNewSqlAnnotated(old.getSqlAnnotated());
        his.setModifyUserName(operatorName);
        his.setModifyTime(LocalDateTime.now());
        hisMapper.insert(his);

        MetricSql upd = new MetricSql();
        upd.setId(id);
        upd.setIsActive(req.getActive());
        upd.setLastModifyUser(operatorName);
        upd.setLastModifyReason(prefix + req.getChangeReason());
        upd.setUpdateTime(LocalDateTime.now());
        sqlMapper.updateById(upd);
    }

    // ---------------- 步骤 5：历史 ----------------

    public List<Map<String, Object>> history(long sqlId) {
        getSql(sqlId);
        List<MetricSqlHis> rows = hisMapper.selectList(new QueryWrapper<MetricSqlHis>()
                .eq("sql_id", sqlId)
                .orderByDesc("version_no")
                .orderByDesc("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MetricSqlHis h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hisId", h.getId());
            m.put("versionNo", h.getVersionNo());
            m.put("changeType", h.getChangeType());
            m.put("changeReason", h.getChangeReason());
            m.put("modifyUserName", h.getModifyUserName());
            m.put("modifyTime", h.getModifyTime());
            out.add(m);
        }
        return out;
    }

    public MetricSqlHis hisById(long hisId) {
        MetricSqlHis h = hisMapper.selectById(hisId);
        if (h == null) {
            throw new BizException(400, "历史记录不存在");
        }
        return h;
    }

    // ---------------- 公共校验 ----------------

    private void validateCommon(String sqlRole, String datasourceKey, String periodStart, String periodEnd) {
        if (!SQL_ROLES.contains(sqlRole)) {
            throw new BizException(400, "sqlRole 必须属于 NUM_DTL/DEN_DTL/NUM_SUM/DEN_SUM");
        }
        if (!DATA_SOURCES.contains(datasourceKey)) {
            throw new BizException(400, "datasourceKey 必须为 HIS_RO / ODS_RO");
        }
        validatePeriod(periodStart, periodEnd);
    }

    private void validatePeriod(String periodStart, String periodEnd) {
        if (!PERIOD_RE.matcher(periodStart).matches() || !PERIOD_RE.matcher(periodEnd).matches()) {
            throw new BizException(400, "periodStart/periodEnd 必须为 6 位 YYYYMM");
        }
        if (periodStart.compareTo(periodEnd) > 0) {
            throw new BizException(400, "periodStart 不能晚于 periodEnd");
        }
    }

}
