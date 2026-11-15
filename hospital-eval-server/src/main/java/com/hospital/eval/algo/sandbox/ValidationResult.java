package com.hospital.eval.algo.sandbox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱校验结果（04 §12.2：{valid, errors, warnings, executableSql}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    private boolean valid;

    private List<String> errors = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();

    private String executableSql;

    public void addError(String msg) {
        this.errors.add(msg);
    }

    public void addWarning(String msg) {
        this.warnings.add(msg);
    }
}
