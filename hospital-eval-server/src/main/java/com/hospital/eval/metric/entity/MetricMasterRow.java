package com.hospital.eval.metric.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 总表全量行（04 §5.2 eval_master_row，逐列；只读，TASK-08 禁止修改）。 */
@Data
@TableName("eval_master_row")
public class MetricMasterRow {

    /** Excel 原始行号（主键） */
    private Integer rowNo;

    private String code;

    private String name;

    /** CHAPTER / METRIC / ATOM */
    private String rowType;

    private String parentCode;

    private Integer level;

    private String metricCode;

    /** NUM / DEN / SINGLE / EXTRA */
    private String atomRole;

    /** JSONB，读取为文本 */
    private String origValuesJson;
}
