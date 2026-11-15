package com.hospital.eval.system.dto;

import lombok.Data;

import java.util.List;

/** 用户列表项（04 §12.1 GET /api/system/users）。 */
@Data
public class UserView {

    private Long id;

    private String username;

    private String realName;

    private Long deptId;

    private String deptName;

    private List<String> roles;

    private Integer status;
}
