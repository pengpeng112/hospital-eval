package com.hospital.eval.engine.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.algo.entity.MetricSql;
import com.hospital.eval.algo.mapper.MetricSqlMapper;
import com.hospital.eval.engine.entity.ExecBatch;
import com.hospital.eval.engine.entity.ExecTask;
import com.hospital.eval.engine.mapper.ExecBatchMapper;
import com.hospital.eval.engine.mapper.ExecTaskMapper;
import com.hospital.eval.engine.service.BatchPlanner.PlannedTask;
import com.hospital.eval.engine.service.ResultAggregator.TaskOutcome;
import com.hospital.eval.system.service.OperLogService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跑批调度（TASK-07 步骤 2/6）：批次创建落库 → 异步执行（3 线程，最多 3 条 SQL
 * 同时在 Oracle 上跑）→ 全部结束后聚合 → 更新批次统计与终态。
 */
@Slf4j
@Service
public class BatchRunner {

    private final AtomicInteger threadSeq = new AtomicInteger();

    private final BatchPlanner planner;
    private final SqlExecutor sqlExecutor;
    private final ResultAggregator aggregator;
    private final ExecBatchMapper batchMapper;
    private final ExecTaskMapper taskMapper;
    private final MetricSqlMapper sqlMapper;
    private final OperLogService operLogService;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3, 60L,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(10_000),
            r -> new Thread(r, "eval-batch-" + threadSeq.incrementAndGet()));

    public BatchRunner(BatchPlanner planner, SqlExecutor sqlExecutor, ResultAggregator aggregator,
                       ExecBatchMapper batchMapper, ExecTaskMapper taskMapper, MetricSqlMapper sqlMapper,
                       OperLogService operLogService) {
        this.planner = planner;
        this.sqlExecutor = sqlExecutor;
        this.aggregator = aggregator;
        this.batchMapper = batchMapper;
        this.taskMapper = taskMapper;
        this.sqlMapper = sqlMapper;
        this.operLogService = operLogService;
    }

    /** 创建批次并异步执行；立即返回 batchId（04 §12.3）。 */
    public Long startBatch(List<Integer> years, List<Long> metricIds, List<Long> scopeIds,
                           String versionTag, String operatorRealName) {
        BatchPlanner.Plan plan = planner.plan(metricIds, scopeIds, years);
        String batchNo = "RECALC_" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "_" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))
                + "_" + String.format("%03d", (int) (Math.random() * 1000));
        ExecBatch batch = new ExecBatch();
        batch.setBatchNo(batchNo);
        batch.setTriggerType("MANUAL_COVER");
        batch.setIsCurrentVersion(true);
        batch.setVersionTag(versionTag);
        batch.setExecStatus("RUNNING");
        batch.setTotalTasks(plan.tasks().size());
        batch.setSuccessTasks(0);
        batch.setFailTasks(0);
        batch.setOperatorName(operatorRealName);
        batch.setStartTime(LocalDateTime.now());
        batchMapper.insert(batch);

        // SKIPPED 子任务落库
        for (PlannedTask skipped : plan.skippedTasks()) {
            ExecTask t = baseTask(batch.getId(), skipped);
            t.setStatus("SKIPPED");
            t.setErrorMsg(skipped.reason());
            taskMapper.insert(t);
        }
        // PENDING 子任务落库（assignedYears/mixedPoolDen 仅存内存）
        Map<Long, PlannedTask> planned = new HashMap<>();
        for (PlannedTask pt : plan.tasks()) {
            ExecTask t = baseTask(batch.getId(), pt);
            t.setStatus("PENDING");
            taskMapper.insert(t);
            planned.put(t.getId(), pt);
        }
        operLogService.log("ENGINE", "BATCH_START", "eval_exec_batch", String.valueOf(batch.getId()),
                Map.of("batchNo", batchNo, "tasks", plan.tasks().size(),
                        "skipped", plan.skippedTasks().size(), "years", years.toString()));
        runAsync(batch.getId(), batchNo, planned, operatorRealName);
        return batch.getId();
    }

    private ExecTask baseTask(Long batchId, PlannedTask pt) {
        ExecTask t = new ExecTask();
        t.setBatchId(batchId);
        t.setMetricId(pt.metricId());
        t.setScopeId(pt.scopeId());
        t.setSqlId(pt.sqlId());
        t.setSqlRole(pt.sqlRole());
        t.setDatasourceKey(pt.datasourceKey());
        return t;
    }

    private void runAsync(Long batchId, String batchNo, Map<Long, PlannedTask> planned, String operatorRealName) {
        executor.submit(() -> {
            try {
                List<ExecTask> rows = taskMapper.selectList(new QueryWrapper<ExecTask>()
                        .eq("batch_id", batchId).ne("status", "SKIPPED"));
                List<TaskOutcome> outcomes = new ArrayList<>();
                List<java.util.concurrent.Future<TaskOutcome>> futures = new ArrayList<>();
                for (ExecTask row : rows) {
                    ExecTask upd = new ExecTask();
                    upd.setId(row.getId());
                    upd.setStatus("RUNNING");
                    upd.setStartTime(LocalDateTime.now());
                    taskMapper.updateById(upd);
                    PlannedTask meta = planned.get(row.getId());
                    MetricSql sql = sqlMapper.selectById(row.getSqlId());
                    futures.add(executor.submit(() ->
                            sqlExecutor.execute(row, sql, meta.assignedYears(), meta.mixedPoolDen())));
                }
                for (int i = 0; i < futures.size(); i++) {
                    TaskOutcome outcome;
                    try {
                        outcome = futures.get(i).get();
                    } catch (Exception e) {
                        ExecTask row = rows.get(i);
                        outcome = new TaskOutcome(row.getId(), row.getMetricId(), row.getScopeId(),
                                row.getSqlId(), row.getSqlRole(), "FAILED", 0, 0,
                                "EXECUTOR_ERROR: " + e.getClass().getSimpleName(), null);
                    }
                    outcomes.add(outcome);
                    ExecTask upd = new ExecTask();
                    upd.setId(outcome.taskId());
                    upd.setStatus(outcome.status());
                    upd.setRowCount(outcome.rawRows());
                    upd.setElapsedMs(outcome.elapsedMs());
                    upd.setErrorMsg(outcome.errorMsg());
                    upd.setEndTime(LocalDateTime.now());
                    taskMapper.updateById(upd);
                }
                aggregator.aggregate(batchId, outcomes);
                int success = (int) outcomes.stream().filter(o -> "SUCCESS".equals(o.status())).count();
                int fail = outcomes.size() - success;
                ExecBatch upd = new ExecBatch();
                upd.setId(batchId);
                upd.setSuccessTasks(success);
                upd.setFailTasks(fail);
                upd.setEndTime(LocalDateTime.now());
                upd.setExecStatus(fail == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL_FAIL"));
                batchMapper.updateById(upd);
                operLogService.log("ENGINE", "BATCH_END", "eval_exec_batch", String.valueOf(batchId),
                        Map.of("batchNo", batchNo, "success", success, "fail", fail,
                                "status", upd.getExecStatus()));
            } catch (Exception e) {
                log.error("批次 {} 异步执行失败", batchId, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
