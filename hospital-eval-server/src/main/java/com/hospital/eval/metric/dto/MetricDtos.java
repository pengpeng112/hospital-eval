package com.hospital.eval.metric.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 指标维护与科室实例接口出入参（04 §12.4）。 */
public class MetricDtos {

    private MetricDtos() {
    }

    /** PUT /api/metric/{id} 入参：changeReason 必填；null 字段不修改。 */
    @Data
    public static class MetricUpdateReq {
        @NotBlank(message = "changeReason 不能为空")
        public String changeReason;

        public String hospCaliberDesc;

        public Boolean isApplicable;

        public String inapplicableReason;

        public String extractMode;

        public String aggMode;

        public java.math.BigDecimal targetValue;

        public Long ownerFuncDeptId;
    }

    /** POST /api/scope 入参。 */
    @Data
    public static class CreateScopeReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        @NotNull(message = "respDeptId 不能为空")
        public Long respDeptId;

        public List<String> hisDeptNames;

        public Long liaisonUserId;

        public Long auditorUserId;

        public Long funcLeaderUserId;

        public String extractModeOverride;

        public Boolean skipFlag;

        public String skipReason;

        public String specialtyName;
    }

    /** PUT /api/scope/{id} 入参（04 §12.4）。 */
    @Data
    public static class UpdateScopeReq {

        public List<String> hisDeptNames;

        public List<String> hisDeptCodes;

        public Long liaisonUserId;

        public Long auditorUserId;

        public Long funcLeaderUserId;

        public String extractModeOverride;

        public Boolean skipFlag;

        public String skipReason;
    }
}
