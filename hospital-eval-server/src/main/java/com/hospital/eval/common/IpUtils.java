package com.hospital.eval.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端 IP：X-Forwarded-For 第一个，否则 remoteAddr（TASK-05 步骤 3）。
 */
public final class IpUtils {

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int idx = xff.indexOf(',');
            return (idx > 0 ? xff.substring(0, idx) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
