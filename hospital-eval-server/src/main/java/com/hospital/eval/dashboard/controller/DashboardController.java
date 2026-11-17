package com.hospital.eval.dashboard.controller;

import com.hospital.eval.common.R;
import com.hospital.eval.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 监测大屏汇总接口（TASK-17 步骤 1，04 §12.8）。
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam int year) {
        return R.ok(dashboardService.overview(year));
    }
}
