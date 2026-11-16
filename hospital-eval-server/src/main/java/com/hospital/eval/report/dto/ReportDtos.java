package com.hospital.eval.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 人工上报 / 三级复核接口出入参（04 §12.7 / §10.1）。 */
public class ReportDtos {

    private ReportDtos() {
    }

    /** POST /api/report/manual-value 入参。 */
    @Data
    public static class ManualValueReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        @NotNull(message = "scopeId 不能为空")
        public Long scopeId;

        @NotNull(message = "year 不能为空")
        public Integer year;

        public java.math.BigDecimal numeratorVal;

        public java.math.BigDecimal denominatorVal;

        public String qualitativeResult;

        public String remark;
    }

    /** POST /api/report/hybrid-select 入参。 */
    @Data
    public static class HybridSelectReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        @NotNull(message = "scopeId 不能为空")
        public Long scopeId;

        @NotNull(message = "year 不能为空")
        public Integer year;

        @NotEmpty(message = "detailIds 不能为空")
        public List<Long> detailIds;

        public Boolean selected = true;

        public String remark;
    }

    /** POST /api/audit/submit|approve|reject 入参。 */
    @Data
    public static class AuditIdsReq {
        @NotEmpty(message = "resultIds 不能为空")
        public List<Long> resultIds;

        /** reject 时必填（意见 ≥ 5 字）；approve 可选 */
        public String opinion;
    }

    /** POST /api/audit/archive 入参。 */
    @Data
    public static class ArchiveReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        @NotNull(message = "year 不能为空")
        public Integer year;
    }

    /** POST /api/audit/unlock 入参。 */
    @Data
    public static class UnlockReq {
        @NotNull(message = "metricId 不能为空")
        public Long metricId;

        @NotNull(message = "year 不能为空")
        public Integer year;

        @NotBlank(message = "opinion 不能为空")
        public String opinion;
    }
}
