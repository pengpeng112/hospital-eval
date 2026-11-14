# TASK-13：前端【指标维护】与【部门管理】页面

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 7 · 第 1 次 |
| 依赖 | TASK-12 |
| 预计改动 | 6~10 个前端文件 |
| 是否连数据库 / 服务器 | 只连本机后端 |

## 1. 目标
把 `src/views/metric/index.vue` 和 `src/views/department/index.vue` 两个占位页做成完整功能页，全部数据来自 TASK-08 的接口。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 10、12.4、13 节
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1
cd hospital-eval-web; npm run build; cd ..
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-web/src/views/metric/**`
- `hospital-eval-web/src/views/department/**`
- `hospital-eval-web/src/components/ExtractModeTag.vue`、`hospital-eval-web/src/components/UserSelect.vue`、`hospital-eval-web/src/components/DeptSelect.vue`
- `docs/task_receipts/TASK_13_回执.md`

## 5. 禁止事项
- 禁止修改 `src/api/*.js` 的函数签名（如发现接口封装错误，可以修正实现，但需在回执说明）。
- 禁止前端写死指标或科室数据。

## 6. 操作步骤

### 步骤 1：公共组件
- `ExtractModeTag.vue`：props `mode`、`applicable`；按 04 §13 显示彩色 `el-tag`（不适用优先显示灰色“不适用”）。
- `UserSelect.vue`：远程搜索下拉（调用 `listUsers({keyword, size:20})`），显示“姓名（科室）”，`v-model` 绑定用户 id；可清空。
- `DeptSelect.vue`：调用 `listDepts`，可按 `deptType` 过滤，`v-model` 绑定科室 id。

### 步骤 2：【指标维护】`src/views/metric/index.vue`
布局：左右两栏。
- **左栏（宽 320px）**：`el-tree` 显示 `getMetricTree()`，节点文字“名称（指标数）”，顶部有搜索框过滤树节点；点击节点 → 右栏按 `chapterCode` 过滤。
- **右栏**：
  - 查询条件：关键字（编号/代号/名称）、提取方式下拉、是否适用下拉、重置按钮；
  - 表格列：`指标编号`、`专科代号`、`指标名称`（可换行）、`属性`、`单位`、`提取方式`（`ExtractModeTag`）、`汇总方式`（`DEDUP_DTL` 显示“明细去重”、`DEPT_SUM` 显示“分科累加”）、`分子/分母原子编号`（两行显示）、`科室数`、`操作`（查看/编辑）；
  - 分页：每页 20 条，显示总数。
- **查看/编辑抽屉**（`el-drawer`，宽 60%）：
  - 只读区：全部字典字段（总表编号、专科代号、章节路径、国家计算方法、业务系统来源）、原子列表（`atoms`：编号、名称、角色）、科室实例列表（科室名、是否免统、提取方式覆盖）；
  - 可编辑区（仅 `ADMIN`、`FUNC_LEADER` 显示，其他角色全部只读）：本院统计口径（多行文本）、是否适用（开关）+ 不适用原因、提取方式、汇总方式、目标值、归口职能部门（`DeptSelect`，`deptType=FUNC`）、**修改原因（必填）**；
  - 保存：调用 `updateMetric`，成功后刷新列表与树；
  - 抽屉底部显示该指标最近的修改记录（调用 `listOperLogs({module:'METRIC', targetType:'eval_metric_dict', targetId: 指标id, size:20})`，展示时间、操作人、变化字段、原因）。

### 步骤 3：【部门管理】`src/views/department/index.vue`
布局：上下两部分。
- **上部：归口部门卡片**：调用 `funcDeptSummary()`，每个部门一张小卡片（部门名、指标数、实例数）；点击卡片 → 下方表格按该部门过滤（前端把部门 id 作为查询参数传给 `listScopes` 不支持的话，就在卡片下方提示“按归口部门分配指标请到【指标维护】修改”）。
  > 说明：`/api/scope/list` 不支持按归口部门过滤，所以卡片只做统计展示 + 提示，不做过滤。
- **下部：一标多科实例表**：
  - 查询条件：关键字（指标编号/名称/科室）、责任科室（`DeptSelect`）、是否免统；
  - 表格列：`指标编号`、`指标名称`、`专业`、`责任科室`、`科室范围`（标签组，超过 3 个显示 `+N`，鼠标悬停显示全部）、`联络员`、`内审员`、`终审人`、`提取方式覆盖`、`免统`（是则红色“免统”+ 原因悬停）、`操作`（编辑）；
  - **按指标分组显示**：同一指标的多行用 `span-method` 合并“指标编号/指标名称/专业”三列，直观体现“一个指标多个科室”；
  - 工具栏按钮：`导出 Excel`（`exportScopes`）、`导入 Excel`（`el-upload` 调 `importScopes`，完成后弹窗显示更新数与错误行列表）、`同步 HIS 科室编码`（仅 ADMIN，调用 `syncDeptCodes`，完成后弹窗显示未匹配科室名称列表）。
- **编辑弹窗**：科室范围（`el-select` 多选 + 可输入新值 `allow-create filterable`）、联络员/内审员/终审人（`UserSelect`）、提取方式覆盖（含“继承指标”选项 = null）、是否免统 + 免统原因（免统时必填）。

### 步骤 4：权限
- 非 `ADMIN` / `FUNC_LEADER` 进入这两个页面时所有编辑按钮隐藏（后端也会拦截）。

## 7. 验收命令
```powershell
cd hospital-eval-web
npm run build
cd ..
Select-String -Path hospital-eval-web/src/views/metric/*.vue, hospital-eval-web/src/views/department/*.vue -Pattern "TODO|mock|假数据" ; "（上面应无输出）"
git status --short
```

## 8. 回执必须包含
- 构建输出最后 10 行；TODO/mock 检查结果；
- 自测记录：用 `npm run dev` 打开两个页面时调用了哪些接口（列接口名即可）；
- 提交号。

提交信息：`TASK-13: 指标维护与部门管理页面`
