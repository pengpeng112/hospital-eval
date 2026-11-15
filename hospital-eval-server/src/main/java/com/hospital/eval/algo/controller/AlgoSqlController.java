package com.hospital.eval.algo.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.algo.dto.AlgoDtos.CreateSqlReq;
import com.hospital.eval.algo.dto.AlgoDtos.ToggleReq;
import com.hospital.eval.algo.dto.AlgoDtos.UpdateSqlReq;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.entity.MetricSqlHis;
import com.hospital.eval.algo.service.SqlVersionService;
import com.hospital.eval.common.R;
import com.hospital.eval.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法管理接口（04 §12.5，TASK-09）：查询登录即可；新增/修改/启停仅 ADMIN。
 */
@RestController
@RequestMapping("/api/algo")
@RequiredArgsConstructor
public class AlgoSqlController {

    private final SqlVersionService sqlVersionService;
    private final UserService userService;

    private String operatorName() {
        var user = userService.findById(StpUtil.getLoginIdAsLong());
        return user == null ? "admin" : user.getRealName();
    }

    @GetMapping("/sqls")
    public R<List<Map<String, Object>>> list(@RequestParam Long metricId,
                                             @RequestParam(required = false) Long scopeId) {
        return R.ok(sqlVersionService.listSqls(metricId, scopeId));
    }

    @GetMapping("/sqls/{id}")
    public R<MetricSql> get(@PathVariable long id) {
        return R.ok(sqlVersionService.getSql(id));
    }

    @PostMapping("/sqls")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> create(@Valid @RequestBody CreateSqlReq req) {
        var result = sqlVersionService.create(req, operatorName());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", result.id());
        resp.put("warnings", result.warnings());
        return R.ok(resp);
    }

    @PutMapping("/sqls/{id}")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody UpdateSqlReq req) {
        var result = sqlVersionService.update(id, req, operatorName());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("oldVersionNo", result.oldVersionNo());
        resp.put("versionNo", result.newVersionNo());
        return R.ok(resp);
    }

    @PostMapping("/sqls/{id}/toggle")
    @SaCheckRole("ADMIN")
    public R<Void> toggle(@PathVariable long id, @Valid @RequestBody ToggleReq req) {
        sqlVersionService.toggle(id, req, operatorName());
        return R.ok();
    }

    @GetMapping("/sqls/{id}/history")
    public R<List<Map<String, Object>>> history(@PathVariable long id) {
        return R.ok(sqlVersionService.history(id));
    }

    @GetMapping("/sql-his/{hisId}")
    public R<MetricSqlHis> his(@PathVariable long hisId) {
        return R.ok(sqlVersionService.hisById(hisId));
    }
}
