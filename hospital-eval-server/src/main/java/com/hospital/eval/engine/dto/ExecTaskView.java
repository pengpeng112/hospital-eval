package com.hospital.eval.engine.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** GET /api/batch/{batchId}/tasks 列表项：eval_exec_task + metricCode + deptName。 */
@Data
public class ExecTaskView {

    private Long id;

    private Long batchId;

    private Long metricId;

    private String metricCode;

    private Long scopeId;

    private String deptName;

    private Long sqlId;

    private String sqlRole;

    private String datasourceKey;

    private String status;

    private Integer rowCount;

    private Long elapsedMs;

    private String errorMsg;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
