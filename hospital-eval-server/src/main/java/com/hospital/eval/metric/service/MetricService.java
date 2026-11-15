package com.hospital.eval.metric.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.entity.MetricMasterRow;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.metric.mapper.MetricMasterRowMapper;
import com.hospital.eval.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 指标维护（TASK-08 步骤 1~4）：章节树（10 分钟缓存）、列表、详情、修改留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricService {

    private static final long TREE_CACHE_MS = 10 * 60 * 1000L;
    private static final Set<String> EXTRACT_MODES = Set.of("SYS", "MANUAL", "HYBRID", "PROV");
    private static final Set<String> AGG_MODES = Set.of("DEDUP_DTL", "DEPT_SUM");

    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final MetricMasterRowMapper masterRowMapper;
    private final OperLogService operLogService;

    private volatile long treeCacheAt = 0L;
    private volatile List<Map<String, Object>> treeCache = null;

    // ---------------- 步骤 1：章节树 ----------------

    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> cache = treeCache;
        if (cache != null && System.currentTimeMillis() - treeCacheAt < TREE_CACHE_MS) {
            return cache;
        }
        List<Map<String, Object>> built = buildTree();
        treeCache = built;
        treeCacheAt = System.currentTimeMillis();
        return built;
    }

    public void clearTreeCache() {
        treeCache = null;
    }

    private synchronized List<Map<String, Object>> buildTree() {
        List<MetricMasterRow> chapters = masterRowMapper.selectList(new QueryWrapper<MetricMasterRow>()
                .eq("row_type", "CHAPTER").orderByAsc("row_no"));
        // chapter_code → 该章节数量（精确）；向上累加由树组装完成
        Map<String, Long> direct = new HashMap<>();
        dictMapper.selectList(new QueryWrapper<MetricDict>().select("chapter_code"))
                .forEach(d -> {
                    String cc = d.getChapterCode();
                    if (cc != null) {
                        direct.merge(cc, 1L, Long::sum);
                    }
                });
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, List<String>> childrenOf = new HashMap<>();
        List<String> rootCodes = new ArrayList<>();
        for (MetricMasterRow c : chapters) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", c.getCode());
            node.put("name", c.getName());
            node.put("rowType", "CHAPTER");
            node.put("metricCount", direct.getOrDefault(c.getCode(), 0L));
            node.put("children", new ArrayList<Map<String, Object>>());
            nodes.put(c.getCode(), node);
            childrenOf.computeIfAbsent(c.getParentCode(), k -> new ArrayList<>()).add(c.getCode());
        }
        // 向上累加：自底向上（按 row_no 逆序传播，父章节 row_no 必然小于子章节）
        List<MetricMasterRow> reversed = new ArrayList<>(chapters);
        reversed.sort(Comparator.comparing(MetricMasterRow::getRowNo).reversed());
        for (MetricMasterRow c : reversed) {
            long own = (long) nodes.get(c.getCode()).get("metricCount");
            long childSum = childrenOf.getOrDefault(c.getCode(), List.of()).stream()
                    .mapToLong(code -> (long) nodes.get(code).get("metricCount")).sum();
            nodes.get(c.getCode()).put("metricCount", own + childSum);
        }
        for (MetricMasterRow c : chapters) {
            Map<String, Object> node = nodes.get(c.getCode());
            for (String child : childrenOf.getOrDefault(c.getCode(), List.of())) {
                ((List<Map<String, Object>>) node.get("children")).add(nodes.get(child));
            }
            if (c.getParentCode() == null) {
                rootCodes.add(c.getCode());
            }
        }
        return rootCodes.stream().map(nodes::get).collect(Collectors.toList());
    }

    // ---------------- 步骤 2：列表 ----------------

    public Map<String, Object> list(int page, int size, String chapterCode, String keyword,
                                    String extractMode, Boolean applicable, String specialty) {
        QueryWrapper<MetricDict> qw = new QueryWrapper<>();
        if (chapterCode != null && !chapterCode.isBlank()) {
            String code = chapterCode.trim();
            qw.and(w -> w.eq("chapter_code", code)
                    .or().likeRight("chapter_code", code + ".")
                    .or().likeRight("chapter_code", code + "("));
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            qw.and(w -> w.like("metric_code", k).or().like("alias_code", k).or().like("metric_name", k));
        }
        if (extractMode != null && !extractMode.isBlank()) {
            qw.eq("extract_mode", extractMode.trim());
        }
        if (applicable != null) {
            qw.eq("is_applicable", applicable);
        }
        if (specialty != null && !specialty.isBlank()) {
            qw.eq("specialty_code", specialty.trim());
        }
        qw.orderByAsc("master_row_no");
        Page<MetricDict> p = dictMapper.selectPage(new Page<>(page, size), qw);
        List<Long> ids = p.getRecords().stream().map(MetricDict::getId).toList();
        Map<Long, Long> scopeCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            scopeMapper.selectList(new QueryWrapper<MetricDeptScope>()
                            .select("metric_id").in("metric_id", ids))
                    .forEach(s -> scopeCounts.merge(s.getMetricId(), 1L, Long::sum));
        }
        List<Map<String, Object>> rows = p.getRecords().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("metricCode", d.getMetricCode());
            m.put("aliasCode", d.getAliasCode());
            m.put("metricName", d.getMetricName());
            m.put("metricAttr", d.getMetricAttr());
            m.put("unit", d.getUnit());
            m.put("extractMode", d.getExtractMode());
            m.put("aggMode", d.getAggMode());
            m.put("isApplicable", d.getIsApplicable());
            m.put("scopeCount", scopeCounts.getOrDefault(d.getId(), 0L));
            m.put("numAtomCode", d.getNumAtomCode());
            m.put("denAtomCode", d.getDenAtomCode());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", p.getTotal());
        resp.put("list", rows);
        return resp;
    }

    // ---------------- 步骤 3：详情 ----------------

    public Map<String, Object> detail(long id) {
        MetricDict dict = dictMapper.selectById(id);
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }
        List<MetricMasterRow> atoms = masterRowMapper.selectList(new QueryWrapper<MetricMasterRow>()
                .eq("metric_code", dict.getMetricCode())
                .eq("row_type", "ATOM")
                .orderByAsc("row_no"));
        List<Map<String, Object>> scopes = scopeMapper.selectList(new QueryWrapper<MetricDeptScope>()
                        .eq("metric_id", id).orderByAsc("id"))
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("respDeptName", s.getRespDeptName());
                    m.put("skipFlag", s.getSkipFlag());
                    m.put("extractModeOverride", s.getExtractModeOverride());
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dict", dict);
        resp.put("atoms", atoms);
        resp.put("scopes", scopes);
        return resp;
    }

    // ---------------- 步骤 4：修改（ADMIN / FUNC_LEADER 在 Controller 校验） ----------------

    public void update(long id, com.hospital.eval.metric.dto.MetricDtos.MetricUpdateReq req) {
        MetricDict dict = dictMapper.selectById(id);
        if (dict == null) {
            throw new BizException(400, "指标不存在");
        }
        if (req.extractMode != null && !EXTRACT_MODES.contains(req.extractMode)) {
            throw new BizException(400, "extractMode 必须属于 SYS/MANUAL/HYBRID/PROV");
        }
        if (req.aggMode != null && !AGG_MODES.contains(req.aggMode)) {
            throw new BizException(400, "aggMode 必须属于 DEDUP_DTL/DEPT_SUM");
        }
        Boolean newApplicable = req.isApplicable != null ? req.isApplicable : dict.getIsApplicable();
        String newReason = req.inapplicableReason != null ? req.inapplicableReason : dict.getInapplicableReason();
        if (Boolean.FALSE.equals(newApplicable) && (newReason == null || newReason.isBlank())) {
            throw new BizException(400, "isApplicable=false 时 inapplicableReason 必填");
        }
        // 记录变化字段（只记有变化的）；稀疏更新实体——避免把 JSONB 列按 varchar 回写
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        MetricDict upd = new MetricDict();
        upd.setId(id);
        if (req.hospCaliberDesc != null && !req.hospCaliberDesc.equals(dict.getHospCaliberDesc())) {
            before.put("hospCaliberDesc", dict.getHospCaliberDesc());
            after.put("hospCaliberDesc", req.hospCaliberDesc);
            upd.setHospCaliberDesc(req.hospCaliberDesc);
        }
        if (req.isApplicable != null && !req.isApplicable.equals(dict.getIsApplicable())) {
            before.put("isApplicable", dict.getIsApplicable());
            after.put("isApplicable", req.isApplicable);
            upd.setIsApplicable(req.isApplicable);
        }
        if (req.inapplicableReason != null && !req.inapplicableReason.equals(dict.getInapplicableReason())) {
            before.put("inapplicableReason", dict.getInapplicableReason());
            after.put("inapplicableReason", req.inapplicableReason);
            upd.setInapplicableReason(req.inapplicableReason);
        }
        if (req.extractMode != null && !req.extractMode.equals(dict.getExtractMode())) {
            before.put("extractMode", dict.getExtractMode());
            after.put("extractMode", req.extractMode);
            upd.setExtractMode(req.extractMode);
        }
        if (req.aggMode != null && !req.aggMode.equals(dict.getAggMode())) {
            before.put("aggMode", dict.getAggMode());
            after.put("aggMode", req.aggMode);
            upd.setAggMode(req.aggMode);
        }
        if (req.targetValue != null
                && req.targetValue.compareTo(dict.getTargetValue() == null
                        ? java.math.BigDecimal.ZERO : dict.getTargetValue()) != 0) {
            before.put("targetValue", dict.getTargetValue());
            after.put("targetValue", req.targetValue);
            upd.setTargetValue(req.targetValue);
        }
        if (req.ownerFuncDeptId != null && !req.ownerFuncDeptId.equals(dict.getOwnerFuncDeptId())) {
            before.put("ownerFuncDeptId", dict.getOwnerFuncDeptId());
            after.put("ownerFuncDeptId", req.ownerFuncDeptId);
            upd.setOwnerFuncDeptId(req.ownerFuncDeptId);
        }
        upd.setUpdateTime(java.time.LocalDateTime.now());
        dictMapper.updateById(upd);
        if (!before.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            detail.put("reason", req.changeReason);
            operLogService.log("METRIC", "UPDATE_METRIC", "eval_metric_dict", String.valueOf(id), detail);
        }
        clearTreeCache();
    }

    @SuppressWarnings("unchecked")
    static void clearChildren(List<Map<String, Object>> tree) {
        for (Map<String, Object> node : tree) {
            ((List<Map<String, Object>>) node.get("children")).clear();
        }
    }
}
