package com.hospital.eval.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 科室表（04 §5.2 sys_dept）。 */
@Data
@TableName("sys_dept")
public class SysDept {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deptName;

    /** CLINICAL 临床医技 / FUNC 职能部门 / INFO 信息科 */
    private String deptType;

    private Long parentId;

    private Integer sortOrder;

    private Integer status;

    private LocalDateTime createTime;
}
