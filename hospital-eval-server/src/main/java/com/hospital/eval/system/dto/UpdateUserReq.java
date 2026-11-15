package com.hospital.eval.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UpdateUserReq {

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    private Long deptId;

    @NotNull(message = "角色不能为空")
    private List<String> roleCodes;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
