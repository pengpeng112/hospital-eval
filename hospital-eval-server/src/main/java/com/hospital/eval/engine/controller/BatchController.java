package com.hospital.eval.engine.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.PageResult;
import com.hospital.eval.common.R;
import com.hospital.eval.engine.dto.BatchRunReq;
import com.hospital.eval.engine.dto.ExecTaskView;
import com.hospital.eval.engine.entity.ExecBatch;
import com.hospital.eval.engine.entity.ExecTask;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.mapper.ExecTaskMapper;
import com.hospital.eval.engine.service.BatchRunner;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.system.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跑批接口（04 §12.3，四个接口仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final BatchRunner batchRunner;
    private final ExecBatchMapper batchMapper;
    private final ExecTaskMapper taskMapper;
    private final MetricDictMapper dictMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final UserService userService;

    @PostMapping("/run")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> run(@Valid @RequestBody BatchRunReq req) {
        if ((req.getMetricIds() == null || req.getMetricIds().isEmpty())
                && (req.getScopeIds() == null || req.getScopeIds().isEmpty())) {
            throw new BizException(400, "必须指定指标或科室实例");
        }
        var user = userService.findById(StpUtil.getLoginIdAsLong());
        String operator = user == null ? "admin" : user.getRealName();
        Long batchId = batchRunner.startBatch(req.getYears(), req.getMetricIds(), req.getScopeIds(),
                req.getVersionTag(), operator);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("batchId", batchId);
        ExecBatch batch = batchMapper.selectById(batchId);
        resp.put("batchNo", batch == null ? null : batch.getBatchNo());
        return R.ok(resp);
    }

    @GetMapping("/{batchId}")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> status(@PathVariable Long batchId) {
        ExecBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException(400, "批次不存在");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("batch", batch);
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "RUNNING", "SUCCESS", "FAILED", "SKIPPED")) {
            stats.put(s, 0);
        }
        taskMapper.selectList(new QueryWrapper<ExecTask>().eq("batch_id", batchId))
                .forEach(t -> stats.merge(t.getStatus(), 1, Integer::sum));
        resp.put("taskStats", stats);
        return R.ok(resp);
    }

    @GetMapping("/{batchId}/tasks")
    @SaCheckRole("ADMIN")
    public R<PageResult<ExecTaskView>> tasks(@PathVariable Long batchId, PageQuery pageQuery, String status) {
        QueryWrapper<ExecTask> qw = new QueryWrapper<>();
        qw.eq("batch_id", batchId);
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.trim());
        }
        qw.orderByAsc("id");
        Page<ExecTask> page = taskMapper.selectPage(new Page<>(pageQuery.getPage(), pageQuery.getSize()), qw);
        List<ExecTaskView> views = page.getRecords().stream().map(this::toView).toList();
        return R.ok(PageResult.of(page.getTotal(), views));
    }

    @GetMapping
    @SaCheckRole("ADMIN")
    public R<PageResult<ExecBatch>> list(PageQuery pageQuery, String triggerType) {
        QueryWrapper<ExecBatch> qw = new QueryWrapper<>();
        if (triggerType != null && !triggerType.isBlank()) {
            qw.eq("trigger_type", triggerType.trim());
        }
        qw.orderByDesc("id");
        Page<ExecBatch> page = batchMapper.selectPage(new Page<>(pageQuery.getPage(), pageQuery.getSize()), qw);
        return R.ok(PageResult.of(page.getTotal(), page.getRecords()));
    }

    private ExecTaskView toView(ExecTask t) {
        ExecTaskView v = new ExecTaskView();
        v.setId(t.getId());
        v.setBatchId(t.getBatchId());
        v.setMetricId(t.getMetricId());
        MetricDict dict = dictMapper.selectById(t.getMetricId());
        v.setMetricCode(dict == null ? null : dict.getMetricCode());
        v.setScopeId(t.getScopeId());
        MetricDeptScope scope = scopeMapper.selectById(t.getScopeId());
        v.setDeptName(scope == null ? null : scope.getRespDeptName());
        v.setSqlId(t.getSqlId());
        v.setSqlRole(t.getSqlRole());
        v.setDatasourceKey(t.getDatasourceKey());
        v.setStatus(t.getStatus());
        v.setRowCount(t.getRowCount());
        v.setElapsedMs(t.getElapsedMs());
        v.setErrorMsg(t.getErrorMsg());
        v.setStartTime(t.getStartTime());
        v.setEndTime(t.getEndTime());
        return v;
    }
}
