package com.hospital.eval.report.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.IpUtils;
import com.hospital.eval.common.R;
import com.hospital.eval.report.dto.ReportDtos.AuditIdsReq;
import com.hospital.eval.report.dto.ReportDtos.ArchiveReq;
import com.hospital.eval.report.dto.ReportDtos.UnlockReq;
import com.hospital.eval.report.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import java.util.Map;

/**
 * 三级复核接口（04 §12.7 / §10.1，TASK-11 步骤 6）。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    private long userId() {
        return StpUtil.getLoginIdAsLong();
    }

    private String ip(HttpServletRequest request) {
        return com.hospital.eval.common.IpUtils.getClientIp(request);
    }

    @PostMapping("/submit")
    public R<Map<String, Object>> submit(@Valid @RequestBody AuditIdsReq req,
                                         HttpServletRequest request) {
        return R.ok(auditService.submit(req.getResultIds(), userId(), ip(request)));
    }

    @PostMapping("/approve")
    public R<Map<String, Object>> approve(@Valid @RequestBody AuditIdsReq req,
                                          HttpServletRequest request) {
        return R.ok(auditService.approve(req.getResultIds(), req.getOpinion(), userId(), ip(request)));
    }

    @PostMapping("/reject")
    public R<Map<String, Object>> reject(@Valid @RequestBody AuditIdsReq req,
                                         HttpServletRequest request) {
        return R.ok(auditService.reject(req.getResultIds(), req.getOpinion(), userId(), ip(request)));
    }

    @PostMapping("/archive")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<Map<String, Object>> archive(@Valid @RequestBody ArchiveReq req,
                                          HttpServletRequest request) {
        return R.ok(auditService.archive(req.getMetricId(), req.getYear(), userId(), ip(request)));
    }

    @PostMapping("/unlock")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = cn.dev33.satoken.annotation.SaMode.OR)
    public R<Void> unlock(@Valid @RequestBody UnlockReq req, HttpServletRequest request) {
        auditService.unlock(req.getMetricId(), req.getYear(), req.getOpinion(), userId(), ip(request));
        return R.ok();
    }

    @GetMapping("/logs")
    public R<List<Map<String, Object>>> logs(@RequestParam(required = false) Long resultId,
                                             @RequestParam(required = false) Long metricId,
                                             @RequestParam(required = false) Integer year) {
        return R.ok(auditService.logs(resultId, metricId, year));
    }
}
