package com.hospital.eval.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 三级复核签字留痕（01 方案第六章表 8 后半 eval_audit_log，逐列；只追加）。 */
@Data
@TableName("eval_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resultId;

    /** L1_LIAISON / L2_AUDITOR / L3_FUNC / ADMIN */
    private String auditLevel;

    /** SUBMIT / APPROVE / REJECT / UNLOCK */
    private String action;

    private String opinion;

    private Long operatorId;

    private String operatorName;

    private String operatorDept;

    private String operateIp;

    private LocalDateTime operateTime;
}
