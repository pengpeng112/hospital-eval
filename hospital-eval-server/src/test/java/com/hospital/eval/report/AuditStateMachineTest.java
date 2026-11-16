package com.hospital.eval.report;

import com.hospital.eval.common.BizException;
import com.hospital.eval.engine.entity.MetricResult;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.mapper.MetricResultMapper;
import com.hospital.eval.engine.service.ResultAggregator;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.entity.MetricDict;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.metric.mapper.MetricDictMapper;
import com.hospital.eval.report.dto.ReportDtos.ManualValueReq;
import com.hospital.eval.report.mapper.AuditLogMapper;
import com.hospital.eval.report.mapper.ManualEvidenceMapper;
import com.hospital.eval.report.service.AuditService;
import com.hospital.eval.report.service.EvidenceService;
import com.hospital.eval.report.service.ReportService;
import com.hospital.eval.report.service.ScopePermission;
import com.hospital.eval.system.mapper.SysDeptMapper;
import com.hospital.eval.system.service.OperLogService;
import com.hospital.eval.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三级复核状态机 + 人工填报门禁 + 佐证 ticket 单元测试（TASK-11 步骤 8）。
 * 全部依赖 Mockito 打桩，不连数据库。
 */
class AuditStateMachineTest {

    private static final long UID = 9L;

    /** 测试上下文：被测服务 + 可断言的结果行 + 可再打桩的桩件。 */
    private record Ctx(AuditService svc, MetricResult r, MetricResultMapper resultMapper,
                       JdbcTemplate jdbc, MetricDeptScopeMapper scopeMapper, MetricDictMapper dictMapper) {
    }

    private MetricResult row(long id, long scopeId, String status, boolean current) {
        MetricResult r = new MetricResult();
        r.setId(id);
        r.setMetricId(100L);
        r.setScopeId(scopeId);
        r.setStatPeriodType("YEAR");
        r.setStatPeriod("2099");
        r.setAuditStatus(status);
        r.setIsCurrent(current);
        r.setNumeratorVal(BigDecimal.ONE);
        r.setDenominatorVal(BigDecimal.TEN);
        return r;
    }

    private MetricDeptScope scope(long id, boolean skip) {
        MetricDeptScope s = new MetricDeptScope();
        s.setId(id);
        s.setMetricId(100L);
        s.setRespDeptName("甲科");
        s.setSkipFlag(skip);
        return s;
    }

    /** 构造一套以 metricId=100 / scopeId=5 / resultId=1 为锚点的打桩服务。 */
    private Ctx ctx(String extractMode, String status) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MetricResultMapper resultMapper = mock(MetricResultMapper.class);
        MetricDeptScopeMapper scopeMapper = mock(MetricDeptScopeMapper.class);
        MetricDictMapper dictMapper = mock(MetricDictMapper.class);
        MetricResult r = row(1L, 5L, status, true);
        when(resultMapper.selectById(1L)).thenReturn(r);
        when(scopeMapper.selectById(5L)).thenReturn(scope(5L, false));
        when(dictMapper.selectById(100L)).thenReturn(dict(extractMode));
        AuditService svc = new AuditService(resultMapper, scopeMapper, dictMapper,
                mock(ManualEvidenceMapper.class), mock(AuditLogMapper.class),
                mock(SysDeptMapper.class), jdbc, mock(ResultAggregator.class),
                mock(UserService.class), mock(OperLogService.class));
        return new Ctx(svc, r, resultMapper, jdbc, scopeMapper, dictMapper);
    }

    private MetricDict dict(String extractMode) {
        MetricDict d = new MetricDict();
        d.setId(100L);
        d.setExtractMode(extractMode);
        return d;
    }

    // ---------------- 状态机：submit / approve / reject ----------------

    /** 场景 1：DRAFT→SUBMIT 成功（非手工指标，无佐证要求）。 */
    @Test
    void draftSubmitOk() {
        Ctx c = ctx("SQL_AUTO", "DRAFT");
        Map<String, Object> resp = c.svc().submit(List.of(1L), UID, "127.0.0.1");
        assertEquals(List.of(1L), resp.get("success"));
        assertEquals("L1_SUBMIT", c.r().getAuditStatus());
        verify(c.resultMapper()).updateById(c.r());
    }

    /** 场景 2：纯手工指标无附件 → SUBMIT 失败且原因含"佐证"。 */
    @Test
    void manualNoEvidenceSubmitFails() {
        Ctx c = ctx("MANUAL", "DRAFT");
        when(c.jdbc().queryForObject(contains("eval_manual_evidence"), eq(Integer.class),
                any(Object[].class))).thenReturn(0);
        Map<String, Object> resp = c.svc().submit(List.of(1L), UID, "127.0.0.1");
        assertTrue(((List<?>) resp.get("success")).isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) (List<?>) resp.get("failed");
        assertTrue(failed.get(0).get("reason").toString().contains("佐证"));
        verify(c.resultMapper(), never()).updateById(any(MetricResult.class));
    }

    /** 场景 3：L1_SUBMIT→APPROVE 成功（本科室审核员在岗）。 */
    @Test
    void l1ApproveOk() {
        Ctx c = ctx("SQL_AUTO", "L1_SUBMIT");
        when(c.jdbc().queryForObject(contains("auditor_user_id"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);
        Map<String, Object> resp = c.svc().approve(List.of(1L), null, UID, "127.0.0.1");
        assertEquals(List.of(1L), resp.get("success"));
        assertEquals("L2_PASS", c.r().getAuditStatus());
    }

    /** 场景 4：REJECT 意见不足 5 个字 → 直接 400。 */
    @Test
    void rejectShortOpinionThrows() {
        Ctx c = ctx("SQL_AUTO", "L1_SUBMIT");
        BizException ex = assertThrows(BizException.class,
                () -> c.svc().reject(List.of(1L), "abc", UID, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("5"));
    }

    /** 场景 5：L1_SUBMIT→REJECT（合格意见）→ L2_REJECT；再 SUBMIT 成功。 */
    @Test
    void rejectThenResubmitOk() {
        Ctx c = ctx("SQL_AUTO", "L1_SUBMIT");
        when(c.jdbc().queryForObject(contains("auditor_user_id"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);
        Map<String, Object> rej = c.svc().reject(List.of(1L), "冒烟测试退回意见", UID, "127.0.0.1");
        assertEquals(List.of(1L), rej.get("success"));
        assertEquals("L2_REJECT", c.r().getAuditStatus());
        Map<String, Object> resub = c.svc().submit(List.of(1L), UID, "127.0.0.1");
        assertEquals(List.of(1L), resub.get("success"));
        assertEquals("L1_SUBMIT", c.r().getAuditStatus());
    }

    // ---------------- 状态机：archive / unlock ----------------

    /** 场景 7：archive 时有一个科室仍是 L1_SUBMIT → 失败且消息含科室名与状态。 */
    @Test
    void archiveFailsWhenDeptNotPassed() {
        Ctx c = ctx("SQL_AUTO", "L1_SUBMIT");
        when(c.scopeMapper().selectList(any())).thenReturn(List.of(scope(5L, false)));
        when(c.resultMapper().selectList(any())).thenReturn(List.of(row(1L, 5L, "L1_SUBMIT", true)));
        when(c.jdbc().queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        BizException ex = assertThrows(BizException.class,
                () -> c.svc().archive(100L, 2099, UID, "127.0.0.1"));
        assertTrue(ex.getMessage().contains("甲科"));
        assertTrue(ex.getMessage().contains("L1_SUBMIT"));
    }

    /** 场景 8：免统科室不参与 archive（不产生未通过失败）。 */
    @Test
    void archiveSkipsFreeDept() {
        Ctx c = ctx("SQL_AUTO", "DRAFT");
        when(c.scopeMapper().selectList(any())).thenReturn(List.of(scope(5L, true)));
        when(c.resultMapper().selectList(any())).thenReturn(List.of());
        when(c.jdbc().queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        Map<String, Object> resp = c.svc().archive(100L, 2099, UID, "127.0.0.1");
        assertEquals(0, resp.get("archived"));
        verify(c.resultMapper(), never()).updateById(any(MetricResult.class));
    }

    /** 场景 9：L3_ARCHIVED→UNLOCK→DRAFT。 */
    @Test
    void unlockResetsToDraft() {
        Ctx c = ctx("SQL_AUTO", "DRAFT");
        MetricResult r = row(1L, 5L, "L3_ARCHIVED", true);
        when(c.resultMapper().selectList(any())).thenReturn(List.of(r));
        when(c.jdbc().queryForObject(contains("sys_user_role"), eq(Integer.class),
                any(Object[].class))).thenReturn(1);
        int n = c.svc().unlock(100L, 2099, "冒烟解锁", UID, "127.0.0.1");
        assertEquals(1, n);
        assertEquals("DRAFT", r.getAuditStatus());
    }

    // ---------------- 人工填报门禁 ----------------

    /** 场景 6：L2_PASS 状态 manual-value 被拒。 */
    @Test
    void manualValueBlockedWhenL2Pass() {
        MetricResultMapper resultMapper = mock(MetricResultMapper.class);
        ScopePermission permission = mock(ScopePermission.class);
        when(permission.scopeOf(5L)).thenReturn(scope(5L, false));
        when(permission.isAdmin(UID)).thenReturn(true);
        when(resultMapper.selectList(any())).thenReturn(List.of(row(1L, 5L, "L2_PASS", true)));
        ReportService svc = new ReportService(mock(MetricDictMapper.class),
                mock(MetricDeptScopeMapper.class), resultMapper, mock(ExecBatchMapper.class),
                permission, mock(ResultAggregator.class), mock(OperLogService.class),
                mock(ManualEvidenceMapper.class));
        ManualValueReq req = new ManualValueReq();
        req.metricId = 100L;
        req.scopeId = 5L;
        req.year = 2099;
        req.numeratorVal = BigDecimal.ONE;
        req.denominatorVal = BigDecimal.TEN;
        BizException ex = assertThrows(BizException.class,
                () -> svc.manualValue(req, UID, "admin"));
        assertTrue(ex.getMessage().contains("不允许修改"));
        verify(resultMapper, never()).insert(any(MetricResult.class));
    }

    // ---------------- 佐证 ticket ----------------

    private EvidenceService ticketService() throws Exception {
        EvidenceService svc = new EvidenceService(null, null, null, null);
        Field f = EvidenceService.class.getDeclaredField("jwtSecret");
        f.setAccessible(true);
        f.set(svc, "unit-test-secret");
        return svc;
    }

    /** 场景 10：ticket 过期 → 校验失败（403）。 */
    @Test
    void expiredTicketFails() throws Exception {
        EvidenceService svc = ticketService();
        long expired = System.currentTimeMillis() / 1000 - 10;
        String ticket = svc.createTicket(7L, expired);
        BizException ex = assertThrows(BizException.class, () -> svc.verifyTicket(7L, ticket));
        assertTrue(ex.getMessage().contains("过期"));
    }

    /** 场景 11：篡改 ticket 签名 → 校验失败（403）；合法 ticket 通过。 */
    @Test
    void tamperedTicketFailsAndValidPasses() throws Exception {
        EvidenceService svc = ticketService();
        long expire = System.currentTimeMillis() / 1000 + 600;
        String ticket = svc.createTicket(7L, expire);
        svc.verifyTicket(7L, ticket); // 合法不抛
        int dot = ticket.lastIndexOf('.');
        String sig = ticket.substring(0, dot);
        String tampered = ("X".equals(sig.substring(0, 1)) ? "Y" : "X")
                + sig.substring(1) + ticket.substring(dot);
        BizException ex = assertThrows(BizException.class, () -> svc.verifyTicket(7L, tampered));
        assertTrue(ex.getMessage().contains("校验失败"));
    }
}
