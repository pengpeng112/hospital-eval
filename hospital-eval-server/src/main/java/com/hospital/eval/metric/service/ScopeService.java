package com.hospital.eval.metric.service;

import com.hospital.eval.common.BizException;
import com.hospital.eval.config.OracleDataSourceConfig.JdbcTemplateSpec;
import com.hospital.eval.config.OracleDataSourceConfig.OraclePools;
import com.hospital.eval.metric.dto.MetricDtos.CreateScopeReq;
import com.hospital.eval.metric.dto.MetricDtos.UpdateScopeReq;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.system.entity.SysDept;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.hospital.eval.system.mapper.SysUserMapper;
import com.hospital.eval.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 科室实例管理（TASK-08 步骤 5~7）：列表联查、新增、修改、归口汇总、
 * Excel 导出/导入、HIS 同步科室编码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeService {

    private static final Pattern ORA_RE = Pattern.compile("ORA-\\d{4,5}");

    private final javax.sql.DataSource dataSource; // PostgreSQL 主数据源（Spring Boot 自动配置）
    private final MetricDeptScopeMapper scopeMapper;
    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final OraclePools pools;
    private final OperLogService operLogService;
    private static final String LIST_SQL_BASE =
            "SELECT s.id, s.metric_id, d.metric_code, d.alias_code, d.metric_name, s.resp_dept_name, "
                    + "s.his_dept_names, u1.real_name AS liaison_name, u2.real_name AS auditor_name, "
                    + "u3.real_name AS func_leader_name, s.extract_mode_override, s.skip_flag, "
                    + "s.skip_reason, s.specialty_name "
                    + "FROM eval_metric_dept_scope s "
                    + "LEFT JOIN eval_metric_dict d ON d.id = s.metric_id "
                    + "LEFT JOIN sys_user u1 ON u1.id = s.liaison_user_id "
                    + "LEFT JOIN sys_user u2 ON u2.id = s.auditor_user_id "
                    + "LEFT JOIN sys_user u3 ON u3.id = s.func_leader_user_id";

    // ---------------- 步骤 5：列表 / 新增 / 修改 / 汇总 ----------------

    public Map<String, Object> list(int page, int size, Long metricId, Long deptId,
                                    Boolean skipFlag, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (metricId != null) {
            where.append(" AND s.metric_id = ?");
            args.add(metricId);
        }
        if (deptId != null) {
            where.append(" AND s.resp_dept_id = ?");
            args.add(deptId);
        }
        if (skipFlag != null) {
            where.append(" AND s.skip_flag = ?");
            args.add(skipFlag);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = "%" + keyword.trim() + "%";
            where.append(" AND (d.metric_code LIKE ? OR d.metric_name LIKE ? OR s.resp_dept_name LIKE ?)");
            args.add(k);
            args.add(k);
            args.add(k);
        }
        JdbcTemplate t = new JdbcTemplate(dataSource);
        Integer total = t.queryForObject(
                "SELECT count(*) FROM eval_metric_dept_scope s "
                        + "LEFT JOIN eval_metric_dict d ON d.id = s.metric_id" + where,
                Integer.class, args.toArray());
        List<Object> params = new ArrayList<>(args);
        params.add(size);
        params.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = t.queryForList(
                LIST_SQL_BASE + where + " ORDER BY s.id LIMIT ? OFFSET ?", params.toArray());
        List<Map<String, Object>> views = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("metricId", r.get("metric_id"));
            m.put("metricCode", r.get("metric_code"));
            m.put("aliasCode", r.get("alias_code"));
            m.put("metricName", r.get("metric_name"));
            m.put("respDeptName", r.get("resp_dept_name"));
            m.put("hisDeptNames", splitNames((String) r.get("his_dept_names")));
            m.put("liaisonName", r.get("liaison_name"));
            m.put("auditorName", r.get("auditor_name"));
            m.put("funcLeaderName", r.get("func_leader_name"));
            m.put("extractModeOverride", r.get("extract_mode_override"));
            m.put("skipFlag", r.get("skip_flag"));
            m.put("skipReason", r.get("skip_reason"));
            m.put("specialtyName", r.get("specialty_name"));
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total == null ? 0 : total);
        resp.put("list", views);
        return resp;
    }

    public long create(CreateScopeReq req) {
        Long exists = scopeMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricDeptScope>()
                .eq("metric_id", req.metricId).eq("resp_dept_id", req.respDeptId));
        if (exists != null && exists > 0) {
            throw new BizException(400, "该指标已存在此科室实例");
        }
        MetricDeptScope s = new MetricDeptScope();
        s.setMetricId(req.metricId);
        s.setRespDeptId(req.respDeptId);
        SysDept dept = deptMapper.selectById(req.respDeptId);
        s.setRespDeptName(dept == null ? null : dept.getDeptName());
        s.setHisDeptNames(joinNames(req.hisDeptNames));
        s.setLiaisonUserId(req.liaisonUserId);
        s.setAuditorUserId(req.auditorUserId);
        s.setFuncLeaderUserId(req.funcLeaderUserId);
        validateExtractMode(req.extractModeOverride);
        s.setExtractModeOverride(req.extractModeOverride);
        s.setSkipFlag(Boolean.TRUE.equals(req.skipFlag));
        s.setSkipReason(req.skipReason);
        s.setSpecialtyName(req.specialtyName);
        scopeMapper.insert(s);
        operLogService.log("SCOPE", "CREATE_SCOPE", "eval_metric_dept_scope", String.valueOf(s.getId()),
                Map.of("metricId", req.metricId, "respDeptId", req.respDeptId));
        return s.getId();
    }

    public void update(long id, UpdateScopeReq req) {
        MetricDeptScope s = scopeMapper.selectById(id);
        if (s == null) {
            throw new BizException(400, "科室实例不存在");
        }
        if (Boolean.TRUE.equals(req.skipFlag) && (req.skipReason == null || req.skipReason.isBlank())) {
            throw new BizException(400, "skipFlag=true 时 skipReason 必填");
        }
        validateExtractMode(req.extractModeOverride);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (req.hisDeptNames != null) {
            String joined = joinNames(req.hisDeptNames);
            if (!joined.equals(s.getHisDeptNames())) {
                before.put("hisDeptNames", s.getHisDeptNames());
                after.put("hisDeptNames", joined);
                s.setHisDeptNames(joined);
            }
        }
        if (req.hisDeptCodes != null && !joinNames(req.hisDeptCodes).equals(s.getHisDeptCodes())) {
            before.put("hisDeptCodes", s.getHisDeptCodes());
            after.put("hisDeptCodes", joinNames(req.hisDeptCodes));
            s.setHisDeptCodes(joinNames(req.hisDeptCodes));
        }
        if (req.liaisonUserId != null && !req.liaisonUserId.equals(s.getLiaisonUserId())) {
            before.put("liaisonUserId", s.getLiaisonUserId());
            after.put("liaisonUserId", req.liaisonUserId);
            s.setLiaisonUserId(req.liaisonUserId);
        }
        if (req.auditorUserId != null && !req.auditorUserId.equals(s.getAuditorUserId())) {
            before.put("auditorUserId", s.getAuditorUserId());
            after.put("auditorUserId", req.auditorUserId);
            s.setAuditorUserId(req.auditorUserId);
        }
        if (req.funcLeaderUserId != null && !req.funcLeaderUserId.equals(s.getFuncLeaderUserId())) {
            before.put("funcLeaderUserId", s.getFuncLeaderUserId());
            after.put("funcLeaderUserId", req.funcLeaderUserId);
            s.setFuncLeaderUserId(req.funcLeaderUserId);
        }
        if (req.extractModeOverride != null && !req.extractModeOverride.equals(s.getExtractModeOverride())) {
            before.put("extractModeOverride", s.getExtractModeOverride());
            after.put("extractModeOverride", req.extractModeOverride);
            s.setExtractModeOverride(req.extractModeOverride);
        }
        if (req.skipFlag != null && !req.skipFlag.equals(s.getSkipFlag())) {
            before.put("skipFlag", s.getSkipFlag());
            after.put("skipFlag", req.skipFlag);
            s.setSkipFlag(req.skipFlag);
        }
        if (req.skipReason != null && !req.skipReason.equals(s.getSkipReason())) {
            before.put("skipReason", s.getSkipReason());
            after.put("skipReason", req.skipReason);
            s.setSkipReason(req.skipReason);
        }
        scopeMapper.updateById(s);
        if (!before.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            operLogService.log("SCOPE", "UPDATE_SCOPE", "eval_metric_dept_scope", String.valueOf(id), detail);
        }
    }

    public List<Map<String, Object>> funcDeptSummary() {
        JdbcTemplate t = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = t.queryForList(
                "SELECT d.owner_func_dept_id AS fid, COUNT(DISTINCT d.id) AS metrics, COUNT(s.id) AS scopes "
                        + "FROM eval_metric_dict d LEFT JOIN eval_metric_dept_scope s ON s.metric_id = d.id "
                        + "GROUP BY d.owner_func_dept_id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Long fid = (Long) r.get("fid");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("funcDeptId", fid);
            if (fid == null) {
                m.put("funcDeptName", "未分配");
            } else {
                SysDept dept = deptMapper.selectById(fid);
                m.put("funcDeptName", dept == null ? ("部门#" + fid) : dept.getDeptName());
            }
            m.put("metricCount", r.get("metrics"));
            m.put("scopeCount", r.get("scopes"));
            out.add(m);
        }
        return out;
    }

    // ---------------- 步骤 6：导出 / 导入 ----------------

    public org.apache.poi.ss.usermodel.Workbook exportWorkbook() {
        org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var sheet = wb.createSheet("科室实例");
        var header = sheet.createRow(0);
        String[] cols = {"实例ID", "指标编号", "专科代号", "指标名称", "责任科室", "科室范围",
                "联络员", "内审员", "终审人", "提取方式覆盖", "是否免统", "免统原因"};
        for (int i = 0; i < cols.length; i++) {
            header.createCell(i).setCellValue(cols[i]);
        }
        int r = 1;
        int page = 1;
        List<Map<String, Object>> batch;
        do {
            Map<String, Object> resp = list(page, 200, null, null, null, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) resp.get("list");
            batch = list;
            for (Map<String, Object> v : list) {
                var row = sheet.createRow(r++);
                Object idVal = v.get("id");
                var idCell = row.createCell(0);
                if (idVal instanceof Number num) {
                    idCell.setCellValue(num.doubleValue());
                } else {
                    idCell.setCellValue(stringOf(idVal));
                }
                row.createCell(1).setCellValue(stringOf(v.get("metricCode")));
                row.createCell(2).setCellValue(stringOf(v.get("aliasCode")));
                row.createCell(3).setCellValue(stringOf(v.get("metricName")));
                row.createCell(4).setCellValue(stringOf(v.get("respDeptName")));
                row.createCell(5).setCellValue(joinNames((List<String>) v.get("hisDeptNames")));
                row.createCell(6).setCellValue(stringOf(v.get("liaisonName")));
                row.createCell(7).setCellValue(stringOf(v.get("auditorName")));
                row.createCell(8).setCellValue(stringOf(v.get("funcLeaderName")));
                row.createCell(9).setCellValue(overrideToChinese((String) v.get("extractModeOverride")));
                boolean skip = Boolean.TRUE.equals(v.get("skipFlag"))
                        || "true".equals(String.valueOf(v.get("skipFlag")));
                row.createCell(10).setCellValue(skip ? "是" : "否");
                row.createCell(11).setCellValue(stringOf(v.get("skipReason")));
            }
            page++;
            if (list.size() < 200) {
                break;
            }
        } while (!batch.isEmpty());
        return wb;
    }

    public record ImportError(int row, String message) {
    }

    public record ImportResult(int updated, List<ImportError> errors) {
    }

    public ImportResult importWorkbook(org.apache.poi.ss.usermodel.Workbook wb) {
        var sheet = wb.getSheetAt(0);
        int updated = 0;
        List<ImportError> errors = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            try {
                var idCell = row.getCell(0);
                if (idCell == null) {
                    continue;
                }
                long id;
                try {
                    id = (long) idCell.getNumericCellValue();
                } catch (Exception cellEx) {
                    String text = stringCell(idCell);
                    if (text.isEmpty()) {
                        continue;
                    }
                    try {
                        id = Long.parseLong(text);
                    } catch (NumberFormatException nfe) {
                        errors.add(new ImportError(r + 1, "实例ID 非法：" + text));
                        continue;
                    }
                }
                MetricDeptScope s = scopeMapper.selectById(id);
                if (s == null) {
                    errors.add(new ImportError(r + 1, "实例不存在：" + id));
                    continue;
                }
                String range = stringCell(row.getCell(5));
                s.setHisDeptNames(range);
                Long liaison = findUserByName(stringCell(row.getCell(6)));
                if (stringCell(row.getCell(6)).isEmpty() && liaison == null) {
                    s.setLiaisonUserId(null);
                } else if (liaison == null) {
                    errors.add(new ImportError(r + 1, "联络员查不到或重名：" + stringCell(row.getCell(6))));
                    continue;
                } else {
                    s.setLiaisonUserId(liaison);
                }
                Long auditor = findUserByName(stringCell(row.getCell(7)));
                if (stringCell(row.getCell(7)).isEmpty() && auditor == null) {
                    s.setAuditorUserId(null);
                } else if (auditor == null) {
                    errors.add(new ImportError(r + 1, "内审员查不到或重名：" + stringCell(row.getCell(7))));
                    continue;
                } else {
                    s.setAuditorUserId(auditor);
                }
                Long leader = findUserByName(stringCell(row.getCell(8)));
                if (stringCell(row.getCell(8)).isEmpty() && leader == null) {
                    s.setFuncLeaderUserId(null);
                } else if (leader == null) {
                    errors.add(new ImportError(r + 1, "终审人查不到或重名：" + stringCell(row.getCell(8))));
                    continue;
                } else {
                    s.setFuncLeaderUserId(leader);
                }
                String overrideZh = stringCell(row.getCell(9));
                s.setExtractModeOverride(overrideFromChinese(overrideZh));
                String skipText = stringCell(row.getCell(10));
                if (!skipText.isEmpty()) {
                    if ("是".equals(skipText)) {
                        s.setSkipFlag(true);
                    } else if ("否".equals(skipText)) {
                        s.setSkipFlag(false);
                    } else {
                        errors.add(new ImportError(r + 1, "是否免统必须为 是/否：" + skipText));
                        continue;
                    }
                }
                s.setSkipReason(stringCell(row.getCell(11)).isEmpty() ? null : stringCell(row.getCell(11)));
                scopeMapper.updateById(s);
                updated++;
            } catch (Exception e) {
                errors.add(new ImportError(r + 1, "行处理异常: " + e.getClass().getSimpleName()));
            }
        }
        operLogService.log("SCOPE", "IMPORT", "eval_metric_dept_scope", null,
                Map.of("updated", updated, "errors", errors.size()));
        return new ImportResult(updated, errors);
    }

    // ---------------- 步骤 7：HIS 同步科室编码 ----------------

    public Map<String, Object> syncDeptCodes() {
        JdbcTemplateSpec spec = pools.templates().get("HIS_RO");
        if (spec == null) {
            throw new BizException(400, "HIS_RO 未配置");
        }
        JdbcTemplate his = new JdbcTemplate(spec.dataSource());
        his.setQueryTimeout(spec.queryTimeoutSeconds());
        Map<String, String> dict;
        try {
            List<Map<String, Object>> rows = his.queryForList(
                    "SELECT DEPT_CODE, DEPT_NAME FROM COMM.DEPT_DICT");
            dict = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.get("DEPT_NAME"));
                String code = String.valueOf(row.get("DEPT_CODE"));
                dict.putIfAbsent(name, code);
            }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            if (root instanceof SQLException se && se.getErrorCode() == 942) {
                throw new BizException(400, "ORA-00942: COMM.DEPT_DICT 视图或表不存在");
            }
            Matcher m = ORA_RE.matcher(root.getMessage() == null ? "" : root.getMessage());
            throw new BizException(400, "HIS 查询失败: " + (m.find() ? m.group() : root.getClass().getSimpleName()));
        }
        List<MetricDeptScope> scopes = scopeMapper.selectList(null);
        Set<String> unmatched = new LinkedHashSet<>();
        int updated = 0;
        for (MetricDeptScope s : scopes) {
            if (s.getHisDeptNames() == null || s.getHisDeptNames().isBlank()) {
                continue;
            }
            Set<String> codes = new LinkedHashSet<>();
            for (String name : s.getHisDeptNames().split(",")) {
                String nameTrim = name.trim();
                if (nameTrim.isEmpty()) {
                    continue;
                }
                String code = dict.get(nameTrim);
                if (code != null) {
                    codes.add(code);
                } else {
                    unmatched.add(nameTrim);
                }
            }
            String joined = String.join(",", codes);
            if (!joined.equals(s.getHisDeptCodes() == null ? "" : s.getHisDeptCodes())) {
                MetricDeptScope upd = new MetricDeptScope();
                upd.setId(s.getId());
                upd.setHisDeptCodes(joined);
                scopeMapper.updateById(upd);
                updated++;
            }
        }
        operLogService.log("SCOPE", "SYNC_DEPT_CODES", "eval_metric_dept_scope", null,
                Map.of("updated", updated, "unmatched", unmatched.size()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("updated", updated);
        resp.put("unmatchedNames", new ArrayList<>(unmatched));
        return resp;
    }

    // ------------------------------------------------------------------

    private void validateExtractMode(String mode) {
        if (mode != null && !mode.isBlank() && !Set.of("SYS", "MANUAL", "HYBRID", "PROV").contains(mode)) {
            throw new BizException(400, "extractModeOverride 必须属于 SYS/MANUAL/HYBRID/PROV 或为空");
        }
    }

    private String joinNames(List<String> names) {
        return names == null ? null : names.stream().map(String::trim).collect(Collectors.joining(","));
    }

    private List<String> splitNames(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(","));
    }

    private String stringOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String stringCell(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> "";
        };
    }

    private String overrideToChinese(String mode) {
        if (mode == null || mode.isBlank()) {
            return "";
        }
        return switch (mode) {
            case "SYS" -> "系统";
            case "MANUAL" -> "人工";
            case "HYBRID" -> "系统+人工";
            case "PROV" -> "省级";
            default -> mode;
        };
    }

    private String overrideFromChinese(String zh) {
        if (zh == null || zh.isBlank()) {
            return null;
        }
        return switch (zh) {
            case "系统" -> "SYS";
            case "人工" -> "MANUAL";
            case "系统+人工" -> "HYBRID";
            case "省级" -> "PROV";
            default -> throw new BizException(400, "提取方式覆盖必须为 系统/人工/系统+人工/省级/空：" + zh);
        };
    }

    /** 按 real_name 查用户：唯一 → id；查不到或多个 → null（调用方记错误行）。 */
    private Long findUserByName(String realName) {
        if (realName == null || realName.isBlank()) {
            return null;
        }
        List<SysUser> users = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("real_name", realName.trim()));
        return users.size() == 1 ? users.get(0).getId() : null;
    }
}
