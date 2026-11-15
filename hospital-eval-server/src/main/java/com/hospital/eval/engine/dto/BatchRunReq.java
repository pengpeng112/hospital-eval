package com.hospital.eval.engine.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** POST /api/batch/run 入参（04 §12.3；metricIds 与 scopeIds 都空必须拒绝）。 */
@Data
public class BatchRunReq {

    private List<Long> metricIds;

    private List<Long> scopeIds;

    @NotEmpty(message = "years 不能为空")
    private List<Integer> years;

    private String versionTag;
}
