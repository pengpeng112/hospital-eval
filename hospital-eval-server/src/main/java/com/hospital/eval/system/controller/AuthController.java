package com.hospital.eval.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.R;
import com.hospital.eval.system.dto.ChangePasswordReq;
import com.hospital.eval.system.dto.LoginReq;
import com.hospital.eval.system.dto.UserView;
import com.hospital.eval.system.entity.SysUser;
import com.hospital.eval.system.service.LoginAttemptLimiter;
import com.hospital.eval.system.service.OperLogService;
import com.hospital.eval.system.service.PasswordUtil;
import com.hospital.eval.system.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录鉴权（04 §12.1）：login / logout / me / change-password。
 * 登录失败统一 400"用户名或密码错误"；停用 400"账号已停用"；5 分钟 5 次失败锁 10 分钟；
 * 登录成功更新 last_login_time 并写 AUTH/LOGIN 操作日志。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter limiter;
    private final OperLogService operLogService;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginReq req) {
        if (limiter.isLocked(req.getUsername())) {
            throw new BizException(400, "登录失败次数过多，账号已临时锁定，请 10 分钟后再试");
        }
        SysUser user = userService.findByUsername(req.getUsername());
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            limiter.recordFailure(req.getUsername());
            throw new BizException(400, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(400, "账号已停用");
        }
        limiter.reset(req.getUsername());

        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());

        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLastLoginTime(LocalDateTime.now());
        userService.updateUser(upd);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mustChangePwd", Boolean.TRUE.equals(user.getMustChangePwd()));
        operLogService.log("AUTH", "LOGIN", "sys_user", String.valueOf(user.getId()), detail);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", StpUtil.getTokenValue());
        resp.put("mustChangePwd", Boolean.TRUE.equals(user.getMustChangePwd()));
        resp.put("user", userService.toView(user));
        return R.ok(resp);
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        StpUtil.logout();
        return R.ok();
    }

    @GetMapping("/me")
    public R<UserView> me() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.findById(userId);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        return R.ok(userService.toView(user));
    }

    @PostMapping("/change-password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordReq req) {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.findById(userId);
        if (user == null || !passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(400, "原密码不正确");
        }
        if (!PasswordUtil.isValidNewPassword(req.getNewPassword())) {
            throw new BizException(400, "新密码至少 8 位且必须包含字母和数字");
        }
        SysUser upd = new SysUser();
        upd.setId(userId);
        upd.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        upd.setMustChangePwd(false);
        userService.updateUser(upd);
        operLogService.log("AUTH", "CHANGE_PASSWORD", "sys_user", String.valueOf(userId), null);
        return R.ok();
    }
}
