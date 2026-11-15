package com.hospital.eval.algo;

import com.hospital.eval.algo.datasource.DatasourceRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 数据源路由器与 Python route_datasource 一致性用例（TASK-09 步骤 7，复用 TASK-01 的 3 个用例）。
 */
class DatasourceRouterTest {

    @Test
    void hisGroupRoutesToHisRo() {
        var r = DatasourceRouter.route("SELECT 1 FROM MEDREC.PAT_VISIT v JOIN COMM.DEPT_DICT d ON 1=1");
        assertEquals("HIS_RO", r.datasourceKey());
        assertNull(r.dsFlag());
        assertEquals("NONE", r.emrSys());
    }

    @Test
    void odsGroupWithMtlJhemr() {
        var r = DatasourceRouter.route(
                "SELECT 1 FROM HIS.PAT_VISIT v JOIN MTL.X m ON 1=1 JOIN JHEMR.Y j ON 1=1");
        assertEquals("ODS_RO", r.datasourceKey());
        assertNull(r.dsFlag());
        assertEquals("MTL+JHEMR", r.emrSys());
    }

    @Test
    void conflictWhenBothGroups() {
        var r = DatasourceRouter.route("SELECT 1 FROM HIS.A a JOIN MEDREC.B b ON 1=1");
        assertEquals("ODS_RO", r.datasourceKey());
        assertEquals("DS_CONFLICT", r.dsFlag());
    }

    @Test
    void unknownWhenNoSchema() {
        var r = DatasourceRouter.route("SELECT 1 FROM dual");
        assertEquals("ODS_RO", r.datasourceKey());
        assertEquals("DS_UNKNOWN", r.dsFlag());
        assertEquals("NONE", r.emrSys());
    }
}
