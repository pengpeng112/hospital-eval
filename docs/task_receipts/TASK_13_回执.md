# TASK-13 回执：前端【指标维护】与【部门管理】页面

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始 / 结束时间：2026-11-17（Asia/Shanghai）
- 状态：已完成（build 成功、TODO/mock 检查无输出、dev 自测 13 个接口调用全部 code=0）
- Git 提交号：见文末
- 基线：HEAD = da43a93（TASK-12 回执提交号回填）
- 说明：任务指令第 6 条"粘贴到 docs/task_receipts/TASK_03_回执.md"仍为 TASK-12 指令的同款笔误，按 TASK_13.md 第 8 节与允许清单写入本文件 `TASK_13_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1   （末行）
PASS: 4d 改回原值成功
PASS: 5 scope/list 返回 5 ≥ 1
PASS: 6 func-dept-summary 返回 1 ≥ 1
PASS: 7a 导出文件 107652 字节 > 5KB
7b 导入 updated=1876 errors=0（其他错误=0）
PASS: 7b 原样导出再导入 errors = 0
SKIP: HIS_RO 未连通 (SQLRecoverableException (vendorCode=17002))
SMOKE TASK_08 ALL PASS

PS> cd hospital-eval-web; npm run build
✓ built in 10.44s
```

## 二、新建 / 修改的文件（5 项，均在白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `src/components/ExtractModeTag.vue` | props `mode`/`applicable`；`applicable === false` 优先灰色"不适用"，否则按 04 §13 四色映射（SYS 绿 / MANUAL 橙 / HYBRID 蓝 / PROV 灰，复用 `utils/dict.js`，未写死第二份映射） |
| `src/components/UserSelect.vue` | 远程搜索 `listUsers({keyword, size:20})`，选项"姓名（科室）"，v-model 绑 id，可清空；支持 `initialLabel` 回显（scope/list 不返回 user id，用行内已有姓名防裸 id） |
| `src/components/DeptSelect.vue` | `listDepts({deptType?})`，v-model 绑科室 id，可清空可搜索 |
| `src/views/metric/index.vue` | 完整替换占位页（步骤 2 全量实现，见下） |
| `src/views/department/index.vue` | 完整替换占位页（步骤 3 全量实现，见下） |

未改动任何 `src/api/*.js`（函数签名零变更，实现也无需修正）。

## 三、【指标维护】页实现要点（步骤 2）

- **左栏 320px**：`el-tree` 加载 `getMetricTree()`，节点文字"名称（指标数）"，顶部搜索框经 `filter-node-method` 按名称/编号过滤；点击节点 → `query.chapterCode = node.code` 并刷新列表（当前章节名在工具栏提示）。
- **右栏查询**：关键字（编号/代号/名称，`keyword`）、提取方式下拉（SYS/MANUAL/HYBRID/PROV）、是否适用下拉（true/false）、重置按钮（清全部条件含章节）；分页 20 条/页 + 总数。
- **表格列**：指标编号 / 专科代号 / 指标名称（换行） / 属性 / 单位 / 提取方式（ExtractModeTag 带 applicable） / 汇总方式（DEDUP_DTL→明细去重、DEPT_SUM→分科累加，取自 dict.js） / 分子/分母原子编号（两行） / 科室数 / 操作。
- **抽屉（60%）**：
  - 只读区：`el-descriptions` 展示总表编号 metricCode、专科代号 aliasCode、章节路径 chapterPath、国家计算方法 stdFormulaDesc、业务系统来源 dataSysSource（字段名逐一取自 `MetricDict` 实体）；atoms 表（编号 code、名称 name、角色 atomRole）；scopes 表（科室名、是否免统、提取方式覆盖）。
  - 可编辑区（仅 ADMIN/FUNC_LEADER 显示）：本院统计口径 textarea、是否适用 switch + 不适用原因（不适用时必填）、提取方式、汇总方式、目标值（数字）、归口职能部门（DeptSelect deptType=FUNC）、修改原因（必填校验）。保存调 `updateMetric(id, {..., changeReason})` 成功后刷新列表与树。
  - 底部修改记录：`listOperLogs({module:'METRIC', targetType:'eval_metric_dict', targetId, size:20})`，展示时间/操作人/变化字段/原因；变化字段与原因从 `detailJson` 的 `before/after` 键位与 `reason` 解析（后端 MetricService 日志结构实测确认）。

## 四、【部门管理】页实现要点（步骤 3）

- **上部归口卡片**：`funcDeptSummary()` 每部门一张卡片（部门名、指标数、实例数）；卡片下方固定提示"按归口部门分配指标请到【指标维护】修改"（`/api/scope/list` 不支持按归口部门过滤，按任务书说明只展示不过滤）。
- **下部实例表**：查询条件关键字（`keyword`）+ 责任科室（DeptSelect→`deptId`）+ 是否免统（`skipFlag`）；列：指标编号/指标名称/专业（同一 `metricId` 用 `span-method` 三列合并）/责任科室/科室范围（标签组 >3 折叠 `+N` 悬停显全部，数据来自后端已拆好的 `hisDeptNames` 数组）/联络员/内审员/终审人/提取方式覆盖（ExtractModeTag，无覆盖显"继承"）/免统（红 tag + 悬停 skipReason）/操作（编辑）。
- **工具栏**：导出 Excel（`exportScopes` 带当前过滤条件）；导入 Excel（`el-upload` http-request → `importScopes`，结果弹窗显示更新数与错误行表）；同步 HIS 科室编码（仅 ADMIN 显示，二次确认后调 `syncDeptCodes`，结果弹窗显示 updated 与 unmatchedNames 标签组）。
- **编辑弹窗**：科室范围 `el-select multiple allow-create filterable`；联络员/内审员/终审人三个 UserSelect（旁显"当前：姓名"提示——列表接口不返回这三个 user id，初始为空，不改则传 null 由后端稀疏更新保持原值）；提取方式覆盖含"继承指标"（=null）；是否免统 switch + 免统原因（免统时必填，与后端校验一致）。

## 五、权限（步骤 4）

- `canEdit = hasRole('ADMIN','FUNC_LEADER')`：指标页表格操作钮文字（编辑/查看）、抽屉可编辑区整体隐藏；部门页编辑钮、导入按钮隐藏（操作列显"-"）；同步按钮仅 ADMIN。
- 页面路由角色由 TASK-12 守卫控制（两页均限 ADMIN/FUNC_LEADER 进入），后端亦有权注拦截。

## 六、验收命令与真实输出

### 6.1 构建输出最后 10 行

```text
dist/assets/reconcile-BrhgUYqv.js            0.19 kB │ gzip:   0.18 kB
dist/assets/summary-CiE-lQVq.js              0.29 kB │ gzip:   0.28 kB
dist/assets/Placeholder-BeSlzLA5.js          0.31 kB │ gzip:   0.29 kB
dist/assets/system-Bv79jnA_.js               0.44 kB │ gzip:   0.21 kB
dist/assets/login-B6lJrygl.js                1.70 kB │ gzip:   0.98 kB
dist/assets/ChangePassword-jOsy9A86.js       1.97 kB │ gzip:   1.07 kB
dist/assets/DeptSelect-kJoGvaUS.js           2.07 kB │ gzip:   0.99 kB
dist/assets/users-CgaHIaZt.js                5.77 kB │ gzip:   2.39 kB
dist/assets/metric-DS_hHmI_.js              10.87 kB │ gzip:   4.05 kB
dist/assets/department-6rCJa9wE.js          12.73 kB │ gzip:   4.73 kB
（其后 index-BwM_Rqqf.js 1,287.91 kB，✓ built in 8.05s）
```

（metric 10.87 kB / department 12.73 kB 两个新页面均按路由分包产出。）

### 6.2 TODO/mock 检查

```powershell
PS> Select-String -Path 'hospital-eval-web/src/views/metric/*.vue', 'hospital-eval-web/src/views/department/*.vue' -Pattern 'TODO|mock|假数据'
（无任何匹配输出）
PS> git status --short
 M hospital-eval-web/src/views/department/index.vue
 M hospital-eval-web/src/views/metric/index.vue
?? hospital-eval-web/src/components/DeptSelect.vue
?? hospital-eval-web/src/components/ExtractModeTag.vue
?? hospital-eval-web/src/components/UserSelect.vue
```

（改动恰为白名单 5 个文件；node_modules/dist 未出现。）

### 6.3 dev 自测（`npm run dev`，经 5173 代理以 admin 实调）

**指标维护页打开调用**：
| 接口 | 实测 |
| :--- | :--- |
| GET /api/metric/tree | code=0，根章节 25.2 指标数 1485、2 个子章节 |
| GET /api/metric/list?page=1&size=20 | code=0，total=1485 |
| GET /api/metric/list?chapterCode=25.2.2.1（点树节点） | code=0，total=3（过滤收窄 ✓） |
| GET /api/metric/{id}（抽屉） | code=0，dict/atoms/scopes 三段齐备 |
| GET /api/system/oper-logs?module=METRIC&targetType=eval_metric_dict&targetId=… | code=0 |

**部门管理页打开调用**：
| 接口 | 实测 |
| :--- | :--- |
| GET /api/scope/func-dept-summary | code=0 |
| GET /api/scope/list?page=1&size=20 | code=0，total=1876 |
| GET /api/scope/list?keyword=全院 | code=0（keyword 生效） |
| GET /api/system/depts（DeptSelect） | code=0，81 个科室 |
| GET /api/system/depts?deptType=FUNC | code=0，1 个归口科室 |
| GET /api/system/users?page=1&size=20（UserSelect） | code=0，68 用户 |

共 13 次调用全部 code=0（自测后 dev server 已停止）。

## 七、安全红线自查

- 本任务零 Oracle 连接（只经 dev 代理访问本机后端）。
- 自测登录密码运行时读取 `deploy/.env`，未打印、未写入任何文件。
- 未写死任何指标/科室/用户数据（页面数据全部来自接口）；TODO/mock 检查无输出。
- 改动仅白名单 5 个前端文件；`node_modules/`、`dist/` 未入 Git。

## 八、待确认事项（不阻塞）

1. **"继承指标"无法清除已有覆盖**：`PUT /api/scope/{id}` 为稀疏更新（`extractModeOverride=null` 表示"不修改"），故当实例已存在覆盖时，UI 上的"继承指标"选项无法真正清除它（后端无"显式清空"契约）。当前行为：选"继承"保存后覆盖保持不变。如需支持清除，须主控在 04 §12.4 增补契约（如 `extractModeOverrideClear:true`）后另行修改后端。
2. **归口卡片点击不过滤**（任务书已预授权）：`/api/scope/list` 无归口部门过滤参数，卡片仅统计展示 + 提示文字。
3. `/api/scope/list` 不返回联络员/内审员/终审人 userId（仅姓名），编辑弹窗三个 UserSelect 初始为空、旁显"当前：姓名"；未改动时保存传 null，后端稀疏更新不覆盖原值，行为安全。

## 九、Git 提交

提交信息：`TASK-13: 指标维护与部门管理页面`

```text
34d3026 TASK-13: 指标维护与部门管理页面
```
