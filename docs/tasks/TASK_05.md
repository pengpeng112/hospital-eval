# TASK-05：后端工程骨架、登录鉴权、用户科室管理与容器化

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 3 · 第 1 次 |
| 依赖 | TASK-04（本机 PostgreSQL 已有数据） |
| 预计改动 | 25~35 个 Java 文件 + pom + Dockerfile + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；**不连** Oracle、**不连** 10.10.8.75 |

## 1. 目标
建立 `hospital-eval-server` 单模块 Spring Boot 3.2 工程，完成统一返回体/异常、Sa-Token 登录、用户/科室/角色/操作日志接口、健康检查，并能以 Docker 容器方式在本机启动。

## 2. 开工前必读
1. `AGENTS.md` 第三节第 5 条（**不许改本机 JAVA_HOME**）
2. `04_开发契约与编码规范.md` 第 1、2、3、4、5.3、11、12.1、14 节
3. 本文件

## 3. 前置检查
```powershell
docker version                              # 有 Server 输出
docker ps --filter name=eval-postgres       # eval-postgres 在运行
python migration/99_verify_import.py        # 最后一行 VERIFY OK
java -version 2>&1 | Select-String "1.8"    # 记录本机 JDK 仍是 1.8（回执里贴出来，证明没改）
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/pom.xml`
- `hospital-eval-server/Dockerfile`
- `hospital-eval-server/src/main/resources/application.yml`
- `hospital-eval-server/src/main/java/com/hospital/eval/EvalApplication.java`
- `hospital-eval-server/src/main/java/com/hospital/eval/common/**`
- `hospital-eval-server/src/main/java/com/hospital/eval/config/**`（本任务只建 `MybatisPlusConfig`、`SaTokenConfig`、`WebConfig`、`JacksonConfig`）
- `hospital-eval-server/src/main/java/com/hospital/eval/system/**`
- `hospital-eval-server/src/test/java/com/hospital/eval/system/**`
- `deploy/docker-compose.dev.yml`（只允许**新增** `eval-server` 服务）
- `deploy/.env.example`（只允许追加变量名）
- `scripts/smoke/_common.ps1`、`scripts/smoke/TASK_05.ps1`
- `docs/task_receipts/TASK_05_回执.md`

## 5. 禁止事项
- 禁止引入 RuoYi / JeecgBoot / dynamic-datasource / Spring Security 全家桶（只允许 `spring-security-crypto`）。
- 禁止在 `application.yml` 写真实密码；禁止写默认管理员密码。
- 禁止修改本机 `JAVA_HOME`、`PATH`；禁止安装本机 JDK 17。

## 6. 操作步骤

### 步骤 1：`pom.xml`
- `groupId=com.hospital`，`artifactId=hospital-eval-server`，`version=1.0.0`，`packaging=jar`，`java.version=17`，`project.build.sourceEncoding=UTF-8`。
- parent `spring-boot-starter-parent:3.2.5`。
- 依赖（版本见 04 §2）：`spring-boot-starter-web`、`spring-boot-starter-validation`、`spring-boot-starter-jdbc`、`mybatis-plus-spring-boot3-starter`、`postgresql`、`ojdbc8`、`orai18n`、`jsqlparser`、`sa-token-spring-boot3-starter`、`spring-security-crypto`、`poi-ooxml`、`lombok`（optional）、`spring-boot-starter-test`（test）。
- 打包插件 `spring-boot-maven-plugin`，`finalName=hospital-eval-server`。

### 步骤 2：`application.yml`
```yaml
server:
  port: 48080
  servlet:
    encoding: { charset: UTF-8, force: true }
spring:
  application.name: hospital-eval-server
  datasource:
    url: jdbc:postgresql://${EVAL_PG_HOST:127.0.0.1}:${EVAL_PG_INNER_PORT:5432}/${POSTGRES_DB:hospital_eval}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
  servlet.multipart: { max-file-size: 100MB, max-request-size: 110MB }
  jackson: { time-zone: Asia/Shanghai, date-format: "yyyy-MM-dd HH:mm:ss" }
mybatis-plus:
  configuration: { map-underscore-to-camel-case: true }
  global-config.db-config.id-type: auto
sa-token:
  token-name: Authorization
  token-prefix: Bearer
  timeout: 43200
  is-concurrent: true
  is-log: false
eval:
  init-admin-password: ${EVAL_INIT_ADMIN_PASSWORD:}
  init-user-password: ${EVAL_INIT_USER_PASSWORD:}
  jwt-secret: ${JWT_SECRET:}
  upload-dir: ${EVAL_UPLOAD_DIR:/data/uploads}
```

### 步骤 3：`common` 包
- `R<T>`（`code/msg/data`，静态方法 `ok(data)`、`ok()`、`fail(code,msg)`）；
- `PageResult<T>`（`total/list`）、`PageQuery`（`page` 默认 1、`size` 默认 20、最大 200）；
- `BizException(code, msg)`；
- `GlobalExceptionHandler`：`BizException`→对应 code；`NotLoginException`→401；`NotRoleException`/`NotPermissionException`→403；`MethodArgumentNotValidException`→400 并返回第一个字段错误；其他异常→500，`msg="系统错误"`，日志记录堆栈；
- `IpUtils.getClientIp(request)`（取 `X-Forwarded-For` 第一个，否则 `remoteAddr`）。

### 步骤 4：`system` 包
- 实体 + Mapper（MyBatis-Plus `BaseMapper`）：`SysDept`、`SysUser`、`SysRole`、`SysUserRole`、`SysOperLog`，字段与 04 §5.2 一致。
- `AdminInitRunner implements ApplicationRunner`：启动时若不存在 `admin` 用户：
  - `eval.init-admin-password` 为空 → 抛异常终止启动，日志写“请在 deploy/.env 配置 EVAL_INIT_ADMIN_PASSWORD”；
  - 否则创建 `admin`（真实姓名“系统管理员”、科室 `信息科`、角色 `ADMIN`、`must_change_pwd=false`）。
- `StpInterfaceImpl implements StpInterface`：`getRoleList` 返回该用户的 `role_code` 列表；`getPermissionList` 返回空列表。
- `AuthController`：按 04 §12.1 实现 login / logout / me / change-password：
  - 登录失败统一返回 `400 用户名或密码错误`；用户 `status=0` 返回 `400 账号已停用`；
  - 同一用户名 5 分钟内连续失败 5 次 → 锁定 10 分钟（内存 Map 计数即可）；
  - 登录成功更新 `last_login_time`，写 `sys_oper_log`（module=`AUTH`，action=`LOGIN`）。
- `DeptController`、`UserController`、`OperLogController`：按 04 §12.1（`oper-logs` 支持 `module`、`username`、`targetType`、`targetId` 四个可选过滤条件，按 `create_time` 倒序）；`/api/system/users` 的增删改与重置密码仅 `ADMIN`（`@SaCheckRole("ADMIN")`）。
- `OperLogService.log(module, action, targetType, targetId, detailMap)`：供后续所有模块调用（自动取当前登录用户和 IP）。
- `HealthController`：`/api/health` 执行 `SELECT 1` 检测 PostgreSQL，返回 `{status:"UP", db:"UP"}`，数据库失败时 `db:"DOWN"`。
- `SaTokenConfig`：注册拦截器，排除 `/api/auth/login`、`/api/health`、`/api/evidence/*/file`；其余 `/api/**` 需要登录。

### 步骤 5：单元测试（`src/test/java/com/hospital/eval/system/`）
- `PasswordRuleTest`：新密码校验（≥8 位且含字母和数字）正反用例；
- `RTest`：`R.ok` / `R.fail` 序列化结构正确；
- 测试**不得**依赖真实数据库（不要用 `@SpringBootTest` 连库）。

### 步骤 6：`Dockerfile`（多阶段）
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /app/target/hospital-eval-server.jar app.jar
EXPOSE 48080
ENTRYPOINT ["java","-Xms512m","-Xmx2g","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
```

### 步骤 7：`deploy/docker-compose.dev.yml` 新增服务
```yaml
  eval-server:
    build: ../hospital-eval-server
    container_name: eval-server
    env_file: .env
    environment:
      EVAL_PG_HOST: eval-postgres
      EVAL_PG_INNER_PORT: "5432"
      EVAL_UPLOAD_DIR: /data/uploads
    ports:
      - "127.0.0.1:${EVAL_API_PORT}:48080"
    volumes:
      - ./data/uploads:/data/uploads
    depends_on:
      eval-postgres: { condition: service_healthy }
```

### 步骤 8：冒烟脚本
- `scripts/smoke/_common.ps1` 提供：
  - `Get-EnvValue $name`：从 `deploy/.env` 读取变量值（**不打印**）；
  - `$Global:Base = if ($env:EVAL_SMOKE_BASE) { $env:EVAL_SMOKE_BASE } else { "http://127.0.0.1:48080" }`（TASK-19 用环境变量指向生产地址）；
  - `Invoke-Api -Method -Path -Body -Token`：返回解析后的 JSON，`code != 0` 时抛错；
  - `Login-Admin`：用 `admin` + `EVAL_INIT_ADMIN_PASSWORD` 登录返回 token；
  - `Assert-True $cond $message`：失败时 `Write-Host "FAIL: $message"` 并 `exit 1`，成功打印 `PASS: $message`。
- `scripts/smoke/TASK_05.ps1` 依次检查并打印 PASS/FAIL：
  1. `/api/health` → `status == "UP"` 且 `db == "UP"`；
  2. 错误密码登录 → 返回 `code == 400`；
  3. admin 登录成功，`roles` 含 `ADMIN`；
  4. 不带 token 调 `/api/auth/me` → HTTP 401 或 `code == 401`；
  5. `/api/system/depts` 返回数量 ≥ 10；
  6. `/api/system/users?page=1&size=5` 的 `total` ≥ 20（TASK-04 导入的联络员）；
  7. 新建一个测试用户 `smoke_user_05`（已存在则跳过），用 `EVAL_INIT_USER_PASSWORD` 登录成功且 `mustChangePwd == true`；
  8. `/api/system/oper-logs` 中存在 `LOGIN` 记录。
  - 最后打印 `SMOKE TASK_05 ALL PASS`。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}/hospital-eval-server:/app" -v "$env:USERPROFILE/.m2:/root/.m2" -w /app maven:3.9-eclipse-temurin-17 mvn -B -q clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
docker logs --tail 50 eval-server
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
java -version
git status --short
```
**预期**：Maven 退出码 0 且测试通过；日志中有 `Started EvalApplication`；冒烟最后一行 `SMOKE TASK_05 ALL PASS`；本机 `java -version` 仍是 1.8。

## 8. 回执必须包含
- 以上命令真实输出（日志只贴最后 30 行，去掉任何密码相关内容）；
- 新建/修改的文件清单；
- 提交号。

提交信息：`TASK-05: 后端骨架、登录鉴权与容器化`
