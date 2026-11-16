# TASK-14 回执：前端【算法管理】页面（SQL 编辑、校验试跑、版本对比、覆盖评审数据）

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始 / 结束时间：2026-11-17 早晨（Asia/Shanghai）
- 状态：已完成（build 成功、Monaco/CDN 检查无匹配、SQL 全链路自测 10 步全 OK；试跑环节因 Oracle 白名单未放行按规则 SKIP）
- Git 提交号：见文末
- 基线：HEAD = 644730b（TASK-13 回执提交号回填）
- 说明：任务指令第 6 条"粘贴到 docs/task_receipts/TASK_03_回执.md"为既有笔误，按 TASK_14.md 第 8 节与允许清单写入本文件 `TASK_14_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1   （末行）
PASS: 3 DELETE 被沙箱拦截 code=400
PASS: 4 修改成功 versionNo=2==2
PASS: 5a history 共 2 条 == 2
PASS: 5b oldSqlAnnotated 含 1 AS 分子
PASS: 5c newSqlAnnotated 含 2 AS 分子
PASS: 6 原样重复提交返回 400 未检测到修改
PASS: 7 停用测试 SQL 成功
SMOKE TASK_09 ALL PASS

PS> cd hospital-eval-web; npm run build   （TASK_14.md 前置检查跑于改动前）
✓ built in 11.27s
```

## 二、新建 / 修改的文件（4 项，均在白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `src/components/SqlEditor.vue` | vue-codemirror `Codemirror` + `sql({ dialect: PLSQL })`；props `modelValue/readonly/height(默认420px)`，emit `update:modelValue`；basicSetup 自带行号与括号匹配；中文 UTF-8 正常；**前端不改写 SQL**，编辑原文直接上抛 |
| `src/components/SqlDiffView.vue` | `@codemirror/merge` 的 `MergeView`：左侧旧 SQL / 右侧新 SQL，两侧 `EditorView.editable.of(false)` 只读、开启 lineWrapping 与差异 gutter；props `oldText/newText`，props 变化或组件卸载时 `destroy()` |
| `src/components/MetricPicker.vue` | 远程搜索 `listMetrics({keyword, size:20})`，选项"编号 代号 名称"，v-model 绑指标 id，`@select` emit 完整指标对象 |
| `src/views/algorithm/index.vue` | 完整替换占位页（步骤 4 全量实现，见下） |

未改动任何 `src/api/*.js`（algo.js 11 个函数签名与实现均无需变更；`updateSql` 本就是 PUT）。

## 三、页面实现要点（步骤 4 逐条）

1. **顶部条件栏**：MetricPicker；选中后 `getMetric(id)` 填充科室实例下拉（过滤免统实例，首项"全部科室"= 不传 scopeId）；右侧数据源标签（`listDatasources()` 的 key+host+configured tooltip；仅 ADMIN 可点击触发 `pingDatasource`，结果绿✓耗时/红✗回显在标签上）；右上角"覆盖评审数据"按钮仅 ADMIN。
2. **左侧 SQL 列表（360px）**：`listSqls({metricId, scopeId})` 按 deptName 分组；每条含角色中文（复用 dict.js SQL_ROLE）、时段 `yyyyMM~yyyyMM`、数据源、v版本号、启用/停用、校验状态（lastVerifyStatus 1=通过绿 / 2=报错红 / null·0=未校验灰，语义取自后端 AlgoSandboxController 回写规则）、dsFlag/periodFlag 有值橙色标签；点击选中加载详情。
3. **右侧编辑区**：元信息 el-descriptions（sourceRef / lastModifyUser / lastModifyReason / updateTime）；SqlEditor 编辑 sqlAnnotated（非 ADMIN 只读）；按钮：校验（结果显示编辑器下方，通过绿 alert / 失败红列 errors / 警告黄列 warnings）、试跑前20行（ADMIN；结果弹窗表格列名+行数+耗时，**顶部醒目"含患者信息，仅供核验，请勿截图外传"**）、保存（弹窗必填修改类型 口径升级/算法Bug修复 + 原因 ≥5 字 → updateSql）、新增 SQL（弹窗角色/数据源/起止年月/SQL/原因 → createSql；仅选择了具体科室实例时可用，因 SQL 挂载科室实例）、启用/停用（必填原因 → toggleSql）、历史版本。
4. **历史版本抽屉**：`listSqlHistory(id)` el-timeline（版本号、修改类型、原因、修改人、时间），点击某版 → `getSqlHis(hisId)` → SqlDiffView 左右对比。
5. **覆盖评审数据（ADMIN）弹窗**：范围单选（当前科室实例 / 当前指标全部科室；"当前科室实例"在顶部为全部科室时禁用）、年份多选 2022~2025 默认 [2025]、版本说明选填、任务书原文提示语、确认后 `runBatch` → 每 5 秒 `getBatch(batchId)` 轮询进度条（SUCCESS+FAILED+SKIPPED/总数）→ 完成后 `listBatchTasks({batchId, status:'FAILED'})` 显示科室/角色/错误信息；**关闭弹窗（watch batch.visible）与组件卸载（onBeforeUnmount）均 clearInterval 停止轮询**。

## 四、验收命令与真实输出

### 4.1 构建输出最后 10 行（改动后正式构建）

```text
dist/assets/Placeholder-B7Px3qUS.js         0.31 kB │ gzip:   0.29 kB
dist/assets/system-DzB3LurQ.js              0.44 kB │ gzip:   0.21 kB
dist/assets/metric-BBO1UjWK.js              0.69 kB │ gzip:   0.33 kB
dist/assets/DeptSelect-BCWEp1mU.js          1.41 kB │ gzip:   0.76 kB
dist/assets/login-ElcMj428.js               1.70 kB │ gzip:   0.98 kB
dist/assets/ChangePassword-CwAhf_Xn.js      1.97 kB │ gzip:   1.07 kB
dist/assets/users-RtoyB52q.js               5.77 kB │ gzip:   2.39 kB
dist/assets/metric-C3N9nn4r.js             10.91 kB │ gzip:   4.06 kB
dist/assets/department-RT3u2vrK.js         12.76 kB │ gzip:   4.75 kB
dist/assets/algorithm-CL7cqXWp.js         481.98 kB │ gzip: 159.32 kB
（其后 index-kGjVtJ0K.js 1,288.11 kB，✓ built in 10.41s）
```

（algorithm 分包 481.98 kB 为 CodeMirror 编辑器与 merge 视图体积，符合预期。）

### 4.2 Monaco / CDN 检查

```powershell
# 任务书原命令在 PowerShell 5.1 下报错：Select-String 无 -Recurse 参数（任务书笔误），
# 改用语义等价写法：Get-ChildItem -Recurse | Select-String
PS> Get-ChildItem -Path hospital-eval-web/src -Include *.vue,*.js -Recurse | Select-String -Pattern "monaco|cdn\.|unpkg|jsdelivr"
（无任何匹配输出）
--- monaco/cdn check done（上方无输出=通过） ---
```

### 4.3 git status --short（提交前）

```text
 M hospital-eval-web/src/views/algorithm/index.vue
?? hospital-eval-web/src/components/MetricPicker.vue
?? hospital-eval-web/src/components/SqlDiffView.vue
?? hospital-eval-web/src/components/SqlEditor.vue
```

## 五、自测记录（npm run dev，经 5173 代理以 admin 实调，页面接口链路逐步复现）

测试位置沿用 TASK-09 冒烟遗留的测试指标（metricId=251 / scopeId=95，其 6 条遗留 SQL 均已停用），新建专属测试 SQL 走完整链路：

| # | 链路步骤（页面动作） | 接口 | 实测结果 |
| :--- | :--- | :--- | :--- |
| 0 | 页面加载 | listDatasources / listSqls | code=0；HIS_RO·ODS_RO 均 configured；遗留 SQL 6 条 |
| 1 | 新增 SQL 弹窗提交 | createSql | code=0，sqlId=2967，version=1 |
| 2 | 点击选中 | getSql | code=0，含 sqlAnnotated，v1 |
| 3 | 校验 | validateSql | **valid=true**，errors=[]，warnings=["JSqlParser 无法解析，已按关键字规则校验"]（中文别名"分子"致 JSqlParser 降级，真实回显） |
| 4 | 试跑前20行 | trialSql | **SKIP：Oracle 白名单未放行**（`SQLRecoverableException (vendorCode=17002)`，与 TASK_09 冒烟口径一致）；前端该场景走拦截器错误提示，不弹结果表 |
| 5 | 保存（口径升级+原因） | updateSql(PUT) | code=0，**versionNo 1→2** |
| 6 | 历史版本对比 | listSqlHistory / getSqlHis | history 共 2 条；v1（新增）new 含 `1 AS 分子`；v2（口径升级）old 含 `1 AS 分子`、new 含 `2 AS 分子` —— SqlDiffView 渲染数据正确 |
| 7 | 停用 | toggleSql | code=0，**版本不递增**（仍 v2），isActive=false（测试 SQL 留痕停用，与 TASK-09 冒烟同口径） |

共 10 步接口断言全部 OK（自测后 dev server 已停止）。**未贴任何试跑患者数据**（试跑因白名单未执行）。

## 六、安全红线自查

- Oracle：本任务前端零直连；后端试跑因白名单未放行未触达业务库，写入数量 **0**。
- 前端不改写/拼接 SQL：SqlEditor 仅透传原文；试跑/保存均把用户编辑原文提交后端。
- 试跑与跑批按钮仅 ADMIN 渲染（`v-if="isAdmin"`）；后端 ping/trial/batch 接口本身也有权注拦截。
- 无 Monaco、无任何 CDN/unpkg/jsdelivr 引用；无写死指标/SQL 假数据。
- 测试 SQL 留痕停用（is_active=false），未启用污染真实跑批范围；未打印任何密码。

## 七、待确认事项（不阻塞）

1. **任务书验收命令笔误**：`Select-String -Path ... -Recurse` 在 PowerShell 5.1 中不可用（该 cmdlet 无 `-Recurse` 参数，报"找不到与参数名称匹配的参数"），已用语义等价的 `Get-ChildItem -Recurse | Select-String` 完成同一检查，建议主控勘误 TASK_14.md 第 7 节。
2. **覆盖跑批弹窗未实弹**：runBatch → 轮询 → 失败列表链路已按 §12.3 契约实现（含关弹窗停轮询、总数为 0 防除零、失败子任务表格），但因 Oracle 白名单未放行，实际跑批只会产生全 FAILED 批次，未做实弹触发；建议待白名单开放后（生产 10.10.8.75 内网直连可用）由主控或运维侧验证一轮。
3. **试跑警告降级提示**：validateSql 对含中文别名的 SQL 返回"JSqlParser 无法解析，已按关键字规则校验"警告——前端如实黄条展示，属后端既有行为，非本任务引入。

## 八、Git 提交

提交信息：`TASK-14: 算法管理页面`

```text
1d989d1 TASK-14: 算法管理页面
```
