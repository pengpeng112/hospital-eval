package com.hospital.eval.algo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 指标 SQL 算法配置（01 方案第六章表 3 + 04 §5.2 增补列，逐列）。 */
@Data
@TableName("eval_metric_sql")
public class MetricSql {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long metricId;

    private Long scopeId;

    /** NUM_DTL / DEN_DTL / NUM_SUM / DEN_SUM */
    private String sqlRole;

    /** ODS_RO / HIS_RO */
    private String datasourceKey;

    private String periodStart;

    private String periodEnd;

    private String timeFieldExpr;

    private String sqlAnnotated;

    private String sqlExecutable;

    private Integer versionNo;

    private Boolean isActive;

    private Integer lastVerifyStatus;

    private String lastModifyUser;

    private String lastModifyReason;

    private LocalDateTime updateTime;

    /** 04 §5.2 增补 */
    private String sourceRef;

    private String execMode;

    private String dsFlag;

    private String periodFlag;

    private String emrSys;
}
