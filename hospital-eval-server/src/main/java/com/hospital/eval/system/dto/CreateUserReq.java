package com.hospital.eval.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateUserReq {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    private Long deptId;

    @NotEmpty(message = "角色不能为空")
    private List<String> roleCodes;
}
