package com.hospital.eval.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 一标多科科室责任分解表（01 方案第六章表 2 + 04 §5.2 增补列，逐列）。 */
@Data
@TableName("eval_metric_dept_scope")
public class MetricDeptScope {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long metricId;

    private Long respDeptId;

    private String respDeptName;

    private String hisDeptCodes;

    private String hisDeptNames;

    private Long liaisonUserId;

    private Long auditorUserId;

    private Long funcLeaderUserId;

    private String extractModeOverride;

    private Boolean skipFlag;

    private String skipReason;

    /** 04 §5.2 增补 */
    private String specialtyName;

    private String caliberDesc;

    private String confirmPerson;

    private String sourceRef;

    private String fontColor;
}
