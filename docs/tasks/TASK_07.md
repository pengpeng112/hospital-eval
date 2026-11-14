# TASK-07：跑批引擎（RAW 原样执行 → 结果解析 → 明细与汇总双链路落库）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 4 · 第 1 次 |
| 依赖 | TASK-06 |
| 预计改动 | 12~18 个 Java 文件 + 测试 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL（写）；Oracle（**只读**）；**不连** 10.10.8.75 |

## 1. 目标
实现 `/api/batch/*` 四个接口：按指定指标/科室实例与年份，把已入库的存量 SQL **原样**送到对应 Oracle 只读源执行，解析结果集（年份列、SUM 数值或 DTL 明细），写入 `eval_metric_detail` 与 `eval_metric_result`，并按规则生成全院行。这是整个系统的核心，**宁可慢，不可错**。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 5.4、8、9、10、12.3 节（**逐字读**）
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_06.ps1     # 无 FAIL
```
- 若 TASK-06 回执中 Oracle 是 SKIP（账号未配置）：照常编码与单测；冒烟中真实跑批部分输出 `SKIP`，回执列为阻塞项。

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/engine/**`
- `hospital-eval-server/src/main/java/com/hospital/eval/metric/entity/**`、`metric/mapper/**`（本任务需要读 `eval_metric_dict`、`eval_metric_dept_scope`，可以先建实体和 Mapper；TASK-08 会在同包继续加 Controller/Service）
- `hospital-eval-server/src/main/java/com/hospital/eval/algo/entity/**`、`algo/mapper/**`（`eval_metric_sql` 实体与 Mapper）
- `hospital-eval-server/src/main/resources/application.yml`（只允许追加 `eval.engine.*`）
- `hospital-eval-server/src/test/java/com/hospital/eval/engine/**`
- `scripts/smoke/TASK_07.ps1`
- `docs/task_receipts/TASK_07_回执.md`

## 5. 禁止事项
- 禁止改写存量 SQL（不替换日期、不加条件）；只能原样执行 `sql_executable`。
- 禁止在 Oracle 执行非 SELECT；每条 SQL 执行前必须调用 `ReadOnlySqlValidator.validate`。
- 禁止截断结果后标记成功（超过 500,000 行必须 `FAILED`）。
- 禁止跨批次删除明细；旧批次数据必须保留（历史版本）。
- 禁止 `/api/batch/run` 在 `metricIds` 和 `scopeIds` 都为空时执行（防止误跑全院数千条 SQL）。

## 6. 核心类（类名照抄）

| 类 | 职责 |
| :--- | :--- |
| `engine/entity/ExecBatch`、`ExecTask`、`MetricResult`、`MetricDetail` + 对应 Mapper | 表实体 |
| `engine/dto/BatchRunReq` | `metricIds`、`scopeIds`、`years`、`versionTag` |
| `engine/service/BatchPlanner` | 根据请求生成子任务列表 |
| `engine/service/SqlExecutor` | 校验 + 执行一条 SQL，流式读取结果 |
| `engine/service/ResultSetParser` | 年份列识别、SUM 数值提取、DTL 列映射（04 §8） |
| `engine/service/DetailWriter` | 批量写 `eval_metric_detail`（每 1,000 行一批） |
| `engine/service/ResultAggregator` | 按 04 §9 计算科室行与全院行，处理“当期”切换（04 §5.4） |
| `engine/service/BatchRunner` | 线程池调度、状态更新、批次收尾 |
| `engine/controller/BatchController` | 4 个接口（04 §12.3），仅 `ADMIN` |

## 7. 操作步骤

### 步骤 1：`BatchPlanner`
输入 `metricIds?`、`scopeIds?`、`years`：
1. 确定科室实例集合：
   - 给了 `scopeIds` → 只用这些实例；
   - 只给了 `metricIds` → 这些指标下的**全部**实例；
   - 两者都空 → 抛 `BizException(400, "必须指定指标或科室实例")`。
2. 跳过 `skip_flag = true` 的实例（为它们在 `eval_exec_task` 写一条 `SKIPPED`，`error_msg='SKIP_FLAG'`）。
3. 对每个实例取 `is_active = true` 的 SQL，过滤出 `[period_start 年, period_end 年]` 与请求 `years` 有交集的；每条 SQL 生成一个子任务（`PENDING`）。
4. 没有任何 SQL 的实例：写一条 `SKIPPED` 子任务，`error_msg='NO_SQL'`（这些等待人工填报）。

### 步骤 2：创建批次
- `batch_no` = `RECALC_yyyyMMdd_HHmmss_<3位随机数>`；`trigger_type='MANUAL_COVER'`；`exec_status='RUNNING'`；`is_current_version=true`；`version_tag` 取请求值；`operator_name` = 当前用户真实姓名；`total_tasks` = 非 SKIPPED 子任务数。
- 接口立即返回 `{batchId, batchNo}`，实际执行**异步**进行。

### 步骤 3：`SqlExecutor` + `ResultSetParser`（每个子任务）
1. 子任务状态改 `RUNNING`，记 `start_time`。
2. `validate(sql_annotated)` 不通过 → `FAILED`，`error_msg='SANDBOX_REJECTED: ' + 原因`。
3. 数据源未配置 → `FAILED`，`error_msg='DATASOURCE_NOT_CONFIGURED: HIS_RO'`。
4. 用对应 `JdbcTemplate.query(executableSql, RowCallbackHandler)` 流式读取（**不要**一次 `queryForList` 全部读进内存）；
5. 读取 `ResultSetMetaData`，用列别名（`getColumnLabel`）按 04 §8.1 找年份列；年份值可能是数字、字符串 `'2025'` 或日期，统一转成 4 位整数年；
6. 按 04 §8.1 过滤年份（`year_from ≤ 年 ≤ year_to` 且在请求 `years` 内）；
7. **SUM 形态**：按 04 §8.2 找数值列，每年累加；
8. **DTL 形态**：按 04 §8.3 映射列；时间列用 `getTimestamp`；CLOB 列用 `getClob` 只读前 500 字符；其余列 `getString` 截断 500 字符放入 `ext` Map；
9. 行数 > 500,000 → 立即停止读取，`FAILED`，`error_msg='ROW_LIMIT_EXCEEDED'`，并删除本子任务已写入的明细；
10. 成功 → `SUCCESS`，记 `row_count`（原始行数）、`elapsed_ms`、`end_time`；Oracle 异常 → `FAILED`，`error_msg` 保留 ORA 错误号与前 500 字符的消息。

### 步骤 4：`DetailWriter`（仅 DTL）
- 写入字段：`batch_id`、`metric_id`、`scope_id`、`stat_year`、`stat_month`（有出院时间时取 `yyyy-MM`，否则 NULL）、`role_type`（`NUM_*`→`NUM`、`DEN_*`→`DEN`）、04 §8.3 映射的固定列、`ext_json`。
- `manual_selected`：若该实例属于**混合池**（04 §9 第 1 条的定义：有效提取方式 HYBRID、有 DEN SQL、无 NUM SQL）且 `role_type='DEN'` → `false`；其他情况 → `true`。
- 用 JDBC `batchUpdate`，每 1,000 行提交一次。

### 步骤 5：`ResultAggregator`（批次内全部子任务结束后执行）
对本批次涉及的每个 `(metric_id, scope_id, year)`：
1. 按 04 §9 计算 `numerator_val`、`denominator_val`、`result_val`、`data_source_tag`；
   - 去重计数用 SQL：`SELECT COUNT(DISTINCT COALESCE(patient_id,'') || '-' || COALESCE(visit_id,'') ) ...`，两者都空的行按 04 §8.3 的兜底规则处理（建议在 Java 里生成去重键再计数，或分两段 SQL 统计）；
   - 某角色的 SQL 子任务 `FAILED` → 该角色值写 `NULL`，并在结果中不覆盖旧当期值（见第 3 点）。
2. 插入 `eval_metric_result`：`batch_id`、`stat_period_type='YEAR'`、`stat_period='2025'`、`sql_version_num/den`（取所用 SQL 的 `version_no`）、`audit_status='DRAFT'`。
3. **当期切换**（04 §5.4）：
   - 本组合任一角色失败 → 新行 `is_current=false`（保留旧当期不动）；
   - 旧当期行是 `L3_ARCHIVED` → 新行 `is_current=false`，对应子任务 `error_msg` 追加 `ARCHIVED_LOCKED`；
   - 否则旧当期行改 `is_current=false`，新行 `is_current=true`。
4. **全院行**（`scope_id=0`）：每个涉及的 `(metric_id, year)` 按 04 §9 第 4 条计算；参与计算的科室值一律取**当期行**（包括本批次刚写入的和其他批次/人工的当期值）；DEDUP_DTL 合并去重时，明细取各科室**当期行所在批次**的明细。写入方式与当期切换规则同上。

### 步骤 6：`BatchRunner`
- 线程池：`ThreadPoolExecutor(core=3, max=3, queue=10000)`，线程名 `eval-batch-%d`；同一时刻最多 3 条 SQL 在 Oracle 上执行。
- 全部子任务结束 → 执行 `ResultAggregator` → 更新批次 `success_tasks`、`fail_tasks`、`end_time`、`exec_status`（全成功 `SUCCESS`；部分失败 `PARTIAL_FAIL`；全失败 `FAILED`）。
- 应用重启时，把仍为 `RUNNING` 的批次与子任务标为 `FAILED`，`error_msg='SERVER_RESTARTED'`（`ApplicationRunner` 实现）。
- 每个批次开始/结束写 `sys_oper_log`（module=`ENGINE`）。

### 步骤 7：单元测试（不连真实 Oracle）
- `ResultSetParserTest`：用 Mockito 或手写 `ResultSet` 替身覆盖：
  1. SUM：列 `年份, 分子`，两行 2024/2025 → 正确解析；
  2. SUM：同一年两行 → 相加并标记 `MULTI_ROWS_SUMMED`；
  3. DTL：列含 `年份, 住院号, 患者ID, 姓名, 住院次数, 出院时间, 文书内容`，`文书内容` 600 字 → 进 `ext` 且被截断为 500 字；
  4. 无年份列且 `year_from==year_to==2025` → 全部归 2025；
  5. 无年份列且范围 2022~2025 → 抛出 `NO_YEAR_COLUMN`；
  6. 年份 2021 的行（SQL 范围 2022~2024）被过滤掉。
- `ResultAggregatorTest`（纯 Java 计算逻辑，抽出 `computeScope` / `computeHospital` 静态方法测试）：
  1. 两科室 DTL，患者 A 同时出现在两科 → 全院去重计数正确（不是简单相加）；
  2. 一科 SUM、一科 DTL → 全院为相加；
  3. 混合池：DEN 5 人，勾选 2 人 → 分子 2；
  4. `unit='百分比（%）'`，num=33、den=129 → `result_val=25.5814`；
  5. den=0 → `result_val=null`；
  6. `skip_flag=true` 的科室不参与全院计算。

### 步骤 8：冒烟脚本 `scripts/smoke/TASK_07.ps1`
1. 调用 `/api/batch/run` 不带 `metricIds`/`scopeIds` → 返回 `code == 400`；
2. 若 Oracle 未配置 → 打印 `SKIP` 并结束；
3. 从 PostgreSQL 选 **3 个**测试实例（用 `docker exec eval-postgres psql` 查询）：
   - A：呼吸专业中有 `NUM_SUM` 与 `DEN_SUM` 的实例（单科室，`datasource_key='ODS_RO'`）；
   - B：康复专业中有 `NUM_DTL` 与 `DEN_DTL` 的实例；
   - C：麻醉专业中任意有 SQL 的实例；
4. 对 A、B、C 发起一次跑批（`years=[2025]`），每 10 秒轮询 `/api/batch/{id}`，最长等 30 分钟；
5. 结束后打印每个子任务的状态、行数、耗时、错误信息；
6. 对每个成功的实例，打印 2025 年科室行与全院行的 `num/den/val`，并与 `BATCH_BASELINE_IMPORT` 中同实例同年的值并排打印（**不要求一致**，只打印供核对）；
7. 打印 `SMOKE TASK_07 DONE`。

## 8. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_07.ps1
git status --short
```

## 9. 回执必须包含
- Maven 测试汇总行；
- 冒烟脚本完整输出（**只含统计数字，不含任何患者姓名、住院号**）；
- 3 个测试实例的“新跑批结果 vs 存量基线”对照表；
- **声明：Oracle 写入数量为 0**；
- 提交号。

提交信息：`TASK-07: 跑批引擎与双链路落库`
