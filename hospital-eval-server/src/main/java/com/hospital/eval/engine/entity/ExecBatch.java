package com.hospital.eval.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 跑批批次（01 方案第六章表 5，逐列）。 */
@Data
@TableName("eval_exec_batch")
public class ExecBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;

    /** CRON / MANUAL_COVER / IMPORT / MANUAL_INPUT */
    private String triggerType;

    private Boolean isCurrentVersion;

    private String versionTag;

    /** RUNNING / SUCCESS / PARTIAL_FAIL / FAILED */
    private String execStatus;

    private Integer totalTasks;

    private Integer successTasks;

    private Integer failTasks;

    private String operatorName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
