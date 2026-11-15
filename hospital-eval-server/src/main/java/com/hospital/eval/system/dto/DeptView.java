package com.hospital.eval.system.dto;

import lombok.Data;

/** 科室列表项（04 §12.1 GET /api/system/depts）。 */
@Data
public class DeptView {

    private Long id;

    private String deptName;

    private String deptType;

    public DeptView() {
    }

    public DeptView(Long id, String deptName, String deptType) {
        this.id = id;
        this.deptName = deptName;
        this.deptType = deptType;
    }
}
