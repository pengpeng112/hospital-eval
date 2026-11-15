package com.hospital.eval.system;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.system.entity.SysRole;
import com.hospital.eval.system.entity.SysUserRole;
import com.hospital.eval.system.mapper.SysRoleMapper;
import com.hospital.eval.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限数据源（TASK-05 步骤 4）：角色 = 该用户的 role_code 列表；权限点暂为空列表。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("user_id", Long.valueOf(loginId.toString())));
        if (links.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = links.stream().map(SysUserRole::getRoleId).toList();
        return sysRoleMapper.selectList(new QueryWrapper<SysRole>().in("id", roleIds))
                .stream().map(SysRole::getRoleCode).toList();
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }
}
