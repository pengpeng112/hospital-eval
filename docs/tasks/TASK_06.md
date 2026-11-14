# TASK-06：只读 SQL 沙箱、Oracle 双只读数据源与试跑接口

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 3 · 第 2 次 |
| 依赖 | TASK-05 |
| 预计改动 | 8~12 个 Java 文件 + 测试 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；**只读**连接 Oracle（HIS `10.10.10.15:1521`、ODS `10.10.8.216:1521`）；**不连** 10.10.8.75 |

## 1. 目标
1. 实现与 Python 完全一致的 SQL 注释剥离器（Java 版）。
2. 实现**只读沙箱校验器**：只放行单条 `SELECT` / `WITH`，拦截一切写操作。
3. 配置两个 Oracle 只读连接池（HIS_RO、ODS_RO），实现数据源列表、连通测试、SQL 校验、SQL 试跑（≤20 行）四个接口。

## 2. 开工前必读
1. `AGENTS.md` 第三节第 1~3 条（**业务源库写入数量必须为 0**）
2. `04_开发契约与编码规范.md` 第 7.4、8.4、12.2 节
3. 本文件

## 3. 前置检查（用户需事先完成第 1 项）
```powershell
Select-String -Path deploy/.env -Pattern '^(ODS_RO_JDBC_URL|ODS_RO_USERNAME|HIS_RO_JDBC_URL|HIS_RO_USERNAME)=' | % { $_.Line.Split('=')[0] + ' 已配置' }
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1     # 仍然全部 PASS
```
- `deploy/.env` 中 `ODS_RO_*`、`HIS_RO_*` 由**用户**填写 DBA 发放的只读账号。若用户尚未填写：照常完成编码与单元测试，冒烟脚本中与 Oracle 相关的检查输出 `SKIP: Oracle 账号未配置`，回执中列为阻塞项。**禁止**向用户索要密码、禁止去别处找凭据文件。
- 若 `ODS_RO_JDBC_URL` 的格式与 `.env.example` 不同（例如是 Service Name 而不是 SID），以用户填写的为准，不要改。

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/config/OracleDataSourceConfig.java`
- `hospital-eval-server/src/main/java/com/hospital/eval/algo/**`（本任务只建 `sandbox/SqlCommentStripper.java`、`sandbox/ReadOnlySqlValidator.java`、`sandbox/ValidationResult.java`、`datasource/OracleQueryService.java`、`controller/AlgoSandboxController.java`、相关 dto）
- `hospital-eval-server/src/main/resources/application.yml`（只允许追加 `eval.oracle.*` 与 `eval.engine.*` 配置）
- `hospital-eval-server/src/test/java/com/hospital/eval/algo/**`
- `scripts/smoke/TASK_06.ps1`
- `docs/task_receipts/TASK_06_回执.md`

## 5. 禁止事项
- 禁止对 Oracle 执行**任何**非 SELECT 语句（包括 `ALTER SESSION`、`CREATE`、`DBMS_*` 过程调用）。
- 禁止在日志、接口返回、回执中输出 Oracle 账号或密码；`/api/algo/datasources` 只返回主机 IP 和 `configured` 布尔值。
- 禁止在没有 `ROWNUM` 包裹的情况下试跑。

## 6. 操作步骤

### 步骤 1：`SqlCommentStripper.strip(String sql)`
- 严格按 04 §7.4 状态机实现（单引号字符串、`''` 转义、双引号标识符、行注释保留换行、块注释替换为空格、去首尾空白和末尾分号）。
- 要求与 Python `strip_sql_comments` 对同样输入输出**完全一致**（见步骤 5 的一致性测试）。

### 步骤 2：`ReadOnlySqlValidator.validate(String sqlAnnotated) → ValidationResult{valid, errors, executableSql}`
按顺序执行，任一失败即 `valid=false` 并把原因加入 `errors`：
1. `executableSql = SqlCommentStripper.strip(sqlAnnotated)`；为空 → `SQL为空`。
2. **多语句检查**：在去注释后的文本中，**字符串常量之外**出现 `;` → `禁止多条语句`。
3. **开头检查**：去掉前导空白和左括号后，必须以 `SELECT` 或 `WITH` 开头（不区分大小写）→ 否则 `只允许 SELECT / WITH 查询`。
4. **关键字黑名单**：在**字符串常量之外**（把字符串内容替换成空格后再检查），按单词边界匹配以下任一关键字（不区分大小写）→ `检测到禁止关键字: XXX`：
   `INSERT UPDATE DELETE MERGE DROP CREATE ALTER TRUNCATE GRANT REVOKE COMMIT ROLLBACK SAVEPOINT EXECUTE EXEC CALL BEGIN DECLARE LOCK RENAME COMMENT PURGE FLASHBACK DBMS_ UTL_`；
   以及短语 `FOR UPDATE`、`INTO`（`SELECT ... INTO` 也禁止）。
5. **JSqlParser 解析**：用 `CCJSqlParserUtil.parse(executableSql)`；解析成功则必须是 `net.sf.jsqlparser.statement.select.Select` 实例，否则 → `不是查询语句`。**解析失败不判为不合法**（存量 Oracle SQL 中有 JSqlParser 不支持的语法），只在 `errors` 之外的 `warnings` 字段记 `JSqlParser 无法解析，已按关键字规则校验`。
6. 全部通过 → `valid=true`。
- 注意：`DBMS_LOB.SUBSTR(...)` 是存量 SQL 读取 CLOB 的常用函数，**必须放行**。因此第 4 步中 `DBMS_` 规则改为：匹配 `DBMS_` 开头的标识符，但**排除** `DBMS_LOB.SUBSTR`、`DBMS_LOB.GETLENGTH`、`DBMS_LOB.INSTR`。

### 步骤 3：`OracleDataSourceConfig`
- 读取环境变量：`HIS_RO_JDBC_URL / HIS_RO_USERNAME / HIS_RO_PASSWORD`、`ODS_RO_JDBC_URL / ODS_RO_USERNAME / ODS_RO_PASSWORD`。
- 对每个已配置的源创建 `HikariDataSource`。“已配置”= URL、用户名、密码三项都非空，且都**不以 `<` 开头**（`.env.example` 的占位符形如 `<DBA 发放的…>`，必须视为未配置）。参数：`maximumPoolSize=3`、`minimumIdle=0`、`readOnly=true`、`connectionTimeout=10000`、`idleTimeout=300000`、`poolName=HIS_RO`/`ODS_RO`、`autoCommit=true`；连接属性 `oracle.jdbc.ReadTimeout=600000`。
- 未配置的源不创建，不影响启动。
- 主数据源（PostgreSQL）必须仍由 Spring Boot 自动配置并标为 `@Primary`（如果手工建 Oracle 数据源导致自动配置失效，要手工声明 `@Primary` 的 PostgreSQL 数据源）。
- 提供 `Map<String, JdbcTemplate> oracleTemplates`（key=`HIS_RO`/`ODS_RO`），每个 `JdbcTemplate` 设置 `queryTimeout = eval.engine.query-timeout-seconds`（默认 600）、`fetchSize = 2000`。

### 步骤 4：`OracleQueryService` 与 `AlgoSandboxController`
- `ping(key)`：执行 `SELECT 1 FROM DUAL`，返回 `{ok, elapsedMs, message}`；异常时 `ok=false`，`message` 只保留异常类名和 ORA 错误号（如 `ORA-01017`），不要原样返回可能含账号的异常信息。
- `trial(sqlText, key)`：先 `validate`，不合法直接返回 400 + 错误列表；合法则执行 `SELECT * FROM (<executableSql>) WHERE ROWNUM <= 20`，`queryTimeout` 固定 120 秒；返回 `{columns, rows, rowCount, elapsedMs}`；CLOB 列只取前 200 字符，日期列格式化为 `yyyy-MM-dd HH:mm:ss`。
- 接口路径与入参严格按 04 §12.2；`trial` 与 `ping` 仅 `ADMIN` 角色可调用；`validate` 与 `datasources` 登录即可。
- 每次 `trial` 写 `sys_oper_log`（module=`ALGO`，action=`TRIAL`，detail 只记数据源、SQL 前 200 字、行数、耗时）。

### 步骤 5：单元测试（不连真实 Oracle）
`ReadOnlySqlValidatorTest` 至少覆盖（全部必须通过）：
| 输入 | 期望 |
| :--- | :--- |
| `SELECT 1 FROM dual` | valid |
| `WITH a AS (SELECT 1 x FROM dual) SELECT * FROM a` | valid |
| `SELECT '--x;' AS a FROM dual -- 注释` | valid（字符串中的 `--` 和 `;` 不算） |
| `SELECT a FROM t UNION ALL SELECT b FROM t2` | valid |
| `SELECT DBMS_LOB.SUBSTR(c, 200, 1) FROM t` | valid |
| `SELECT 'DELETE' AS a FROM dual` | valid（字符串中的关键字不算） |
| `DELETE FROM t` | invalid |
| `SELECT 1 FROM dual; DELETE FROM t` | invalid（多语句） |
| `SELECT * FROM t FOR UPDATE` | invalid |
| `SELECT a INTO b FROM t` | invalid |
| `/* x */ UPDATE t SET a=1` | invalid |
| `BEGIN NULL; END;` | invalid |
| `SELECT DBMS_RANDOM.VALUE FROM dual` | invalid |
| `--注释\nCALL p()` | invalid |

`SqlCommentStripperConsistencyTest`：读取 `migration/dist/sqls.json`（相对路径 `../migration/dist/sqls.json`，文件不存在则跳过测试），随机取 200 条（固定随机种子 42）的 `sql_annotated`，断言 Java `strip()` 结果与 JSON 中的 `sql_executable` **完全相等**；并统计这 200 条中 `validate` 为 valid 的比例（打印出来，回执中写明；期望 ≥ 99%，不合法的逐条列出原因）。
> Docker 构建时测试读不到上级目录，所以该测试用 `Assumptions.assumeTrue(Files.exists(path))` 跳过；**本地验收命令**会挂载整个项目目录来运行它，见第 7 节。

### 步骤 6：冒烟脚本 `scripts/smoke/TASK_06.ps1`
1. `/api/algo/datasources` 返回 2 条，且不包含字段名 `password` / `username`；
2. `/api/algo/sql/validate` 提交 `DELETE FROM t` → `valid == false`；提交 `SELECT 1 FROM dual` → `valid == true`；
3. 若 `ODS_RO` 已配置：`ping` 返回 `ok == true`；`trial` 执行 `SELECT COUNT(*) AS CNT FROM HIS.PAT_VISIT WHERE ROWNUM <= 1`（若 ODS 无此表，改用 `SELECT 1 AS X FROM DUAL`）→ `rowCount == 1`；否则输出 `SKIP`；
4. 若 `HIS_RO` 已配置：`ping` 返回 `ok == true`；`trial` 执行 `SELECT COUNT(*) AS CNT FROM COMM.DEPT_DICT WHERE ROWNUM <= 1`（失败则改 `SELECT 1 AS X FROM DUAL`）；否则 `SKIP`；
5. 从 PostgreSQL 取 1 条 `datasource_key='ODS_RO'` 且 `sql_role` 以 `_SUM` 结尾的存量 SQL，调用 `trial`（只取 20 行）→ 成功或返回明确的 ORA 错误（两种都打印出来，不算 FAIL，回执中写明结果）；
6. 最后打印 `SMOKE TASK_06 DONE`（有 SKIP 时同时打印 `SKIPPED: n`）。

## 7. 验收命令
```powershell
# 1. 容器内编译 + 单测（挂载整个项目，使一致性测试能读到 migration/dist/sqls.json）
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B clean package
# 2. 重建并启动后端
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_06.ps1
git status --short
```

## 8. 回执必须包含
- Maven 测试汇总行（`Tests run: X, Failures: 0, Errors: 0`）与一致性测试打印的“合法比例”；
- 两个冒烟脚本完整输出；
- Oracle 两个源的连通结果（成功 / 失败的 ORA 号 / SKIP）；
- **声明：本任务对 Oracle 只执行了 SELECT，写入数量为 0**；
- 提交号。

提交信息：`TASK-06: 只读SQL沙箱与Oracle只读数据源`
