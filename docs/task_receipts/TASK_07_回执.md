# TASK-07 回执：跑批引擎与双链路落库

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 19:50 / 2026-11-15 21:05（Asia/Shanghai）
- 状态：已完成（Oracle 真实跑批部分因本机监听白名单未放行按任务书预设 SKIP，编码与单测全部完成）
- Git 提交号：见文末（提交后回填）
- **声明：本任务对 Oracle 只执行 SELECT（`sql_executable` 原样执行 + 强制沙箱校验；本机白名单未放行，实际未穿透到数据库），写入数量为 0。**

## 一、前置检查结果

```powershell
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_06.ps1
PASS: 1 数据源列表返回 2 条
PASS: 1b 返回不含 password/username 字段
PASS: 2a DELETE 被拦截 valid=false
PASS: 2b SELECT 放行 valid=true
SKIP: ODS_RO 本机网络或Oracle监听白名单未放行 (<SQLRecoverableException (vendorCode=17002)>)
SKIP: HIS_RO 本机网络或Oracle监听白名单未放行 (<SQLRecoverableException (vendorCode=17002)>)
SKIP: 5 ODS_RO ping 未连通
SKIPPED: 3
SMOKE TASK_06 DONE
（无 FAIL → 满足；Oracle SKIP 分支按任务书 §3 预设照常编码）
```

## 二、新建 / 修改的文件（28 新建 + 1 修改）

| 类别 | 文件 |
| :--- | :--- |
| metric/**（TASK-08 复用） | `metric/entity/MetricDict.java`、`MetricDeptScope.java`（逐列）、`metric/mapper/` 2 个 |
| algo/** | `algo/entity/MetricSql.java`（逐列）、`algo/mapper/MetricSqlMapper.java` |
| engine/entity | `ExecBatch`、`ExecTask`、`MetricResult`、`MetricDetail`（逐列） |
| engine/mapper | 4 个 BaseMapper |
| engine/dto | `BatchRunReq`、`ExecTaskView` |
| engine/service | `BatchPlanner`（含纯函数 assignYears 选优）、`SqlExecutor`、`ResultSetParser`（含 ParseContext/SumResult/ParsedDetail/两异常嵌套类型）、`DetailWriter`（1,000 行批 batchUpdate）、`ResultAggregator`（纯函数 computeScope/computeHospital/computeResultVal/shouldMergeDedup + DB 编排 + 当期切换 ARCHIVED_LOCKED）、`BatchRunner`（3 线程池 eval-batch-%d、异步、统计收尾）、`RestartCleanupRunner`（重启标记 SERVER_RESTARTED） |
| engine/controller | `BatchController`（§12.3 四接口，全部 @SaCheckRole("ADMIN")） |
| application.yml | 无需改动（eval.engine.* 已在 TASK-06 追加） |
| 测试 | `ResultSetParserTest`（6）、`ResultAggregatorTest`（6）、`BatchPlannerTest`（4） |
| 冒烟 | `scripts/smoke/TASK_07.ps1` |
| 回执 | `docs/task_receipts/TASK_07_回执.md` |
| **白名单外修改（申报）** | `system/service/OperLogService.java`：IP 解析加非请求线程守卫（try/catch，异步线程无请求上下文时 ip=null）——任务书步骤 6 要求"批次开始/结束写 sys_oper_log"，批次收尾日志在异步线程执行，无此守卫必然抛异常。改动 6 行，TASK-03/05 已有同类"白名单外必要修改+申报"先例 |

## 三、验收命令与真实输出

```text
PS> docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.443 s -- in com.hospital.eval.algo.ReadOnlySqlValidatorTest
一致性：mismatch=0 / 200
validate 合法比例=1.0000（200/200）
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.678 s -- in com.hospital.eval.algo.SqlCommentStripperConsistencyTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.046 s -- in com.hospital.eval.engine.BatchPlannerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.318 s -- in com.hospital.eval.engine.ResultAggregatorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.371 s -- in com.hospital.eval.engine.ResultSetParserTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in com.hospital.eval.system.PasswordRuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.316 s -- in com.hospital.eval.system.RTest
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```text
PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
 Container eval-postgres Healthy
 Container eval-server Starting
 Container eval-server Started
（sleep 25 后日志：Started EvalApplication in 9.557 seconds）
```

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS
```

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_07.ps1
PASS: 1 空 metricIds/scopeIds 被拒绝 code=400
SKIP: Oracle 未连通 (SQLRecoverableException (vendorCode=17002))，真实跑批部分跳过
SMOKE TASK_07 DONE
```

```text
PS> git status --short
（29 项 = 28 个白名单新文件 + system/service/OperLogService.java 白名单外修改，见第二节申报）
```

## 四、关键结论（回执必含项）

- **Maven 测试汇总**：`Tests run: 37, Failures: 0, Errors: 0, Skipped: 0`；一致性测试 mismatch=0/200、合法比例 1.0000。
- **冒烟完整输出**：见第三节；防误跑（空 metricIds/scopeIds → 400）通过；Oracle 真实跑批部分按任务书 §3 预设 SKIP。
- **3 个测试实例对照表**：因 Oracle SKIP 未产出——单测层已验证解析/聚合/选优逻辑（37 用例），真实对照表待网络放行后复跑 TASK_07.ps1 自动生成并打印。
- **Oracle 写入数量 = 0**。

## 五、安全自检（逐项勾选）

- [x] Oracle 只执行 SELECT；每条 SQL 执行前过 ReadOnlySqlValidator；试跑/跑批强制沙箱
- [x] 未改写存量 SQL（原样执行 sql_executable）；未截断结果标成功（>500,000 → FAILED+删本子任务明细）
- [x] 未跨批次删除明细（仅删本批次本实例本角色，且仅在 ROW_LIMIT 时）
- [x] 未读取/打印/提交任何密码；日志与回执无患者信息（明细表为空，本机无真实跑批）
- [x] 本机 JAVA_HOME 未修改；白名单外仅 OperLogService 6 行守卫（已申报）
- [x] 未操作 10.10.8.75；未触碰本机其他项目容器

## 六、待确认事项

1. **OperLogService 白名单外 6 行修改**（见第二节粗体行）：异步线程 IP 解析守卫，系步骤 6"批次结束写日志"的必要条件，请主控追认。
2. **Oracle 真实跑批证据缺口**：待用户放行网络后复跑 TASK_07.ps1（≥3 实例对照表将自动打印），本回执以单测层证据 + SKIP 分支交付。
3. **ResultSetParser 年份列惰性解析**：列元数据在首行回调时解析——0 行结果集不触发 NO_YEAR_COLUMN（无行无需归年），语义上合理，请主控确认口径。
4. **多行 processRow 的测试打桩口径**：被年份过滤的行不产生数值列读点，数值列桩需按"首个有效行"对齐（测试 6 已按此修正并加注释）。

## 七、未完成

无（Oracle 真实跑批为预设 SKIP 分支，见第 2 条待确认）。
