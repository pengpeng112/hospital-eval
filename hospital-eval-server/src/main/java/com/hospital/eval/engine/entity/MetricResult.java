package com.hospital.eval.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 指标结果汇总（01 方案第六章表 6，逐列）。 */
@Data
@TableName("eval_metric_result")
public class MetricResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private Long metricId;

    /** 0 = 全院行 */
    private Long scopeId;

    /** YEAR / QUARTER / MONTH */
    private String statPeriodType;

    private String statPeriod;

    private BigDecimal numeratorVal;

    private BigDecimal denominatorVal;

    private BigDecimal resultVal;

    private String qualitativeResult;

    /** JSONB，读取为文本 */
    private String atomValuesJson;

    /** SYS / MANUAL / HYBRID */
    private String dataSourceTag;

    private Integer sqlVersionNum;

    private Integer sqlVersionDen;

    /** DRAFT / L1_SUBMIT / L2_PASS / L2_REJECT / L3_ARCHIVED */
    private String auditStatus;

    private Boolean isCurrent;

    private LocalDateTime updateTime;
}
