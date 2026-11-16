package com.hospital.eval.report.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.R;
import com.hospital.eval.report.service.WorkbenchService;
import com.hospital.eval.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作台接口（TASK-11 步骤 7）。
 */
@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchService workbenchService;
    private final UserService userService;

    private boolean funcLeader() {
        long uid = StpUtil.getLoginIdAsLong();
        var roles = userService.roleCodesOf(uid);
        return roles.contains("ADMIN") || roles.contains("FUNC_LEADER");
    }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam int year) {
        return R.ok(workbenchService.overview(year));
    }

    @GetMapping("/todos")
    public R<List<Map<String, Object>>> todos(@RequestParam int year) {
        long uid = StpUtil.getLoginIdAsLong();
        var roles = userService.roleCodesOf(uid);
        boolean admin = roles.contains("ADMIN");
        boolean funcLeader = roles.contains("FUNC_LEADER");
        return R.ok(workbenchService.todos(year, uid, admin, funcLeader));
    }

    @GetMapping("/dept-progress")
    public R<List<Map<String, Object>>> deptProgress(@RequestParam int year) {
        return R.ok(workbenchService.deptProgress(year));
    }

    @GetMapping("/alerts")
    public R<List<Map<String, Object>>> alerts(@RequestParam int year) {
        return R.ok(workbenchService.alerts(year));
    }
}
