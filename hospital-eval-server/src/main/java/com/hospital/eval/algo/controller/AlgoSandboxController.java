package com.hospital.eval.algo.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hospital.eval.algo.datasource.OracleQueryService;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.common.BizException;
import com.hospital.eval.algo.dto.SandboxDtos.TrialReq;
import com.hospital.eval.algo.dto.SandboxDtos.ValidateReq;
import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.ValidationResult;
import com.hospital.eval.common.R;
import com.hospital.eval.system.service.OperLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法沙箱与数据源接口（04 §12.2）：
 * datasources / validate 登录即可；ping / trial 仅 ADMIN。
 */
@RestController
@RequestMapping("/api/algo")
@RequiredArgsConstructor
public class AlgoSandboxController {

    private final OracleQueryService oracleQueryService;
    private final ReadOnlySqlValidator validator;
    private final OperLogService operLogService;
    private final MetricSqlMapper sqlMapper;

    @GetMapping("/datasources")
    public R<List<Map<String, Object>>> datasources() {
        return R.ok(oracleQueryService.datasources());
    }

    @PostMapping("/datasources/{key}/ping")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> ping(@PathVariable String key) {
        Map<String, Object> resp = oracleQueryService.ping(key);
        operLogService.log("ALGO", "PING", "datasource", key,
                Map.of("ok", String.valueOf(resp.get("ok")), "message", String.valueOf(resp.get("message"))));
        return R.ok(resp);
    }

    @PostMapping("/sql/validate")
    public R<ValidationResult> validate(@Valid @RequestBody ValidateReq req) {
        return R.ok(validator.validate(req.sqlText));
    }

    @PostMapping("/sql/trial")
    @SaCheckRole("ADMIN")
    public R<Map<String, Object>> trial(@Valid @RequestBody TrialReq req) {
        Map<String, Object> resp;
        try {
            resp = oracleQueryService.trial(req.sqlText, req.datasourceKey);
        } catch (BizException e) {
            // TASK-09 步骤 6：试跑失败回写 last_verify_status=2
            if (req.sqlId != null) {
                markVerifyStatus(req.sqlId, 2);
            }
            throw e;
        }
        // TASK-09 步骤 6：试跑成功回写 last_verify_status=1
        if (req.sqlId != null) {
            markVerifyStatus(req.sqlId, 1);
        }
        String sqlPrefix = req.sqlText.length() > 200 ? req.sqlText.substring(0, 200) : req.sqlText;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("datasourceKey", req.datasourceKey);
        detail.put("sqlPrefix", sqlPrefix);
        detail.put("rowCount", resp.get("rowCount"));
        detail.put("elapsedMs", resp.get("elapsedMs"));
        if (req.sqlId != null) {
            detail.put("sqlId", req.sqlId);
        }
        operLogService.log("ALGO", "TRIAL", "datasource", req.datasourceKey, detail);
        return R.ok(resp);
    }

    private void markVerifyStatus(long sqlId, int status) {
        try {
            var upd = new com.hospital.eval.algo.entity.MetricSql();
            upd.setId(sqlId);
            upd.setLastVerifyStatus(status);
            sqlMapper.updateById(upd);
        } catch (Exception e) {
            // 回写失败不影响试跑结果本身
        }
    }
}
