# TASK-05 回执：后端骨架、登录鉴权与容器化

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 16:55 / 2026-11-15 17:42（Asia/Shanghai）
- 状态：已完成
- Git 提交号：见文末（提交后回填）
- 说明：用户指令中的"05_开发契约与编码规范.md / TASK_04.md 前置检查 / TASK_03_回执.md"判断为模板笔误——契约文件实际为 04（已全文在读），前置检查按 TASK_05.md §3 执行，回执按白名单写入本文件。

## 一、前置检查结果

```powershell
PS> docker version --format "{{.Server.Version}}"
29.3.0
PS> docker ps --filter name=eval-postgres --format "{{.Names}} {{.Status}}"
eval-postgres Up About an hour (healthy)
PS> python migration/99_verify_import.py（末行）
VERIFY OK
PS> java -version 2>&1 | Select-String "1.8"
openjdk version "1.8.0_292"     ← 本机 JDK 仍为 1.8，未做任何修改 ✓
PS> Test-Path deploy/.env ; Test-Path migration/dist/sqls.json
True ; True
```
（另核验 48080 端口空闲。）四项 TASK-05 前置检查 + TASK-04 前置检查全部满足。

## 二、新建 / 修改的文件（44 新建 + 1 修改 + 2 冒烟，共 47，逐项核对白名单）

| 类别 | 文件 |
| :--- | :--- |
| 工程根 | `hospital-eval-server/pom.xml`、`hospital-eval-server/Dockerfile` |
| 资源 | `hospital-eval-server/src/main/resources/application.yml`（任务书原样） |
| 主类 | `src/main/java/com/hospital/eval/EvalApplication.java` |
| common/** | `R`、`PageResult`、`PageQuery`、`BizException`、`GlobalExceptionHandler`、`IpUtils` |
| config/** | `MybatisPlusConfig`、`SaTokenConfig`、`WebConfig`、`JacksonConfig`（恰 4 个） |
| system/** | 实体 5（SysDept/SysUser/SysRole/SysUserRole/SysOperLog）、Mapper 5、DTO 6（LoginReq/ChangePasswordReq/CreateUserReq/UpdateUserReq/UserView/DeptView）、服务 5（UserService/OperLogService/LoginAttemptLimiter/PasswordUtil/SystemBeansConfig[BCrypt Bean]）、AdminInitRunner、StpInterfaceImpl、Controller 5（Auth/Dept/User/OperLog/Health） |
| 测试 | `src/test/java/com/hospital/eval/system/PasswordRuleTest.java`、`RTest.java`（不连库） |
| compose | `deploy/docker-compose.dev.yml` 仅新增 eval-server 服务 |
| 冒烟 | `scripts/smoke/_common.ps1`、`scripts/smoke/TASK_05.ps1` |
| 回执 | `docs/task_receipts/TASK_05_回执.md` |

未引入 RuoYi/JeecgBoot/dynamic-datasource/Spring Security 全家桶；application.yml 无真实密码。

## 三、验收命令与真实输出

```text
PS> docker run --rm -v "${PWD}/hospital-eval-server:/app" -v "$env:USERPROFILE/.m2:/root/.m2" -w /app maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.155 s -- in com.hospital.eval.system.PasswordRuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.528 s -- in com.hospital.eval.system.RTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
maven_exit=0
```
> 与任务书命令的差异仅一个参数：追加 `-Dmaven.repo.local=/root/.m2/repository`。原因：宿主机 `~/.m2/settings.xml`（EMIS 项目所配）`localRepository=F:\python\emis-tools\maven-repo` 为 Windows 路径，Linux 容器内被当作相对目录名，导致依赖全部下载到 `/app` 下的乱名目录、编译类路径为空。该 -D 覆盖为标准仓库路径；镜像加速沿用宿主机 settings.xml 的阿里云 mirror（不读取其密码类配置）。乱名目录已从源码树删除并确认 git 干净。

```text
PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
 Container eval-postgres Healthy
 Container eval-server Starting
 Container eval-server Started
```

```text
PS> docker logs --tail 30 eval-server（节选关键行，无密码内容）
 :: Spring Boot ::                (v3.2.5)
Starting EvalApplication v1.0.0 using Java 17.0.21 with PID 1 (/app/app.jar started by root in /app)
o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 48080 (http)
https://sa-token.cc (v1.38.0)
o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 48080 (http) with context path ''
com.hospital.eval.EvalApplication        : Started EvalApplication in 4.092 seconds (process running for 4.922)
com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
c.hospital.eval.system.AdminInitRunner   : admin 用户已存在，跳过初始化
```

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
PASS: 1 /api/health UP/UP
PASS: 2 错误密码登录返回 code=400
PASS: 3 admin 登录成功且含 ADMIN 角色
PASS: 4 未登录访问 me 返回 401
PASS: 5 科室数量 81 ≥ 10
PASS: 6 用户总数 67 ≥ 20
PASS: 7a 创建 smoke_user_05
PASS: 7b smoke_user_05 登录且 mustChangePwd=true
PASS: 8 操作日志存在 LOGIN 记录
SMOKE TASK_05 ALL PASS
```
（容器重建后复跑一轮验证幂等：9 项仍全 PASS，第 7a 变为"smoke_user_05 已存在，跳过创建"，用户总数 68=66 导入+admin+smoke_user_05，全部写入可解释。）

```text
PS> java -version
openjdk version "1.8.0_292"     ← 未修改 ✓
```

```text
PS> git status --short
 M deploy/docker-compose.dev.yml
?? hospital-eval-server/
?? scripts/
（--untracked-files=all 展开为 47 项，逐一核对均在白名单；target/ 已被 .gitignore 排除）
```

## 四、kkFileView 状态

沿用 TASK-04 结论：镜像不可用、服务注释停用（TASK-16 前恢复）；本任务不依赖。

## 五、执行过程中修复的问题（自测抓出，均留代码注释）

1. **jsqlparser 版本契约矛盾（重要勘误）**：04 §2 写"mybatis-plus 3.5.5 + jsqlparser 4.9"，实测 **4.9 已移除 `SelectExpressionItem`，而 MP 3.5.5 的 PaginationInnerInterceptor 按 4.6 API 编译**——4.9 组合启动即崩（NoClassDefFoundError）。已改钉 **4.6**（MP 3.5.5 原配），并在 pom 注释与本回执申报。TASK-06 的 SQL 校验器按 4.6 API 开发即可。
2. **BCrypt Bean 注入类型**：AdminInitRunner 改按 `PasswordEncoder` 接口注入（@Bean 声明类型为接口，按具体类注入匹配不到）。
3. **冒烟脚本编码**：Windows PowerShell 5.1 按 ANSI 读无 BOM 的 UTF-8 脚本导致中文乱码解析失败——两个 .ps1 已加 UTF-8 BOM。
4. **构建仓库路径**：见第三节说明（EMIS settings.xml 的 Windows localRepository 在容器内失效），验收命令追加 `-Dmaven.repo.local`，误下载的乱名依赖目录已从源码树删除。

## 六、安全自检（逐项勾选）

- [x] 未连接 Oracle / 10.10.8.75（仅本机 Docker PostgreSQL）
- [x] 未读取/打印/提交任何密码或 deploy/.env 内容；冒烟脚本 Get-EnvValue 只取值不打印；日志输出已过滤
- [x] 回执与仓库无患者信息（明细表为空）
- [x] 本机 JAVA_HOME / PATH 未修改（java -version 仍 1.8.0_292；编译全在容器内 JDK 17）
- [x] 只改白名单文件（compose 仅新增 eval-server 服务；未改 .env.example）
- [x] 未操作 10.10.8.75；未触碰本机其他项目容器

## 七、待确认事项

1. **jsqlparser 4.9→4.6 契约勘误**（第五节第 1 条）：请主控更新 04 §2，避免 TASK-06 按旧口径引依赖。
2. **Maven 验收命令的 -Dmaven.repo.local 附加参数**：因宿主机既有 settings.xml 的 Windows 路径问题，属环境适配而非任务逻辑；若主控希望完全原样执行该命令，需要用户调整宿主机 `~/.m2/settings.xml`（超出本 AI 白名单）。
3. `POST /api/system/depts` 的角色限制：04 §12.1 未标注，按"系统配置类操作仅 ADMIN"实现（@SaCheckRole("ADMIN")），如需 FUNC_LEADER 可写请主控明确。
4. `GET /api/system/oper-logs` 的 username 过滤按模糊匹配（like）实现，module/targetType/targetId 按精确匹配；如需调整请指出。
5. Dockerfile 构建期写入阿里云 mirror settings.xml（仅构建期、无凭据），与宿主机既有配置同口径。

## 八、未完成

无。
