package com.hospital.eval.algo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 算法沙箱接口出入参（04 §12.2）。 */
public class SandboxDtos {

    private SandboxDtos() {
    }

    /** POST /api/algo/sql/validate 入参。 */
    @Data
    public static class ValidateReq {
        @NotBlank(message = "sqlText 不能为空")
        public String sqlText;
    }

    /** POST /api/algo/sql/trial 入参（sqlId 供 TASK-09 回写 last_verify_status，本任务忽略）。 */
    @Data
    public static class TrialReq {
        @NotBlank(message = "sqlText 不能为空")
        public String sqlText;

        @NotBlank(message = "datasourceKey 不能为空")
        public String datasourceKey;

        public Long sqlId;
    }
}
