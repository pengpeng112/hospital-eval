package com.hospital.eval.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.system.dto.UserView;
import com.hospital.eval.system.entity.SysDept;
import com.hospital.eval.system.entity.SysRole;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.entity.SysUserRole;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.hospital.eval.system.mapper.SysRoleMapper;
import com.hospital.eval.system.mapper.SysUserMapper;
import com.hospital.eval.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 用户/角色装配：供 AuthController（me）与 UserController 共用。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    public SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
    }

    public SysUser findById(Long id) {
        return sysUserMapper.selectById(id);
    }

    /** 局部更新：null 字段不参与（MyBatis-Plus updateById 语义）。 */
    public void updateUser(SysUser upd) {
        sysUserMapper.updateById(upd);
    }

    public List<String> roleCodesOf(Long userId) {
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("user_id", userId));
        if (links.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = links.stream().map(SysUserRole::getRoleId).toList();
        return sysRoleMapper.selectList(new QueryWrapper<SysRole>().in("id", roleIds))
                .stream().map(SysRole::getRoleCode).toList();
    }

    public String deptNameOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    public UserView toView(SysUser user) {
        UserView view = new UserView();
        view.setId(user.getId());
        view.setUsername(user.getUsername());
        view.setRealName(user.getRealName());
        view.setDeptId(user.getDeptId());
        view.setDeptName(deptNameOf(user.getDeptId()));
        view.setRoles(roleCodesOf(user.getId()));
        view.setStatus(user.getStatus());
        return view;
    }
}
