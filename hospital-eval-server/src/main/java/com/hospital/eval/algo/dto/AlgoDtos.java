package com.hospital.eval.algo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 算法管理接口出入参（04 §12.5）。 */
public class AlgoDtos {

    private AlgoDtos() {
    }

    /** POST /api/algo/sqls 入参。 */
    @Data
    public static class CreateSqlReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        /** 允许 null 表示全院通用 */
        public Long scopeId;

        @NotBlank(message = "sqlRole 不能为空")
        public String sqlRole;

        @NotBlank(message = "datasourceKey 不能为空")
        public String datasourceKey;

        @NotBlank(message = "periodStart 不能为空")
        public String periodStart;

        @NotBlank(message = "periodEnd 不能为空")
        public String periodEnd;

        @NotBlank(message = "sqlAnnotated 不能为空")
        public String sqlAnnotated;

        @NotBlank(message = "changeReason 不能为空")
        public String changeReason;
    }

    /** PUT /api/algo/sqls/{id} 入参（changeType 不允许为「新增」；datasourceKey 可选）。 */
    @Data
    public static class UpdateSqlReq {
        /** 可选，传空则沿用原数据源 */
        public String datasourceKey;

        @NotBlank(message = "periodStart 不能为空")
        public String periodStart;

        @NotBlank(message = "periodEnd 不能为空")
        public String periodEnd;

        @NotBlank(message = "sqlAnnotated 不能为空")
        public String sqlAnnotated;

        @NotBlank(message = "changeType 不能为空")
        public String changeType;

        @NotBlank(message = "changeReason 不能为空")
        public String changeReason;
    }

    /** POST /api/algo/sqls/{id}/toggle 入参。 */
    @Data
    public static class ToggleReq {
        @NotNull(message = "active 不能为空")
        public Boolean active;

        @NotBlank(message = "changeReason 不能为空")
        public String changeReason;
    }
}
