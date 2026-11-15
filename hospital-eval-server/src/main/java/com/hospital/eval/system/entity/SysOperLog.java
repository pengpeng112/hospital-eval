package com.hospital.eval.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志（04 §5.2 sys_oper_log；detail_json 读取时按文本返回）。 */
@Data
@TableName("sys_oper_log")
public class SysOperLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** 如 METRIC / ALGO / AUDIT / AUTH */
    private String module;

    /** 如 UPDATE_CALIBER / SAVE_SQL / ARCHIVE / LOGIN */
    private String action;

    private String targetType;

    private String targetId;

    /** JSONB，读取为 JSON 文本 */
    private String detailJson;

    private String ip;

    private LocalDateTime createTime;
}
