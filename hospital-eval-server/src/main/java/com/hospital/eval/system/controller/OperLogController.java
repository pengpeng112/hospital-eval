package com.hospital.eval.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.PageResult;
import com.hospital.eval.common.R;
import com.hospital.eval.system.entity.SysOperLog;
import com.hospital.eval.system.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询（04 §12.1）：module/username/targetType/targetId 可选过滤，按 create_time 倒序。
 */
@RestController
@RequestMapping("/api/system/oper-logs")
@RequiredArgsConstructor
public class OperLogController {

    private final SysOperLogMapper sysOperLogMapper;

    @GetMapping
    public R<PageResult<SysOperLog>> list(PageQuery pageQuery, String module, String username,
                                          String targetType, String targetId) {
        QueryWrapper<SysOperLog> qw = new QueryWrapper<>();
        if (module != null && !module.isBlank()) {
            qw.eq("module", module.trim());
        }
        if (username != null && !username.isBlank()) {
            qw.like("username", username.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq("target_type", targetType.trim());
        }
        if (targetId != null && !targetId.isBlank()) {
            qw.eq("target_id", targetId.trim());
        }
        qw.orderByDesc("create_time");
        Page<SysOperLog> page = sysOperLogMapper.selectPage(
                new Page<>(pageQuery.getPage(), pageQuery.getSize()), qw);
        return R.ok(PageResult.of(page.getTotal(), page.getRecords()));
    }
}
