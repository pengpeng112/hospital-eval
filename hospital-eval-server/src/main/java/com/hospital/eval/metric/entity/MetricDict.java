package com.hospital.eval.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 评审指标主字典（01 方案第六章表 1，逐列）。 */
@Data
@TableName("eval_metric_dict")
public class MetricDict {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String metricCode;

    private String aliasCode;

    private Integer masterRowNo;

    private String numAtomCode;

    private String denAtomCode;

    /** JSONB，读取为文本 */
    private String extraAtomsJson;

    private String chapterPart;

    private String chapterCode;

    private String chapterPath;

    private String specialtyCode;

    private String metricName;

    private String metricAttr;

    private String metricCategory;

    private String unit;

    private String direction;

    private String extractMode;

    private String aggMode;

    private Boolean isApplicable;

    private String inapplicableReason;

    private String stdFormulaDesc;

    private String hospCaliberDesc;

    private String dataSysSource;

    private Long ownerFuncDeptId;

    private String dedupGranularity;

    private BigDecimal targetValue;

    private Integer sortOrder;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
