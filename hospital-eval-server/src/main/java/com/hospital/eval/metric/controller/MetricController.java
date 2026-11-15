package com.hospital.eval.metric.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hospital.eval.common.R;
import com.hospital.eval.metric.dto.MetricDtos.MetricUpdateReq;
import com.hospital.eval.metric.service.MetricService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 指标维护接口（04 §12.4，TASK-08 步骤 1~4）。
 */
@RestController
@RequestMapping("/api/metric")
@RequiredArgsConstructor
public class MetricController {

    private final MetricService metricService;

    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        return R.ok(metricService.tree());
    }

    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       String chapterCode, String keyword, String extractMode,
                                       Boolean applicable, String specialty) {
        return R.ok(metricService.list(page, size, chapterCode, keyword, extractMode, applicable, specialty));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable long id) {
        return R.ok(metricService.detail(id));
    }

    @PutMapping("/{id}")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<Void> update(@PathVariable long id, @Valid @RequestBody MetricUpdateReq req) {
        metricService.update(id, req);
        return R.ok();
    }
}
