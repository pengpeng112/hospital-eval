# TASK-15：前端【结果汇总】与四级下钻溯源（迎检核心页）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 8 · 第 1 次 |
| 依赖 | TASK-14 |
| 预计改动 | 6~10 个前端文件 |
| 是否连数据库 / 服务器 | 只连本机后端 |

## 1. 目标
完成 `src/views/summary/index.vue`（当期版本 / 历史版本两个菜单共用）及其组件，实现：**全院汇总 → 科室分布 → 患者明细 → 病历原文** 四级下钻，同屏核验面板（趋势 + 口径 + SQL + 复核记录），以及“导出省评回填总表”。

## 2. 开工前必读
1. `01_医院等级评审系统总体设计方案.md` 第 5.6 节（业务意图）
2. `04_开发契约与编码规范.md` 第 5.4、12.6、13、14 节
3. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_10.ps1
cd hospital-eval-web; npm run build; cd ..
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-web/src/views/summary/**`
- `hospital-eval-web/src/components/AuditStatusTag.vue`、`hospital-eval-web/src/components/TrendChart.vue`
- `docs/task_receipts/TASK_15_回执.md`

## 5. 禁止事项
- **禁止 `v-html` 直接渲染病历正文**；只能渲染 `highlight()`（TASK-12 的 `src/utils/escape.js`，先转义后高亮）的结果。
- 禁止在前端做汇总计算（数字全部来自后端）。

## 6. 操作步骤

### 步骤 1：公共组件
- `AuditStatusTag.vue`：`DRAFT` 灰“草稿”、`L1_SUBMIT` 蓝“已提交初审”、`L2_PASS` 绿“内审通过”、`L2_REJECT` 红“退回整改”、`L3_ARCHIVED` 深绿“已终审归档”。
- `TrendChart.vue`：ECharts 折线 + 柱状组合图，props `data=[{year, num, den, val}]`，柱为分子/分母，折线为结果值（右轴）；窗口大小变化时 `resize`，卸载时 `dispose`。

### 步骤 2：主页面 `src/views/summary/index.vue`
- 通过 `route.meta.mode` 区分：
  - `current`（当期版本）：不传 `batchId`；
  - `history`（历史版本）：顶部显示批次下拉（`listResultBatches()`，显示“批次号 · 类型 · 版本说明 · 时间”），必须选一个批次才查询；并提供“与当期对比”开关：打开后每个年份单元格下方用小字显示当期值，与所选批次不同则标橙。
- **筛选栏**：章节（`el-tree-select`，数据来自 `getMetricTree()`）、专业（输入框）、提取方式、“只看分子或分母为 0”开关、关键字、年份多选（默认 2022~2025）。
- **URL 参数预填**（04 §13）：页面加载时读取 `route.query` 中的 `chapterCode`、`keyword`、`extractMode`、`zeroOnly`，填入筛选栏后再查询；路由参数变化时（`watch(() => route.query)`）重新查询。
- **右上角按钮**：`导出省评回填总表`（`exportMaster({years, batchId})`；权限 `ADMIN/FUNC_LEADER/EXPERT/LEADER`）。
- **主表格**（`listSummary`）：
  - 固定列：`指标编号`、`专科代号`、`指标名称`、`提取方式`、`科室数`；
  - 每个选中年份一组列：`分子`、`分母`、`结果值`、`状态`；分子与分母显示为**蓝色可点击数字**（为 `null` 显示 `—`，不可点击）；`结果值` 按单位显示（`%` 单位加 `%`）；
  - 行可展开（`type="expand"`）：展开时调用 `listMetricScopes(metricId, {batchId, years})`，显示各科室行（科室、免统标记、各年分子/分母/状态），科室的分子分母同样可点击；
  - 点击指标名称 → 打开 **核验抽屉**。
- 分页 20 条。

### 步骤 3：核验抽屉 `ExpertVerifyDrawer.vue`（宽 70%）
调用 `getMetricPanel(metricId)`，分四个区块：
1. 基本信息：编号、代号、名称、单位、导向、归口部门、提取方式、汇总方式；
2. 口径：国家标准计算方法、本院统计口径（多行原样显示，`white-space: pre-wrap`）；
3. 趋势：`TrendChart`；
4. 实际执行 SQL：按科室 + 角色分 Tab，`SqlEditor`（只读）显示带注释 SQL，标注时段与版本号；
5. 复核记录：时间线（级别、动作、意见、操作人、科室、时间、IP）。

### 步骤 4：患者明细弹窗 `PatientDetailModal.vue`
- 打开参数：`metricId, scopeId(0=全院), year, role(NUM/DEN), batchId?`；标题“<指标名> · <科室或全院> · <年份> · 分子/分母明细”；
- 调用 `listDetail`，表格列由后端返回的 `columns` 动态生成（`prop` 以 `ext.` 开头的从 `row.ext` 取值）；
- 关键字搜索、分页（每页 50）、`导出 Excel` 按钮（`exportDetail`）；
- 顶部显示“共 N 人次（按 患者ID+住院次数 去重）”；
- 住院号列为链接 → 打开 **病历抽屉**；
- 弹窗顶部醒目提示：“含患者隐私信息，仅限院内核验使用”。

### 步骤 5：病历抽屉 `EmrRecordDrawer.vue`
- 入参：`patientId, visitId, inpNo`；
- 顶部两个按钮：`打开院内360视图`（调 `get360Url`，`url` 为空时按钮禁用并提示“未配置360视图地址”，否则 `window.open`）；
- 左侧文书列表（`listEmrDocuments`）：按时间排序，显示系统标签（MTL 蓝 / JHEMR 绿）、文书名称、时间；返回 `warnings` 时在列表上方显示黄色提示；
- 右侧正文（`getEmrDocumentContent`）：用 `<div class="emr-content" v-html="highlight(content, keywords)">` 渲染（**必须**用 `highlight`），`white-space: pre-wrap`；
- 关键词输入框：默认取指标本院统计口径中引号内的词（正则 `[“"](.+?)[”"]`，最多 5 个），用户可自行增删，回车后重新高亮。

## 7. 验收命令
```powershell
cd hospital-eval-web
npm run build
cd ..
Select-String -Path hospital-eval-web/src/views/summary/*.vue -Pattern "v-html" | ForEach-Object { $_.Line.Trim() }
git status --short
```
**预期**：构建成功；`v-html` 只出现在病历正文一处，且绑定的是 `highlight(...)` 的返回值。

## 8. 回执必须包含
- 构建输出；`v-html` 检查结果；
- 自测记录：当期版本 → 展开科室 → 点分子 → 打开病历（若有明细数据）各步骤是否成功、调用了哪些接口；历史版本选择 `BATCH_BASELINE_IMPORT` 是否能显示数据；导出总表是否成功下载；
- 提交号。

提交信息：`TASK-15: 结果汇总与四级下钻页面`
