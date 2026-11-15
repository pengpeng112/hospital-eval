# TASK-09 回执：算法管理与SQL版本留痕接口

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 21:20 / 2026-11-15 22:10（Asia/Shanghai）
- 状态：已完成（不连 Oracle；沙箱只做静态校验）
- Git 提交号：`25fd545 TASK-09: 算法管理与SQL版本留痕接口`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交入库）
- 环境说明：会话期间 Docker Desktop 引擎意外停止，已由本 AI 重新启动并确认 eval-postgres 数据完好（VERIFY OK）后继续。

## 一、前置检查结果

```powershell
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1（末行）
SMOKE TASK_08 ALL PASS
```

## 二、新建 / 修改的文件（12 项，均在 algo/** 白名单与冒烟白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `algo/entity/MetricSqlHis.java` | eval_metric_sql_his 逐列 |
| `algo/mapper/MetricSqlHisMapper.java` | BaseMapper |
| `algo/datasource/DatasourceRouter.java` | 04 §7.7 Java 版路由（与 Python route_datasource 逐行对应，单测保证一致） |
| `algo/dto/AlgoDtos.java` | CreateSqlReq / UpdateSqlReq / ToggleReq |
| `algo/service/SqlVersionService.java` | 查询/新增（校验→路由→ds_flag/emr_sys→his 新增→操作日志前置数据）、修改（事务内先 his 后更新、version+1、未检测到修改 400、changeType 白名单、原因≥5字）、toggle（[启用]/[停用] 前缀 his）、history、hisById |
| `algo/controller/AlgoSqlController.java` | §12.5 全部接口：list/detail 登录即可，create/update/toggle 仅 ADMIN |
| `algo/controller/AlgoSandboxController.java` | **修改（TASK-09 允许的唯一 TASK-06 改动）**：trial 增加 sqlId 回写 last_verify_status（成功=1/失败=2），失败回写后原样抛出 |
| `src/test/java/com/hospital/eval/algo/DatasourceRouterTest.java` | 复用 TASK-01 三个 route_datasource 用例 + 无模式名用例 |
| `src/test/java/com/hospital/eval/algo/SqlVersionServiceTest.java` | Mockito 模拟 Mapper：版本+1 且 his 存旧 SQL、无变化抛"未检测到修改"、changeType=新增 抛异常、原因<5字 抛异常 |
| `scripts/smoke/TASK_09.ps1` | 任务书步骤 8 全部 9 项断言（UTF-8 BOM） |
| `docs/task_receipts/TASK_09_回执.md` | 本回执 |

sandbox/ 下已有校验规则**零改动**（只调用）。

## 三、验收命令与真实输出

```text
PS> docker run ... maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.158 s -- in com.hospital.eval.algo.DatasourceRouterTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.228 s -- in com.hospital.eval.algo.ReadOnlySqlValidatorTest
一致性：mismatch=0 / 200
validate 合法比例=1.0000（200/200）
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.008 s -- in com.hospital.eval.algo.SqlVersionServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s -- in com.hospital.eval.engine.BatchPlannerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.300 s -- in com.hospital.eval.engine.ResultAggregatorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.299 s -- in com.hospital.eval.engine.ResultSetParserTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in com.hospital.eval.system.PasswordRuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.264 s -- in com.hospital.eval.system.RTest
[INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
（Started EvalApplication）

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1
PASS: 1 NEU-EPI-01 指标共 2 条 SQL ≥ 1
PASS: 2 新增测试 SQL 成功 id=2962
PASS: 3 DELETE 被沙箱拦截 code=400
PASS: 4 修改成功 versionNo=2==2
PASS: 5a history 共 2 条 == 2
PASS: 5b oldSqlAnnotated 含 1 AS 分子
PASS: 5c newSqlAnnotated 含 2 AS 分子
PASS: 6 原样重复提交返回 400 未检测到修改
PASS: 7 停用测试 SQL 成功
SMOKE TASK_09 ALL PASS

PS> git status --short
?? hospital-eval-server/src/main/java/com/hospital/eval/algo/（entity/MetricSqlHis、mapper/MetricSqlHisMapper、datasource/DatasourceRouter、dto/AlgoDtos、service/SqlVersionService、controller/AlgoSqlController）
?? hospital-eval-server/src/test/java/com/hospital/eval/algo/（2 个测试）
?? scripts/smoke/TASK_09.ps1
 M hospital-eval-server/src/main/java/com/hospital/eval/algo/controller/AlgoSandboxController.java（trial 回写）
```

## 四、关键结论（回执必含项）

- **Maven 测试汇总**：`Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`；其中 DatasourceRouter 4/4（与 Python 三个用例结果一致）、SqlVersionService 4/4。
- **冒烟**：TASK_09 全部 8 步断言通过；测试 SQL 已按任务书要求**停用**（is_active=false，防止被跑批引擎执行）。
- **trial 回写验证**：冒烟第 4 步的修改使 last_verify_status 复位为 0（更新路径）；sqlId 回写在无 Oracle 环境下逻辑已接线（成功=1/失败=2），真实回写证据待网络放行后由试跑产生。

## 五、安全自检（逐项勾选）

- [x] 本任务未连接 Oracle（沙箱仅静态校验）
- [x] 未物理删除 eval_metric_sql / eval_metric_sql_his 任何记录
- [x] 未保存未通过校验的 SQL（create/update 均先 validate）
- [x] sql_executable 由后端从 sqlAnnotated 生成（接口不接收该字段）
- [x] 未读取/打印/提交任何密码；回执无患者信息
- [x] 本机 JAVA_HOME / PATH 未修改；只改 algo/** 白名单文件 + 冒烟脚本 + 回执
- [x] 未操作 10.10.8.75；Docker Desktop 引擎恢复属本机环境操作，未触碰其他项目容器

## 六、待确认事项

1. **Docker Desktop 引擎中途停止**：会话期间（约 21:55）Linux 引擎管道消失导致构建失败，本 AI 已重启 Docker Desktop 并确认 eval-postgres 数据卷完好（VERIFY OK）。若为机器重启/崩溃所致请知悉；生产部署（TASK-19）前建议核查该稳定性。
2. **`@Transactional` 生效前提**：SqlVersionService 的事务依赖 Spring 自动代理（无需额外开启，Boot 默认启用 DataSourceTransactionManager/JpaTransactionManager 之一——本项目走 JDBC 自动配置，已实测修改路径正常返回；多语句原子性由同连接同事务保证）。
3. **toggle 的 his 版本号取当前版本**（不 +1）：任务书未明确，按"启停不产生新版本"处理。
4. **update 时 ds_flag/emr_sys 不随新 SQL 重算**（任务书更新字段清单未列），period_flag 同步置 null 由后续试跑刷新——如需重算请主控明确。

## 七、未完成

无。
