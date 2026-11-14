# TASK-16：前端【人工上报】（手工填报 / 混合勾选 / 佐证预览 / 三级复核）与【工作台】

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 8 · 第 2 次 |
| 依赖 | TASK-15 |
| 预计改动 | 8~12 个前端文件 |
| 是否连数据库 / 服务器 | 只连本机后端与本机 kkFileView |

## 1. 目标
完成 `src/views/manual/index.vue` 与 `src/views/workbench/index.vue`，让科室联络员、内审员、职能终审人能在系统里完成“填报 / 勾选 → 上传佐证 → 提交 → 内审通过或退回 → 终审归档”的完整闭环。

## 2. 开工前必读
1. `01_医院等级评审系统总体设计方案.md` 第 5.1、5.5 节
2. `04_开发契约与编码规范.md` 第 9、10、10.1、12.7、13 节
3. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1
cd hospital-eval-web; npm run build; cd ..
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-web/src/views/manual/**`
- `hospital-eval-web/src/views/workbench/**`
- `docs/task_receipts/TASK_16_回执.md`

## 5. 禁止事项
- 禁止前端自行判断状态流转后直接改状态；所有流转都调后端接口，以后端返回为准。
- 禁止前端计算分子（混合池分子以 `hybridSelect` 返回值为准）。
- kkFileView 预览必须用后端返回的 `url`（同源 `/kkview/...`），禁止直接拼接 `http://...:18012`。

## 6. 操作步骤

### 步骤 1：【人工上报】主页面 `src/views/manual/index.vue`
- 顶部：年份选择（默认 2025）、状态 Tab（待填报 / 被退回 / 已提交 / 内审通过 / 已归档 / 全部，对应 `status` 参数）、关键字。
- 表格（`listReportTasks`）：勾选列、`指标编号`、`指标名称`、`科室`、`提取方式`、`我的角色`、`分子`、`分母`、`结果值`、`附件数`、`状态`（`AuditStatusTag`）、`操作`。
- 批量按钮（按勾选行）：`提交初审`（`auditSubmit`）、`内审通过`（`auditApprove`）、`退回整改`（弹窗必填意见 ≥ 5 字 → `auditReject`）；执行后弹窗列出 `success` 数和 `failed` 明细（原因）。
- 行操作：`填报`（打开填报抽屉）、`复核记录`（打开 `AuditTimelineDrawer`）、`终审归档`（仅当前用户 `myRole=FUNC_LEADER` 或 ADMIN，调 `auditArchive({metricId, year})`，失败时完整显示后端返回的未通过科室列表）、`解锁`（ADMIN/FUNC_LEADER，必填意见 → `auditUnlock`）。

### 步骤 2：填报抽屉 `ReportDrawer.vue`（宽 75%）
按该行 `extractMode` 显示不同 Tab：
1. **数值填报**（`MANUAL`，或任何提取方式下分子/分母为空需要补录时）：分子、分母（数字输入）、定性结论（下拉：是/否/符合/部分符合/不符合，可空）、备注；`保存` → `saveManualValue`；状态非 `DRAFT/L2_REJECT` 时整块禁用并提示“当前状态不可修改”。
2. **分母病例池勾选**（仅混合池实例；判断方法：调 `listHybridPool`，返回 400 则不显示此 Tab）：
   - 表格列同明细弹窗（动态列）+ `已选为分子`（复选框）+ `判定依据`；
   - 顶部显示“分母 N 人次 / 已勾选分子 M 人次”；
   - 勾选/取消：弹出小窗填写判定依据（勾选时必填）→ `hybridSelect` → 用返回值刷新 M；
   - 住院号可点开 `EmrRecordDrawer`（复用 TASK-15 组件）先看病历再勾选。
3. **佐证材料**：
   - 左侧目录（固定分类：`原始登记台账`、`制度文件`、`培训记录`、`督导检查`、`会议纪要`、`持续改进(PDCA)`、`其他`），显示每类文件数；
   - 中间文件列表（`listEvidence`）+ 上传（`el-upload` 拖拽，选择当前分类；前端先校验扩展名白名单与 100MB 上限）；
   - 右侧预览区（宽 55%）：点击文件 → `getEvidencePreviewUrl(id)` → `<iframe :src="url">`；若 kkFileView 不可用（iframe 加载失败或后端报错），PDF/图片降级为直接用 `rawUrl` 在 iframe/img 中打开，Word/Excel 提示“预览服务不可用，请下载查看”；
   - 删除按钮（上传人/ADMIN，二次确认）。
- 纯手工指标顶部红色提示：“纯手工指标提交前须上传原始登记台账或扫描件（省卫健委：杜绝无原始数据支撑）”。

### 步骤 3：复核时间线 `AuditTimelineDrawer.vue`
- 调 `listAuditLogs({resultId})` 或 `({metricId, year})`，`el-timeline` 显示：级别中文（科室初审 / 内审复核 / 职能终审 / 管理员）、动作中文（提交/通过/退回/归档/解锁）、意见、操作人、科室、IP、时间；退回节点红色。

### 步骤 4：【工作台】`src/views/workbench/index.vue`
- 顶部年份选择（默认 2025）。
- 第一行统计卡片（`workbenchOverview`）：评审指标总数、适用、不适用、系统提取、人工、系统+人工、省级；
- 第二行：复核进度环形图（ECharts 饼图，数据 = `audit` 五个状态）+ 我的待办卡片（`workbenchTodos`，每类一个数字，点击跳转 `/manual` 并带上对应状态 Tab 参数）；
- 第三行：科室进度表（`workbenchDeptProgress`：科室、总数、已提交、已通过、已归档、完成率进度条，完成率 100% 绿、≥50% 黄、<50% 红）；
- 第四行：异常预警列表（`workbenchAlerts`：类型标签、指标编号、指标名、科室、说明），点击指标编号跳转 `/summary/current?keyword=<编号>`。
- 页面不放欢迎语、不放装饰图片。

## 7. 验收命令
```powershell
cd hospital-eval-web
npm run build
cd ..
Select-String -Path hospital-eval-web/src/views/manual/*.vue -Pattern ":18012" ; "（上面应无输出）"
git status --short
```

## 8. 回执必须包含
- 构建输出；`:18012` 检查结果；
- 自测记录：用 admin 在页面上对一条测试数据（**年份选 2099**，测完后按 TASK-11 冒烟脚本第 9 步的 SQL 清理）走完“填报 → 上传 PDF → 预览 → 提交 → 退回 → 再提交 → 通过 → 归档 → 解锁”的结果；kkFileView 预览是否成功；
- 提交号。

提交信息：`TASK-16: 人工上报与工作台页面`
