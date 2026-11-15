# TASK-17：【大屏展示】监测驾驶舱与【省平台对账】（后端 + 前端）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 9 · 第 1 次 |
| 依赖 | TASK-16 |
| 预计改动 | 后端 6~8 个文件 + 前端 3~5 个文件 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；**不连** Oracle、**不连** 10.10.8.75 |

## 1. 目标
1. 后端实现 04 §12.8 的全部接口（大屏汇总、对账模板下载、对账导入、对账结果查询与导出）。
2. 前端完成 `src/views/screen/dashboard.vue`（监测驾驶舱）和 `src/views/screen/reconcile.vue`（省平台 77 张表对账专区）。

## 2. 开工前必读
1. `00_项目需求背景与AI接手总览.md` 第一节（省平台“分母非零占比”“77 张表上传率”要求）
2. `04_开发契约与编码规范.md` 第 5.2（`eval_prov_reconcile`）、12.8、13 节
3. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1
cd hospital-eval-web; npm run build; cd ..
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/dashboard/**`
- `hospital-eval-server/src/test/java/com/hospital/eval/dashboard/**`
- `hospital-eval-web/src/views/screen/**`
- `scripts/smoke/TASK_17.ps1`
- `docs/task_receipts/TASK_17_回执.md`

## 5. 禁止事项
- 大屏禁止任何写死的示例数字；接口返回空就显示“暂无数据”。
- 禁止引入 JimuReport、DataV 等额外大屏框架（只用 ECharts + Element Plus）。

## 6. 操作步骤

### 步骤 1：后端 `GET /api/dashboard/overview?year=`
- `cards.metricTotal` = 适用指标数（`is_applicable=true`）；
- `cards.sysRate` = 适用指标中 `extract_mode IN ('SYS','HYBRID')` 的占比（%，1 位小数）——即“信息化提取率”；
- `cards.manualRate` = `MANUAL` 占比；
- `cards.archivedRate` = 该年全院当期行 `L3_ARCHIVED` 数 / 适用指标数；
- `chapters`：按 `eval_master_row` 中 `level = 4` 的章节（即“第X部分/第X章”层级，如 `25.2.3.1`~`25.2.3.5`、`25.2.2.1`~`25.2.2.2`）分组：`metricCount`、`filledCount`（该年全院当期行分子或结果值非空）、`zeroCount`（分子或分母为 0）；
- `specialties`：第三章各重点专业（`chapter_code` 以 `25.2.3.3.` 开头，按其第 5 级章节分组，`chapterCode` 取该第 5 级章节编号，`name` 取章节名）：`metricCount`、`filledCount`、`archivedCount`；
- `trend`：2022~2025 每年 `filledCount`。

### 步骤 2：后端对账
- `GET /api/reconcile/template`：生成 xlsx，列 `指标编号 | 指标名称 | 统计年份 | 省分子 | 省分母 | 省结果值`，附 2 行示例说明（示例行以 `#` 开头的指标编号表示说明行，导入时跳过）。
- `POST /api/reconcile/import`（`ADMIN`、`FUNC_LEADER`）：
  1. 读第一个 Sheet，第 1 行表头，按列名定位（不按列号）；跳过空行和 `#` 开头的行；
  2. `import_id` = `REC_yyyyMMddHHmmss`；
  3. 每行按 `指标编号` 查 `eval_metric_dict.metric_code`，再取该年全院当期行作为 `hosp_*`；
  4. 按 04 §12.8 规则计算 `flag`；
  5. 写 `eval_prov_reconcile`；返回 `{importId, total, issueCount}`（`issueCount` = flag ≠ OK 的数量）。
- `GET /api/reconcile/imports`、`GET /api/reconcile/{importId}/rows`（`onlyIssue=true` 时只返回 flag ≠ OK）、`GET /api/reconcile/{importId}/export`（导出全部行，加一列“问题说明”中文：省分母为0 / 省分子为0 / 院省偏差>5% / 院内无此指标 / 一致）。
- 单元测试 `ReconcileFlagTest`：覆盖 5 种 flag 判定与“省值为 0 时比较分子分母”的分支。

### 步骤 3：前端【监测大屏】`src/views/screen/dashboard.vue`
- 深色背景（`#0b1a33`），适配 1920×1080，窗口缩放时图表 `resize`；顶部标题“医院等级评审指标监测大屏”+ 年份选择 + 全屏按钮（`document.documentElement.requestFullscreen()`）；
- 布局 3 行：
  1. 4 个指标卡（适用指标数、信息化提取率、人工填报占比、终审归档率）；
  2. 左：章节完成情况柱状图（`chapters`：指标数 vs 已填报数）；中：信息化提取方式饼图（复用 `workbenchOverview` 的 `byExtractMode`）；右：零值指标数柱状图（`chapters.zeroCount`）；
  3. 重点专业完成情况横向条形图（`specialties`，按完成率排序）+ 年度趋势折线（`trend`）；
- 点击专业条形 → 跳转 `/summary/current?chapterCode=<该专业的 chapterCode>`。

### 步骤 4：前端【省平台对账】`src/views/screen/reconcile.vue`
- 顶部：`下载模板`、`导入省平台反馈表`（`el-upload`）、导入批次下拉（`listReconcileImports`）；
- 统计条：总数、一致、省分母为 0、省分子为 0、偏差 >5%、院内无此指标（数字 + 颜色）；以及“分母非零占比 = (总数 − 省分母为0) / 总数”；
- 表格（`listReconcileRows`）：`指标编号`、`指标名称`、`年份`、`省分子/省分母/省结果值`、`院内分子/院内分母/院内结果值`、`问题类型`（红/橙/灰标签）、`备注`；“只看问题项”开关；`导出差异清单` 按钮；
- 点击指标编号 → 跳转 `/summary/current?keyword=<指标编号>`。

### 步骤 5：冒烟脚本 `scripts/smoke/TASK_17.ps1`
1. `/api/dashboard/overview?year=2025`：`cards.metricTotal > 0`，`chapters` 长度 ≥ 5，`specialties` 长度 ≥ 20；
2. 下载模板到 `$env:TEMP`；用 Python（`openpyxl`）在模板里写 3 行测试数据：`25.2.3.3.5.1.1`（年份 2025，省分子=院内分子、省分母=院内分母，期望 OK——院内值从 `/api/result/summary?keyword=NEU-EPI-01` 取）、同一指标改省分母为 0（期望 `PROV_DEN_ZERO`）、不存在的编号 `99.99.99`（期望 `NOT_FOUND`）；
3. 导入 → `total == 3`、`issueCount == 2`；`rows?onlyIssue=true` 返回 2 条且 flag 正确；导出文件大小 > 3KB；
4. 打印 `SMOKE TASK_17 ALL PASS`。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_17.ps1
cd hospital-eval-web; npm run build; cd ..
git status --short
```

## 8. 回执必须包含
- Maven 测试汇总行、冒烟输出、前端构建输出、大屏接口返回的 `cards` 四个数值；
- 提交号。

提交信息：`TASK-17: 监测大屏与省平台对账`
