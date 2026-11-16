package com.hospital.eval.report.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.R;
import com.hospital.eval.report.dto.ReportDtos.HybridSelectReq;
import com.hospital.eval.report.dto.ReportDtos.ManualValueReq;
import com.hospital.eval.report.service.HybridService;
import com.hospital.eval.report.service.ReportService;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 人工上报与混合勾选接口（04 §12.7，TASK-11 步骤 2~4）。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final HybridService hybridService;
    private final UserService userService;

    private long userId() {
        return StpUtil.getLoginIdAsLong();
    }

    private String operatorName() {
        var user = userService.findById(userId());
        return user == null ? null : user.getRealName();
    }

    @GetMapping("/tasks")
    public R<Map<String, Object>> tasks(@RequestParam int year,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        PageQuery pageQuery) {
        return R.ok(reportService.tasks(userId(), year, status, keyword,
                pageQuery.getPage(), pageQuery.getSize()));
    }

    @PostMapping("/manual-value")
    public R<Map<String, Object>> manualValue(@Valid @RequestBody ManualValueReq req) {
        return R.ok(reportService.manualValue(req, userId(), operatorName()));
    }

    @GetMapping("/hybrid-pool")
    public R<Map<String, Object>> hybridPool(@RequestParam long metricId,
                                             @RequestParam long scopeId,
                                             @RequestParam int year,
                                             @RequestParam(required = false) String keyword,
                                             PageQuery pageQuery) {
        return R.ok(hybridService.pool(metricId, scopeId, year, keyword,
                pageQuery.getPage(), pageQuery.getSize()));
    }

    @PostMapping("/hybrid-select")
    public R<Map<String, Object>> hybridSelect(@Valid @RequestBody HybridSelectReq req) {
        java.math.BigDecimal numerator = hybridService.select(req.getMetricId(), req.getScopeId(),
                req.getYear(), req.getDetailIds(),
                !Boolean.FALSE.equals(req.getSelected()), req.getRemark(), userId());
        return R.ok(Map.of("numerator", numerator));
    }
}
