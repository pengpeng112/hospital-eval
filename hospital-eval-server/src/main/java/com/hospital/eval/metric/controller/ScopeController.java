package com.hospital.eval.metric.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hospital.eval.common.R;
import com.hospital.eval.metric.dto.MetricDtos.CreateScopeReq;
import com.hospital.eval.metric.dto.MetricDtos.UpdateScopeReq;
import com.hospital.eval.metric.service.ScopeService;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科室实例管理接口（04 §12.4，TASK-08 步骤 5~7）。
 */
@RestController
@RequestMapping("/api/scope")
@RequiredArgsConstructor
public class ScopeController {

    private final ScopeService scopeService;

    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       Long metricId, Long deptId, Boolean skipFlag, String keyword) {
        return R.ok(scopeService.list(page, size, metricId, deptId, skipFlag, keyword));
    }

    @PostMapping
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> create(@Valid @RequestBody CreateScopeReq req) {
        long id = scopeService.create(req);
        return R.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<Void> update(@PathVariable long id, @Valid @RequestBody UpdateScopeReq req) {
        scopeService.update(id, req);
        return R.ok();
    }

    @GetMapping("/func-dept-summary")
    public R<List<Map<String, Object>>> funcDeptSummary() {
        return R.ok(scopeService.funcDeptSummary());
    }

    @GetMapping("/export")
    public void export(HttpServletResponse response) throws java.io.IOException {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=scopes.xlsx");
        var wb = scopeService.exportWorkbook();
        wb.write(response.getOutputStream());
        wb.close();
    }

    @PostMapping("/import")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<Map<String, Object>> importScopes(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file.getInputStream());
        var result = scopeService.importWorkbook(wb);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("updated", result.updated());
        resp.put("errors", result.errors().stream()
                .map(e -> Map.of("row", e.row(), "message", e.message())).toList());
        return R.ok(resp);
    }

    @PostMapping("/sync-dept-codes")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> syncDeptCodes() {
        return R.ok(scopeService.syncDeptCodes());
    }
}
