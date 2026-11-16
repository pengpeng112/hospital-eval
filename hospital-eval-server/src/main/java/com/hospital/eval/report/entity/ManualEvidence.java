package com.hospital.eval.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 人工填报佐证材料（01 方案第六章表 8 前半，逐列）。 */
@Data
@TableName("eval_manual_evidence")
public class ManualEvidence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long metricId;

    private Long scopeId;

    /** 如 2025 */
    private String statPeriod;

    /** 佐证分类目录：制度汇编/培训考核/督导检查/原始登记本 */
    private String folderCategory;

    private String fileName;

    /** 相对路径（相对 upload-dir） */
    private String filePath;

    private String fileExt;

    private Long fileSize;

    private String remark;

    private Long uploadUserId;

    private String uploadUserName;

    private LocalDateTime uploadTime;
}
