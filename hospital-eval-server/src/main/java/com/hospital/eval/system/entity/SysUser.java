package com.hospital.eval.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户表（04 §5.2 sys_user）。 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String realName;

    private Long deptId;

    private String passwordHash;

    private Boolean mustChangePwd;

    private String phone;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;
}
