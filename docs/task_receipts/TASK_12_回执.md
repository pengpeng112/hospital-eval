# TASK-12 回执：前端工程骨架、登录、7 大菜单布局与接口封装

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-16 白天（TASK-11 提交后投喂）/ 2026-11-16 23:40（Asia/Shanghai）
- 状态：已完成（build 成功、外链检查通过、联调自测两条通过）
- Git 提交号：见文末 `git log --oneline -n 1` 结果
- 基线：HEAD = 32a563f（TASK-11 回执提交号回填）
- 说明：任务指令第 6 条所写"粘贴到 docs/task_receipts/TASK_03_回执.md"为笔误，按 TASK_12.md 第 8 节与允许清单写入本文件 `TASK_12_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> node -v
v22.23.2                                   # 满足 v22.x

PS> npm config get registry
https://registry.npmmirror.com             # 记录在案

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1   （末行）
PASS: 5 科室数量 81 ≥ 10
PASS: 6 用户总数 68 ≥ 20
PASS: 7a smoke_user_05 已存在，跳过创建
PASS: 7b smoke_user_05 登录且 mustChangePwd=true
PASS: 8 操作日志存在 LOGIN 记录
SMOKE TASK_05 ALL PASS                     # 后端在运行
```

## 二、新建 / 修改的文件（均在 `hospital-eval-web/**` 与回执白名单内）

| 分类 | 文件 |
| :--- | :--- |
| 工程配置 | `package.json`（name=hospital-eval-web，scripts 保留 dev/build/preview）、`vite.config.js`（@ 别名、`/api→48080`、`/kkview→18012` 代理、chunkSizeWarningLimit 3000）、`index.html`（中文标题，无任何外链）、`.gitignore`（脚手架自带 node_modules/dist 忽略） |
| 基础设施 | `src/main.js`（Element Plus 中文语言包 + 全部图标 + Pinia + Router + 两份 css）、`src/App.vue`（仅 router-view）、`src/styles/index.css`（雅黑字体、#f5f7fa 背景、eval-card 白底 16px） |
| utils | `src/utils/request.js`（token 头、code==0 解包、401 清 token 跳 /login、blob 直通、`download(url,params,defaultName)` 取 Content-Disposition）、`src/utils/dict.js`（提取方式/复核状态/复核级别/动作/角色/SQL角色/汇总模式/触发类型/执行状态 中文与颜色映射）、`src/utils/escape.js`（`escapeHtml` 替换 `&<>"'`；`highlight` 先转义再 `<mark>` 包裹） |
| stores | `src/stores/user.js`（token/user/roles/mustChangePwd；login/logout/fetchMe/hasRole 任一命中/clearMustChangePwd） |
| api × 8 | `src/api/auth.js`(4)、`system.js`(7)、`algo.js`(11)、`batch.js`(4)、`metric.js`(11)、`result.js`(10)、`report.js`(18)、`dashboard.js`(6)——函数名逐个对齐任务书第 4 节表格，路径/入参严格按 04 §12 |
| 路由 | `src/router/index.js`（04 §13 七大菜单 + `/login`、`/change-password`、`/system/users`；每路由 `meta.title/roles`（summary 附 `mode`）；守卫：无 token→登录、fetchMe 补拉、mustChangePwd 锁改密页、角色不符跳 /workbench 提示"无权限访问"、默认首页 /workbench、兜底 404 重定向） |
| 布局 | `src/layout/EvalLayout.vue`（200px 可折叠菜单、顺序严格按 §13、"结果汇总/大屏展示"为二级菜单并按角色过滤；顶部系统名 + 姓名/科室/角色中文 + 下拉（修改密码、用户管理[仅 ADMIN]、退出登录）；面包屑取 meta.title） |
| 页面 | `src/views/login/index.vue`（居中卡片、系统全称标题、登录后 mustChangePwd→改密页）、`src/views/login/ChangePassword.vue`（≥8 位含字母数字前端校验、两次一致性、成功后登出回登录页）、`src/views/system/users.vue`（分页/关键字/角色筛选、新增、编辑（姓名/科室/角色多选/状态）、重置密码二次确认——本任务唯一完整业务页） |
| 占位页 × 8 | `workbench/index.vue`(TASK-16)、`metric/index.vue`(TASK-13)、`algorithm/index.vue`(TASK-14)、`department/index.vue`(TASK-13)、`manual/index.vue`(TASK-16)、`summary/index.vue`(TASK-15，读 `meta.mode` 区分当期/历史文案)、`screen/dashboard.vue` 与 `screen/reconcile.vue`(TASK-17)，均经 `src/components/Placeholder.vue` 显示"本页面将在 TASK-XX 实现" |
| 删除的脚手架样板 | `src/style.css`、`src/components/HelloWorld.vue`、`src/assets/vue.svg`、`public/vite.svg`、`README.md`（脚手架默认文件，App.vue 已重写不再引用） |

## 三、验收命令与真实输出

### 3.1 依赖安装（契约 §2 版本核对）

`npm create vite@5 hospital-eval-web -- --template vue` + `npm install` + 业务依赖安装。（npm 源 cdn 当晚极慢：首装卡死一次，终止后带 `--fetch-retries/--fetch-timeout` 重试成功。）

**契约版本钉正**：首次 `npm install element-plus ... pinia ... echarts` 得到 pinia@4.1.0、echarts@6.1.0，**与 04 §2 固定版本清单（pinia@^2、echarts@^5）不符**，已执行 `npm install "pinia@^2" "echarts@^5"` 钉正。最终 `package.json` 依赖版本：vue ^3.5、vite ^5.4（devDep）、element-plus ^2.14、@element-plus/icons-vue ^2.3、pinia **^2.3**、vue-router ^4.6、axios ^1.21、echarts **^5.6**、vue-codemirror ^6、codemirror ^6、@codemirror/lang-sql/merge/state/view、无 TypeScript、无 vue-tsc。

### 3.2 `npm run build` 输出最后 15 行

```text
dist/assets/index-BhPf4IW-.css             364.65 kB │ gzip:  48.86 kB
dist/assets/workbench-DHDZOhxu.js            0.19 kB │ gzip:   0.17 kB
dist/assets/metric-sUGizjIB.js               0.19 kB │ gzip:   0.17 kB
dist/assets/algorithm-CGT8pC_m.js            0.19 kB │ gzip:   0.17 kB
dist/assets/department-CB2mFcFn.js           0.19 kB │ gzip:   0.17 kB
dist/assets/manual-B_qXeuD6.js               0.19 kB │ gzip:   0.17 kB
dist/assets/dashboard-CCJjUI2K.js            0.19 kB │ gzip:   0.18 kB
dist/assets/reconcile-DR9JmoXZ.js            0.19 kB │ gzip:   0.18 kB
dist/assets/summary-D-a_w6wD.js              0.29 kB │ gzip:   0.28 kB
dist/assets/Placeholder-v1FzsYjf.js          0.31 kB │ gzip:   0.29 kB
dist/assets/login-CEm94kgZ.js                1.70 kB │ gzip:   0.98 kB
dist/assets/ChangePassword-CCvPNXO7.js       1.97 kB │ gzip:   1.07 kB
dist/assets/users-DHcgatH3.js                6.00 kB │ gzip:   2.45 kB
dist/assets/index-C0xJ1kC8.js            1,287.02 kB │ gzip: 417.62 kB
✓ built in 7.84s
```

（`built in` 出现 → 构建成功；共 20 个产物文件，各业务视图已按路由分包。）

### 3.3 外链检查

```powershell
PS> Select-String -Path dist/index.html -Pattern "https?://"
（无任何匹配行）
外链检查：无匹配（通过）
```

### 3.4 联调自测（`npm run dev` 后 PowerShell 请求）

```text
1) curl http://localhost:5173/api/health
{"status":"UP","db":"UP"}                          # /api 代理生效

2) POST http://localhost:5173/api/auth/login（admin，密码从 deploy/.env 运行时读取、未打印）
code: 0 msg: ok
mustChangePwd: False user.realName: 系统管理员 roles: ['ADMIN'] tokenLen: 36
```

（自测完成后 dev server 已停止。）

## 四、src/api/*.js 导出函数数量

| 文件 | 数量 | 函数 |
| :--- | :--- | :--- |
| `auth.js` | 4 | login、logout、me、changePassword |
| `system.js` | 7 | listDepts、createDept、listUsers、createUser、updateUser、resetPassword、listOperLogs |
| `algo.js` | 11 | listDatasources、pingDatasource、validateSql、trialSql、listSqls、getSql、createSql、updateSql、toggleSql、listSqlHistory、getSqlHis |
| `batch.js` | 4 | runBatch、getBatch、listBatchTasks、listBatches |
| `metric.js` | 11 | getMetricTree、listMetrics、getMetric、updateMetric、listScopes、createScope、updateScope、funcDeptSummary、exportScopes、importScopes、syncDeptCodes |
| `result.js` | 10 | listSummary、listMetricScopes、getMetricPanel、listResultBatches、listDetail、exportDetail、get360Url、listEmrDocuments、getEmrDocumentContent、exportMaster |
| `report.js` | 18 | listReportTasks、saveManualValue、listHybridPool、hybridSelect、uploadEvidence、listEvidence、getEvidencePreviewUrl、deleteEvidence、auditSubmit、auditApprove、auditReject、auditArchive、auditUnlock、listAuditLogs、workbenchOverview、workbenchTodos、workbenchDeptProgress、workbenchAlerts |
| `dashboard.js` | 6 | dashboardOverview、downloadReconcileTemplate、importReconcile、listReconcileImports、listReconcileRows、exportReconcile |
| 合计 | **71** | 与任务书第 4 节表格逐函数一致 |

## 五、安全红线自查

- Oracle：本任务零连接；只连本机后端 `http://localhost:48080`。
- 凭据：联调登录密码经脚本运行时从 `deploy/.env` 读取，未打印、未写进任何文件；仓库无 token/密码。
- `node_modules/`、`dist/` 均被忽略（根 `.gitignore` 第 47~48 行 + 工程内 `.gitignore`），提交前 `git status` 复核不含二者。
- 未使用 TypeScript / vue-tsc；`index.html` 无外网 CDN；未引入 04 §2 之外的 UI 库。

## 六、待确认事项（不阻塞，后续任务注意）

1. **echarts@^5.6**：npm 提示 echarts 6 已发布，本次按契约钉在 ^5；TASK-17 大屏开发时若需升级到 6，须先修订 04 §2 版本清单。
2. **登录页无验证码**：04 §12.1 未定义验证码接口，按要求只做用户名+密码；若医院等保测评要求验证码，需主控补充契约。
3. 任务指令"验收输出粘贴到 TASK_03_回执.md"为笔误，本回执写入 `TASK_12_回执.md`（已在本回执开头说明）。

## 七、Git 提交

提交信息：`TASK-12: 前端骨架、登录与7大菜单布局`

```text
5c2d72f TASK-12: 前端骨架、登录与7大菜单布局
```
