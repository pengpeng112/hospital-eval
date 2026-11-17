package com.hospital.eval.dashboard.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.R;
import com.hospital.eval.dashboard.service.ReconcileService;
import com.hospital.eval.system.service.OperLogService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 省平台对账接口（TASK-17 步骤 2，04 §12.8）。导入仅 ADMIN/FUNC_LEADER。
 */
@RestController
@RequestMapping("/api/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;
    private final OperLogService operLogService;

    @GetMapping("/template")
    public void template(HttpServletResponse response) throws IOException {
        byte[] bytes = reconcileService.template();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("省平台对账模板.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        response.getOutputStream().write(bytes);
    }

    @PostMapping("/import")
    @SaCheckRole(value = {"ADMIN", "FUNC_LEADER"}, mode = SaMode.OR)
    public R<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) throws IOException {
        long userId = StpUtil.getLoginIdAsLong();
        return R.ok(reconcileService.importFile(file, userId));
    }

    @GetMapping("/imports")
    public R<List<Map<String, Object>>> imports() {
        return R.ok(reconcileService.imports());
    }

    @GetMapping("/{importId}/rows")
    public R<Map<String, Object>> rows(@PathVariable String importId,
                                       @RequestParam(required = false) Boolean onlyIssue,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return R.ok(reconcileService.rows(importId, onlyIssue, page, size));
    }

    @GetMapping("/{importId}/export")
    public void export(@PathVariable String importId, HttpServletResponse response) throws IOException {
        byte[] bytes = reconcileService.export(importId);
        operLogService.log("RECONCILE", "EXPORT", "eval_prov_reconcile", importId,
                Map.of("bytes", bytes.length));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = URLEncoder.encode("省平台对账差异清单.xlsx", StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        response.getOutputStream().write(bytes);
    }
}
