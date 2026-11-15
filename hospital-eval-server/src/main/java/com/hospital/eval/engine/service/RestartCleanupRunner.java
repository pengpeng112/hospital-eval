package com.hospital.eval.engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 应用重启清理（TASK-07 步骤 6）：把仍为 RUNNING 的批次与子任务标为 FAILED
 * （error_msg='SERVER_RESTARTED'），避免僵尸状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestartCleanupRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        int tasks = jdbcTemplate.update(
                "UPDATE eval_exec_task SET status = 'FAILED', error_msg = 'SERVER_RESTARTED', "
                        + "end_time = CURRENT_TIMESTAMP WHERE status IN ('PENDING', 'RUNNING')");
        int batches = jdbcTemplate.update(
                "UPDATE eval_exec_batch SET exec_status = 'FAILED', end_time = CURRENT_TIMESTAMP "
                        + "WHERE exec_status = 'RUNNING'");
        if (tasks > 0 || batches > 0) {
            log.info("重启清理：{} 个子任务、{} 个批次标记为 FAILED(SERVER_RESTARTED)", tasks, batches);
        }
    }
}
