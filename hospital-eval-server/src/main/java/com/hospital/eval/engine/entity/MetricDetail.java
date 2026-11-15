package com.hospital.eval.engine.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 患者级溯源明细（01 方案第六章表 7 + 04 §5.2 增补列 select_remark，逐列；
 *  复合主键 (id, stat_year)，按 stat_year 分区；写入走 DetailWriter 的 JDBC 批量）。 */
@Data
@TableName("eval_metric_detail")
public class MetricDetail {

    private Long id;

    private Long batchId;

    private Long metricId;

    private Long scopeId;

    private Integer statYear;

    private String statMonth;

    /** NUM / DEN */
    private String roleType;

    private Boolean manualSelected;

    private String patientId;

    private String inpNo;

    private String visitId;

    private String patientName;

    private String admitDeptName;

    private String dischargeDeptName;

    private LocalDateTime admitTime;

    private LocalDateTime dischargeTime;

    private String mainDiagCode;

    private String mainDiagName;

    private String mainOperCode;

    private String mainOperName;

    private String emrSysSource;

    private String emrDocId;

    private String emrDocTitle;

    /** JSONB，读取为文本 */
    private String extJson;

    /** 04 §5.2 增补 */
    private String selectRemark;
}
