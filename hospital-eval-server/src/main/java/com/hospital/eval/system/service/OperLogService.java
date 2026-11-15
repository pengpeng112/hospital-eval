package com.hospital.eval.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 操作日志服务（TASK-05 步骤 4）：供所有模块调用，自动取当前登录用户与 IP。
 * detail_json 为 JSONB，经 CAST 写入；写日志失败只记警告，不影响主业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperLogService {

    private final JdbcTemplate jdbcTemplate;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;

    public void log(String module, String action, String targetType, String targetId, Map<String, Object> detail) {
        long userId = -1L;
        String username = null;
        try {
            if (StpUtil.isLogin()) {
                userId = StpUtil.getLoginIdAsLong();
                username = (String) StpUtil.getSession().get("username");
            }
        } catch (Exception ignore) {
            // 未登录上下文（如系统任务）允许为空
        }
        String ip;
        try {
            ip = com.hospital.eval.common.IpUtils.getClientIp(request);
        } catch (Exception ignore) {
            // 异步线程无请求上下文（如跑批收尾日志）
            ip = null;
        }
        String detailJson;
        try {
            detailJson = detail == null ? null : objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            detailJson = null;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO sys_oper_log (user_id, username, module, action, target_type, target_id, detail_json, ip) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                    userId, username, module, action, targetType, targetId, detailJson, ip);
        } catch (Exception e) {
            log.warn("sys_oper_log 写入失败: {}", e.getMessage());
        }
    }
}
