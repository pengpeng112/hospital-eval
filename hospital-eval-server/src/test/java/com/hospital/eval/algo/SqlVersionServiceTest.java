package com.hospital.eval.algo;

import com.hospital.eval.algo.dto.AlgoDtos.UpdateSqlReq;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.entity.MetricSqlHis;
import com.hospital.eval.algo.mapper.MetricSqlHisMapper;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.algo.service.SqlVersionService;
import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SQL 版本服务用例（TASK-09 步骤 7，Mockito 模拟 Mapper，不连数据库）。
 */
class SqlVersionServiceTest {

    private MetricSqlMapper sqlMapper;
    private MetricSqlHisMapper hisMapper;
    private SqlVersionService service;
    private MetricSql existing;

    @BeforeEach
    void setUp() {
        sqlMapper = mock(MetricSqlMapper.class);
        hisMapper = mock(MetricSqlHisMapper.class);
        var scopeMapper = mock(MetricDeptScopeMapper.class);
        service = new SqlVersionService(sqlMapper, hisMapper, scopeMapper, new ReadOnlySqlValidator());
        existing = new MetricSql();
        existing.setId(5L);
        existing.setMetricId(100L);
        existing.setScopeId(200L);
        existing.setSqlRole("NUM_SUM");
        existing.setDatasourceKey("ODS_RO");
        existing.setPeriodStart("202501");
        existing.setPeriodEnd("202512");
        existing.setSqlAnnotated("SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL");
        existing.setSqlExecutable("SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL");
        existing.setVersionNo(1);
        existing.setIsActive(true);
        when(sqlMapper.selectById(5L)).thenReturn(existing);
    }

    private UpdateSqlReq req(String annotated, String ps, String pe, String changeType, String reason) {
        UpdateSqlReq r = new UpdateSqlReq();
        r.periodStart = ps;
        r.periodEnd = pe;
        r.sqlAnnotated = annotated;
        r.changeType = changeType;
        r.changeReason = reason;
        return r;
    }

    // 修改后版本号 +1，且 his 保存修改前旧 SQL
    @Test
    void updateBumpsVersionAndSavesOldSql() {
        var out = service.update(5L, req("SELECT 2025 AS 年份, 2 AS 分子 FROM DUAL", "202501", "202512", "算法Bug修复", "分子取值修正为 2"), "信息科管理员");
        assertEquals(1, out.oldVersionNo());
        assertEquals(2, out.newVersionNo());
        var hisCaptor = ArgumentCaptor.forClass(MetricSqlHis.class);
        verify(hisMapper).insert(hisCaptor.capture());
        Assertions.assertEquals("SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL",
                hisCaptor.getValue().getOldSqlAnnotated());
        var sqlCaptor = ArgumentCaptor.forClass(MetricSql.class);
        verify(sqlMapper).updateById(sqlCaptor.capture());
        Assertions.assertEquals(2, sqlCaptor.getValue().getVersionNo());
    }

    // SQL 文本和时段都没变化 → 400 未检测到修改
    @Test
    void updateWithoutChangeThrows() {
        var e = assertThrows(BizException.class, () -> service.update(5L,
                req("SELECT 2025 AS 年份, 1 AS 分子 FROM DUAL", "202501", "202512",
                        "算法Bug修复", "原样重复提交"), "信息科管理员"));
        assertTrue(e.getMessage().contains("未检测到修改"));
    }

    // 修改时 changeType='新增' → 抛异常
    @Test
    void updateRejectsCreateChangeType() {
        var e = assertThrows(BizException.class, () -> service.update(5L,
                req("SELECT 9 AS x FROM DUAL", "202501", "202512", "新增", "修改不允许新增类型"), "信息科管理员"));
        assertTrue(e.getMessage().contains("口径升级 / 算法Bug修复"));
    }

    // changeReason 少于 5 个字 → 抛异常
    @Test
    void updateShortReasonThrows() {
        var e = assertThrows(BizException.class, () -> service.update(5L,
                req("SELECT 2025 AS 年份, 2 AS 分子 FROM DUAL", "202501", "202512",
                        "算法Bug修复", "太短"), "信息科管理员"));
        assertTrue(e.getMessage().contains("至少 5 个字"));
    }

    // 修改时支持切换 datasourceKey 并同步重算 dsFlag / emrSys
    @Test
    void updateCanSwitchDatasourceAndRecomputeFlags() {
        var r = req("SELECT * FROM MEDREC.PAT_MASTER_INDEX", "202501", "202512",
                "口径升级", "纠正误配数据源为 HIS_RO");
        r.datasourceKey = "HIS_RO";
        var out = service.update(5L, r, "信息科管理员");
        assertEquals(2, out.newVersionNo());
        var sqlCaptor = ArgumentCaptor.forClass(MetricSql.class);
        verify(sqlMapper).updateById(sqlCaptor.capture());
        assertEquals("HIS_RO", sqlCaptor.getValue().getDatasourceKey());
        assertEquals(null, sqlCaptor.getValue().getDsFlag());
        assertEquals("NONE", sqlCaptor.getValue().getEmrSys());
    }

}
