package com.hospital.eval.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.R;
import com.hospital.eval.system.dto.DeptView;
import com.hospital.eval.system.entity.SysDept;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 科室管理（04 §12.1）。查询登录即可；新增仅 ADMIN（系统配置类操作）。
 */
@RestController
@RequestMapping("/api/system/depts")
@RequiredArgsConstructor
public class DeptController {

    private final SysDeptMapper sysDeptMapper;

    @GetMapping
    public R<List<DeptView>> list(String keyword, String deptType) {
        QueryWrapper<SysDept> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like("dept_name", keyword.trim());
        }
        if (deptType != null && !deptType.isBlank()) {
            qw.eq("dept_type", deptType.trim());
        }
        qw.orderByAsc("id");
        List<DeptView> list = sysDeptMapper.selectList(qw).stream()
                .map(d -> new DeptView(d.getId(), d.getDeptName(), d.getDeptType()))
                .toList();
        return R.ok(list);
    }

    @PostMapping
    @SaCheckRole("ADMIN")
    public R<DeptView> create(@Valid @RequestBody CreateDeptReq req) {
        Long exists = sysDeptMapper.selectCount(new QueryWrapper<SysDept>().eq("dept_name", req.getDeptName()));
        if (exists != null && exists > 0) {
            throw new BizException(400, "科室名称已存在");
        }
        SysDept dept = new SysDept();
        dept.setDeptName(req.getDeptName());
        dept.setDeptType(req.getDeptType());
        sysDeptMapper.insert(dept);
        return R.ok(new DeptView(dept.getId(), dept.getDeptName(), dept.getDeptType()));
    }

    @Data
    public static class CreateDeptReq {
        @NotBlank(message = "科室名称不能为空")
        private String deptName;

        @NotBlank(message = "科室类型不能为空")
        private String deptType;
    }
}
