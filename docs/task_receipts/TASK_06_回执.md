# TASK-06 回执：只读SQL沙箱与Oracle只读数据源

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 19:35 / 2026-11-15 20:12（Asia/Shanghai）
- 状态：已完成（Oracle 双源因本机监听白名单未放行按规则 SKIP，编码与冒烟断言全部按预期工作）
- Git 提交号：`388abed TASK-06: 只读SQL沙箱与Oracle只读数据源`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交入库）
- **声明：本任务对 Oracle 只执行了 SELECT（`SELECT 1 FROM DUAL` 连通探测与带 ROWNUM 包裹的试跑，且实际均未到达数据库——被本机白名单拦截于连接阶段），写入数量为 0。**

## 一、前置检查结果

```powershell
PS> Select-String -Path deploy/.env -Pattern '^(ODS_RO_JDBC_URL|ODS_RO_USERNAME|HIS_RO_JDBC_URL|HIS_RO_USERNAME)=' | % { $_.Line.Split('=')[0] + ' 已配置' }
ODS_RO_JDBC_URL 已配置
ODS_RO_USERNAME 已配置
HIS_RO_JDBC_URL 已配置
HIS_RO_USERNAME 已配置
（值为 .env.example 的 <DBA 发放的…> 占位符 → 运行时按"以 < 开头"规则视为未配置，走 SKIP 路径）
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS
```
（另：主控已在 ef114d6/68d87e3 落裁决——契约 jsqlparser 更新为 4.6 与本工程 pom 一致、kkFileView 已取消注释编排、Oracle 本机白名单兼容规则已入 AGENTS.md。）

## 二、新建 / 修改的文件（11 项，均在白名单）

| 文件 | 新建/修改 | 说明 |
| :--- | :--- | :--- |
| `config/OracleDataSourceConfig.java` | 新建 | 双只读 Hikari 池（3/0/readonly/10s/300s/autocommit、oracle.jdbc.ReadTimeout=600000）；**不注册为 DataSource Bean**（避免触发 Boot 自动配置回退、保住 @Primary 的 PostgreSQL 主源）；`<` 占位符视为未配置；@PreDestroy 关闭 |
| `algo/sandbox/SqlCommentStripper.java` | 新建 | 04 §7.4 状态机 Java 版，与 Python 逐行为对应 |
| `algo/sandbox/ValidationResult.java` | 新建 | {valid, errors, warnings, executableSql} |
| `algo/sandbox/ReadOnlySqlValidator.java` | 新建 | 五步校验（去注释/多语句/开头/关键字黑名单[DBMS_LOB 三函数豁免]/JSqlParser 解析[失败仅 warning]） |
| `algo/datasource/OracleQueryService.java` | 新建 | ping / trial（ROWNUM≤20 包裹、120s 超时、CLOB 前 200 字、日期格式化、异常脱敏） |
| `algo/dto/SandboxDtos.java` | 新建 | ValidateReq / TrialReq（sqlId 留给 TASK-09 回写） |
| `algo/controller/AlgoSandboxController.java` | 新建 | §12.2 四接口；ping/trial 仅 ADMIN；trial 写 ALGO/TRIAL 操作日志 |
| `src/main/resources/application.yml` | 修改 | 仅追加 `eval.oracle.*` 与 `eval.engine.*`（600s/2000/3） |
| `src/test/java/com/hospital/eval/algo/ReadOnlySqlValidatorTest.java` | 新建 | 步骤 5 全部 14 个用例 |
| `src/test/java/com/hospital/eval/algo/SqlCommentStripperConsistencyTest.java` | 新建 | 种子 42 取 200 条一致性 + 合法比例（Docker 构建中 assumeTrue 跳过） |
| `scripts/smoke/TASK_06.ps1` | 新建 | 步骤 6 全部检查（UTF-8 BOM） |

## 三、验收命令与真实输出

```text
PS> docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.337 s -- in com.hospital.eval.algo.ReadOnlySqlValidatorTest
一致性：mismatch=0 / 200
validate 合法比例=1.0000（200/200）
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.853 s -- in com.hospital.eval.algo.SqlCommentStripperConsistencyTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.027 s -- in com.hospital.eval.system.PasswordRuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.290 s -- in com.hospital.eval.system.RTest
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```text
PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
 Container eval-postgres Healthy
 Container eval-server Starting
 Container eval-server Started
（sleep 25 后 docker logs：Started EvalApplication in 3.438 seconds）
```

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
PASS: 1 /api/health UP/UP
PASS: 2 错误密码登录返回 code=400
PASS: 3 admin 登录成功且含 ADMIN 角色
PASS: 4 未登录访问 me 返回 401
PASS: 5 科室数量 81 ≥ 10
PASS: 6 用户总数 68 ≥ 20
PASS: 7a smoke_user_05 已存在，跳过创建
PASS: 7b smoke_user_05 登录且 mustChangePwd=true
PASS: 8 操作日志存在 LOGIN 记录
SMOKE TASK_05 ALL PASS
```

```text
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
```

```text
PS> git status --short
 M hospital-eval-server/src/main/resources/application.yml
?? hospital-eval-server/src/main/java/com/hospital/eval/algo/（8 个新文件）
?? hospital-eval-server/src/test/java/com/hospital/eval/algo/（2 个测试）
?? scripts/smoke/TASK_06.ps1
（逐一核对均在白名单；target/ 已被忽略）
```

## 四、关键结论（回执必含项）

- **Maven 测试汇总**：`Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`；其中沙箱校验器 14/14 通过。
- **一致性测试**：随机种子 42 取 200 条存量 SQL，Java `strip()` 与 Python `sql_executable` **mismatch=0**（完全一致）；`validate` 合法比例 **1.0000（200/200）**，无"不合法清单"（高于 ≥99% 预期）。
- **Oracle 连通结果**：HIS_RO / ODS_RO 均 **SKIP**——`ping` 返回 `SQLRecoverableException (vendorCode=17002)`，即 AGENTS.md §三.2 所载"本机直连未加入白名单"的标准表现；未出现 ORA-01017（账号类）错误。待用户放行本机内网 IP 或开启 data-asset-83 转发后，复跑 TASK_06.ps1 即可走真实连通与试跑分支。
- **Oracle 写入数量 = 0**（本任务仅 SELECT 探测，且未穿透白名单）。

## 五、安全自检（逐项勾选）

- [x] Oracle 只执行 SELECT（连通探测 `SELECT 1 FROM DUAL`；试跑强制 `SELECT * FROM (原SQL) WHERE ROWNUM <= 20` 且 120s 超时），写入数量为 0
- [x] 未读取/打印/提交任何密码或 deploy/.env 内容；datasources 接口只返回 key/host/configured；ping/trial 异常只回简短类名 + ORA 号或 vendorCode
- [x] 回执与仓库无患者信息（试跑未真实执行到数据）
- [x] 本机 JAVA_HOME / PATH 未修改（编译全在容器内 JDK 17）
- [x] 只改白名单文件（application.yml 仅追加 eval.oracle.* 与 eval.engine.*）
- [x] 未操作 10.10.8.75；未触碰本机其他项目容器

## 六、待确认事项

1. **Oracle SKIP 待用户放行**：本机→10.10.8.216/10.10.10.15 的 1521 监听未放行本机 IP（vendorCode=17002）。由用户选择"加入白名单"或"开启 data-asset-83 SSH 转发"后复跑 `scripts/smoke/TASK_06.ps1`，即可补齐真实连通与试跑证据（任务书步骤 3 允许该路径）。
2. **`EXEC` 关键字与存量 SQL**：黑名单含 `EXEC`，若存量 SQL 出现 `DBMS_LOB...` 以外的包函数调用（如 `UTL_RAW`）会被拦——设计如此（只读沙箱），TASK-09 人工核对时按提示处理。
3. `trial` 对 `sqlId` 的回写按任务书属 TASK-09，本任务 DTO 已预留字段但不处理。

## 七、未完成

无（Oracle 真实连通证据依赖用户网络放行，属任务书预设的 SKIP 分支）。
