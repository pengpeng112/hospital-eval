package com.hospital.eval.result.controller;

import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.R;
import com.hospital.eval.result.service.ResultQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 结果汇总查询（04 §12.6：summary / scopes / panel / batches，登录即可）。
 */
@RestController
@RequestMapping("/api/result")
@RequiredArgsConstructor
public class ResultController {

    private final ResultQueryService resultQueryService;

    @GetMapping("/summary")
    public R<Map<String, Object>> summary(PageQuery pageQuery,
                                          @RequestParam String years,
                                          @RequestParam(required = false) Long batchId,
                                          @RequestParam(required = false) String chapterCode,
                                          @RequestParam(required = false) String specialty,
                                          @RequestParam(required = false) String extractMode,
                                          @RequestParam(required = false) Boolean zeroOnly,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(resultQueryService.summary(pageQuery.getPage(), pageQuery.getSize(),
                years, batchId, chapterCode, specialty, extractMode, zeroOnly, keyword));
    }

    @GetMapping("/metric/{metricId}/scopes")
    public R<Map<String, Object>> scopes(@PathVariable long metricId,
                                         @RequestParam(defaultValue = "2022,2023,2024,2025") String years,
                                         @RequestParam(required = false) Long batchId) {
        return R.ok(resultQueryService.metricScopes(metricId, years, batchId));
    }

    @GetMapping("/metric/{metricId}/panel")
    public R<Map<String, Object>> panel(@PathVariable long metricId) {
        return R.ok(resultQueryService.panel(metricId));
    }

    @GetMapping("/batches")
    public R<Map<String, Object>> batches(PageQuery pageQuery,
                                          @RequestParam(required = false) String triggerType) {
        return R.ok(resultQueryService.batches(pageQuery.getPage(), pageQuery.getSize(), triggerType));
    }
}
