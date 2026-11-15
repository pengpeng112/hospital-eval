package com.hospital.eval.system.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（04 §12.1）：SELECT 1 检测 PostgreSQL；库失败时 status=UP、db=DOWN。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public Map<String, String> health() {
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("status", "UP");
        String db;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            db = "UP";
        } catch (Exception e) {
            log.warn("健康检查数据库探测失败: {}", e.getMessage());
            db = "DOWN";
        }
        resp.put("db", db);
        return resp;
    }
}
