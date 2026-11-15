package com.hospital.eval.algo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** SQL 修改历史（01 方案第六章表 4，逐列；只追加不删除）。 */
@Data
@TableName("eval_metric_sql_his")
public class MetricSqlHis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sqlId;

    private Long metricId;

    private String sqlRole;

    private Integer versionNo;

    /** 新增 / 口径升级 / 算法Bug修复 */
    private String changeType;

    private String changeReason;

    private String periodStart;

    private String periodEnd;

    private String oldSqlAnnotated;

    private String newSqlAnnotated;

    private Long modifyUserId;

    private String modifyUserName;

    private LocalDateTime modifyTime;
}
