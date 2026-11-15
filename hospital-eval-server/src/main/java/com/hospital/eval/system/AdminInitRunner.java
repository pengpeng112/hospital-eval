package com.hospital.eval.system;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.system.entity.SysDept;
import com.hospital.eval.system.entity.SysRole;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.entity.SysUserRole;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.hospital.eval.system.mapper.SysRoleMapper;
import com.hospital.eval.system.mapper.SysUserMapper;
import com.hospital.eval.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化 admin（TASK-05 步骤 4）：
 * 不存在 admin 且 eval.init-admin-password 为空 → 终止启动；
 * 否则创建 admin（系统管理员 / 信息科 / ADMIN 角色 / must_change_pwd=false）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitRunner implements ApplicationRunner {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final org.springframework.core.env.Environment env;

    @Override
    public void run(ApplicationArguments args) {
        Long adminCount = sysUserMapper.selectCount(new QueryWrapper<SysUser>().eq("username", "admin"));
        if (adminCount != null && adminCount > 0) {
            log.info("admin 用户已存在，跳过初始化");
            return;
        }
        String initPwd = env.getProperty("eval.init-admin-password", "");
        if (initPwd.isBlank()) {
            throw new IllegalStateException("请在 deploy/.env 配置 EVAL_INIT_ADMIN_PASSWORD");
        }
        SysDept infoDept = sysDeptMapper.selectOne(new QueryWrapper<SysDept>().eq("dept_name", "信息科"));
        if (infoDept == null) {
            infoDept = new SysDept();
            infoDept.setDeptName("信息科");
            infoDept.setDeptType("INFO");
            sysDeptMapper.insert(infoDept);
        }
        SysRole adminRole = sysRoleMapper.selectOne(new QueryWrapper<SysRole>().eq("role_code", "ADMIN"));

        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setRealName("系统管理员");
        admin.setDeptId(infoDept.getId());
        admin.setPasswordHash(passwordEncoder.encode(initPwd));
        admin.setMustChangePwd(false);
        admin.setStatus(1);
        sysUserMapper.insert(admin);

        if (adminRole != null) {
            SysUserRole link = new SysUserRole();
            link.setUserId(admin.getId());
            link.setRoleId(adminRole.getId());
            sysUserRoleMapper.insert(link);
        }
        log.info("admin 用户初始化完成");
    }
}
