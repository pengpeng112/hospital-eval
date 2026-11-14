# TASK-14：前端【算法管理】页面（SQL 编辑、校验试跑、版本对比、覆盖评审数据）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 7 · 第 2 次 |
| 依赖 | TASK-13 |
| 预计改动 | 5~8 个前端文件 |
| 是否连数据库 / 服务器 | 只连本机后端 |

## 1. 目标
完成 `src/views/algorithm/index.vue`：选指标 → 选科室实例 → 查看/编辑该实例的分子分母 SQL（CodeMirror 编辑器，SQL 高亮），校验、试跑前 20 行、保存（必填修改类型与原因）、查看历史并左右对比、触发“覆盖评审数据”跑批并显示进度。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 10、12.2、12.3、12.5、13 节
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1
cd hospital-eval-web; npm run build; cd ..
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-web/src/views/algorithm/**`
- `hospital-eval-web/src/components/SqlEditor.vue`、`hospital-eval-web/src/components/SqlDiffView.vue`、`hospital-eval-web/src/components/MetricPicker.vue`
- `docs/task_receipts/TASK_14_回执.md`

## 5. 禁止事项
- 禁止使用 Monaco Editor 或任何 CDN 加载的编辑器。
- 禁止在前端拼接或改写 SQL（只把用户编辑的原文发给后端）。
- 试跑与跑批按钮只对 `ADMIN` 显示。

## 6. 操作步骤

### 步骤 1：`SqlEditor.vue`
- 基于 `vue-codemirror` + `@codemirror/lang-sql`（`sql({ dialect: PLSQL })`，从 `@codemirror/lang-sql` 导入 `PLSQL`）；
- props：`modelValue`、`readonly`、`height`（默认 `420px`）；emit `update:modelValue`；
- 开启行号、括号匹配；中文字符正常显示。

### 步骤 2：`SqlDiffView.vue`
- 基于 `@codemirror/merge` 的 `MergeView`，左侧旧 SQL、右侧新 SQL，只读，高亮差异；
- props：`oldText`、`newText`；组件卸载时调用 `destroy()`。

### 步骤 3：`MetricPicker.vue`
- 远程搜索下拉：输入编号/代号/名称，调用 `listMetrics({keyword, size:20})`，显示“编号 代号 名称”，`v-model` 绑定指标 id，并 emit 选中的完整指标对象。

### 步骤 4：页面布局 `src/views/algorithm/index.vue`
1. **顶部条件栏**：`MetricPicker`；选中后调用 `getMetric(id)` 显示科室实例下拉（含“全部科室”）；右侧显示数据源状态（调用 `listDatasources()`，每个源一个小标签，ADMIN 可点“测试连接”调 `pingDatasource`）。
2. **左侧 SQL 列表**（宽 360px）：调用 `listSqls({metricId, scopeId})`，按科室分组，每条显示：角色中文（分子明细/分母明细/分子汇总/分母汇总）、时段（`202201~202412`）、数据源、版本号、状态（启用/停用）、校验状态（未校验灰/通过绿/报错红）、标记（`ds_flag`、`period_flag` 有值时显示橙色标签）；点击选中。
3. **右侧编辑区**：
   - 显示所选 SQL 的元信息（来源 `source_ref`、最后修改人、修改原因、更新时间）；
   - `SqlEditor` 编辑 `sqlAnnotated`（非 ADMIN 只读）；
   - 按钮：`校验`（调 `validateSql`，结果显示在编辑器下方：通过绿色，失败红色列出 errors）、`试跑前20行`（调 `trialSql({sqlText, datasourceKey, sqlId})`，弹窗以表格显示列名和最多 20 行、耗时；**弹窗顶部醒目提示“含患者信息，仅供核验，请勿截图外传”**）、`保存`（弹窗必填：修改类型 `口径升级/算法Bug修复`、修改原因 ≥ 5 字；调 `updateSql`）、`新增 SQL`（弹窗：角色、数据源、起止年月、SQL、原因 → `createSql`）、`启用/停用`（必填原因 → `toggleSql`）、`历史版本`。
4. **历史版本抽屉**：调用 `listSqlHistory(id)` 显示时间线（版本号、修改类型、原因、修改人、时间）；点某一版 → `getSqlHis(hisId)` → `SqlDiffView` 左右对比。
5. **覆盖评审数据**（ADMIN）：页面右上角按钮，弹窗：
   - 范围单选：`当前科室实例` / `当前指标全部科室`；
   - 年份多选：2022、2023、2024、2025（默认 2025）；
   - 版本说明（选填，如“2026年11月口径修订”）；
   - 提示文字：“重算将生成新批次，旧结果自动保留在【历史版本】；已终审归档的数据不会被覆盖，需先解锁。”；
   - 确认后调用 `runBatch`，然后每 5 秒轮询 `getBatch(batchId)`，以进度条显示 `SUCCESS+FAILED+SKIPPED / 总数`；完成后显示失败子任务列表（`listBatchTasks({batchId, status:'FAILED'})`：科室、角色、错误信息）；关闭弹窗即停止轮询。

## 7. 验收命令
```powershell
cd hospital-eval-web
npm run build
cd ..
Select-String -Path hospital-eval-web/src -Include *.vue,*.js -Recurse -Pattern "monaco|cdn\.|unpkg|jsdelivr" ; "（上面应无输出）"
git status --short
```

## 8. 回执必须包含
- 构建输出最后 10 行、外链/Monaco 检查结果；
- 自测记录：在页面上对一条测试 SQL 完成“校验 → 试跑 → 保存 → 历史对比”的结果描述（不要贴试跑出的患者数据）；
- 提交号。

提交信息：`TASK-14: 算法管理页面`
