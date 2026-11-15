package com.hospital.eval.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.PageQuery;
import com.hospital.eval.common.PageResult;
import com.hospital.eval.common.R;
import com.hospital.eval.system.dto.CreateUserReq;
import com.hospital.eval.system.dto.UpdateUserReq;
import com.hospital.eval.system.dto.UserView;
import com.hospital.eval.system.entity.SysRole;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.entity.SysUserRole;
import com.hospital.eval.system.mapper.SysRoleMapper;
import com.hospital.eval.system.mapper.SysUserMapper;
import com.hospital.eval.system.mapper.SysUserRoleMapper;
import com.hospital.eval.system.service.OperLogService;
import com.hospital.eval.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理（04 §12.1）：增改与重置密码仅 ADMIN；查询登录即可。
 */
@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final OperLogService operLogService;

    @Value("${eval.init-user-password}")
    private String initUserPassword;

    @GetMapping
    public R<PageResult<UserView>> list(PageQuery pageQuery, String keyword, String roleCode, Long deptId) {
        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("username", keyword.trim()).or().like("real_name", keyword.trim()));
        }
        if (deptId != null) {
            qw.eq("dept_id", deptId);
        }
        if (roleCode != null && !roleCode.isBlank()) {
            SysRole role = sysRoleMapper.selectOne(new QueryWrapper<SysRole>().eq("role_code", roleCode.trim()));
            if (role == null) {
                return R.ok(PageResult.of(0, List.of()));
            }
            List<SysUserRole> links = sysUserRoleMapper.selectList(
                    new QueryWrapper<SysUserRole>().eq("role_id", role.getId()));
            List<Long> userIds = links.stream().map(SysUserRole::getUserId).toList();
            if (userIds.isEmpty()) {
                return R.ok(PageResult.of(0, List.of()));
            }
            qw.in("id", userIds);
        }
        qw.orderByAsc("id");
        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(pageQuery.getPage(), pageQuery.getSize()), qw);
        List<UserView> views = page.getRecords().stream().map(userService::toView).collect(Collectors.toList());
        return R.ok(PageResult.of(page.getTotal(), views));
    }

    @PostMapping
    @SaCheckRole("ADMIN")
    public R<UserView> create(@Valid @RequestBody CreateUserReq req) {
        Long exists = sysUserMapper.selectCount(new QueryWrapper<SysUser>().eq("username", req.getUsername()));
        if (exists != null && exists > 0) {
            throw new BizException(400, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setRealName(req.getRealName());
        user.setDeptId(req.getDeptId());
        user.setPasswordHash(passwordEncoder.encode(initUserPassword));
        user.setMustChangePwd(true);
        user.setStatus(1);
        sysUserMapper.insert(user);
        assignRoles(user.getId(), req.getRoleCodes());
        operLogService.log("AUTH", "CREATE_USER", "sys_user", String.valueOf(user.getId()),
                Map.of("username", req.getUsername(), "roles", req.getRoleCodes()));
        return R.ok(userService.toView(user));
    }

    @PutMapping("/{id}")
    @SaCheckRole("ADMIN")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateUserReq req) {
        SysUser user = userService.findById(id);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setRealName(req.getRealName());
        upd.setDeptId(req.getDeptId());
        upd.setStatus(req.getStatus());
        sysUserMapper.updateById(upd);
        sysUserRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", id));
        assignRoles(id, req.getRoleCodes());
        operLogService.log("AUTH", "UPDATE_USER", "sys_user", String.valueOf(id),
                Map.of("roles", req.getRoleCodes(), "status", req.getStatus()));
        return R.ok();
    }

    @PostMapping("/{id}/reset-password")
    @SaCheckRole("ADMIN")
    public R<Void> resetPassword(@PathVariable Long id) {
        SysUser user = userService.findById(id);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        SysUser upd = new SysUser();
        upd.setId(id);
        upd.setPasswordHash(passwordEncoder.encode(initUserPassword));
        upd.setMustChangePwd(true);
        sysUserMapper.updateById(upd);
        operLogService.log("AUTH", "RESET_PASSWORD", "sys_user", String.valueOf(id), null);
        return R.ok();
    }

    private void assignRoles(Long userId, List<String> roleCodes) {
        for (String code : new ArrayList<>(roleCodes)) {
            SysRole role = sysRoleMapper.selectOne(new QueryWrapper<SysRole>().eq("role_code", code));
            if (role == null) {
                throw new BizException(400, "未知角色：" + code);
            }
            Long exists = sysUserRoleMapper.selectCount(new QueryWrapper<SysUserRole>()
                    .eq("user_id", userId).eq("role_id", role.getId()));
            if (exists == null || exists == 0) {
                SysUserRole link = new SysUserRole();
                link.setUserId(userId);
                link.setRoleId(role.getId());
                sysUserRoleMapper.insert(link);
            }
        }
    }
}
