package com.hospital.eval.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 跑批子任务（04 §5.2 eval_exec_task，逐列）。 */
@Data
@TableName("eval_exec_task")
public class ExecTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long metricId;

    private Long scopeId;

    private Long sqlId;

    private String sqlRole;

    private String datasourceKey;

    /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED */
    private String status;

    private Integer rowCount;

    private Long elapsedMs;

    private String errorMsg;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
