package com.hospital.eval.report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.system.mapper.SysRoleMapper;
import com.hospital.eval.system.mapper.SysUserMapper;
import com.hospital.eval.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 人工上报权限工具（TASK-11 步骤 1，04 §10.1）。
 * isFuncLeader：scope.func_leader_user_id 等于该用户，或用户有 FUNC_LEADER 角色。
 */
@Service
@RequiredArgsConstructor
public class ScopePermission {

    private final MetricDeptScopeMapper scopeMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;

    public MetricDeptScope scopeOf(long scopeId) {
        return scopeMapper.selectById(scopeId);
    }

    public boolean isLiaison(long userId, long scopeId) {
        MetricDeptScope s = scopeMapper.selectById(scopeId);
        return s != null && userId != 0
                && userId == (s.getLiaisonUserId() == null ? -1 : s.getLiaisonUserId());
    }

    public boolean isAuditor(long userId, long scopeId) {
        MetricDeptScope s = scopeMapper.selectById(scopeId);
        return s != null && userId != 0
                && userId == (s.getAuditorUserId() == null ? -1 : s.getAuditorUserId());
    }

    public boolean isFuncLeader(long userId, long metricId) {
        if (hasRole(userId, "FUNC_LEADER")) {
            return true;
        }
        MetricDeptScope s = scopeMapper.selectOne(new QueryWrapper<MetricDeptScope>()
                .eq("metric_id", metricId)
                .eq("func_leader_user_id", userId)
                .last("LIMIT 1"));
        return s != null;
    }

    public boolean isAdmin(long userId) {
        return hasRole(userId, "ADMIN");
    }

    public boolean hasRole(long userId, String roleCode) {
        var role = roleMapper.selectOne(new QueryWrapper<com.hospital.eval.system.entity.SysRole>()
                .eq("role_code", roleCode));
        if (role == null) {
            return false;
        }
        var link = userRoleMapper.selectOne(new QueryWrapper<com.hospital.eval.system.entity.SysUserRole>()
                .eq("user_id", userId)
                .eq("role_id", role.getId())
                .last("LIMIT 1"));
        return link != null;
    }
}
