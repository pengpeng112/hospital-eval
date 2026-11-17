# TASK-17 回执：【大屏展示】监测驾驶舱与【省平台对账】（后端 + 前端）

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始 / 结束时间：2026-11-17 上午（Asia/Shanghai）
- 状态：已完成（Maven 71/71 BUILD SUCCESS、冒烟 12 项断言 ALL PASS、前端构建成功）
- Git 提交号：见文末
- 基线：HEAD = 82254b5（TASK-16 回执提交号回填）
- 说明：任务指令第 6 条"粘贴到 docs/task_receipts/TASK_03_回执.md"为既有笔误，按 TASK_17.md 第 8 节与允许清单写入本文件 `TASK_17_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1   （末行）
SMOKE TASK_11 ALL PASS
PS> cd hospital-eval-web; npm run build
✓ built in 20.60s
```

（过程中 Docker Desktop 引擎一次意外停止，由本 AI 重启恢复，`eval-postgres` 数据卷完好。）

## 二、新建的文件（7 项，均在白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `dashboard/service/ReconcileFlag.java` | **flag 判定纯函数**（可单测）：NOT_FOUND → PROV_DEN_ZERO → PROV_NUM_ZERO → DIFF_GT_5 → OK 顺序判定；省值 0/未填时按分子分母完全相等比较；`labelOf` 中文映射（省分母为0/省分子为0/院省偏差>5%/院内无此指标/一致） |
| `dashboard/service/DashboardService.java` | 大屏汇总（步骤 1 全量）：cards 四值 SQL 实时统计；chapters=`eval_master_row` level=4 的 7 章节按 `chapter_code = code OR LIKE code||'.%'` 归属（dot 后缀防 25.2.3.3.1 误吞 25.2.3.3.10）；specialties=level=5 且前缀 25.2.3.3.（29 个第 5 级专业章节）；trend 2022~2025；全部 FILTER 聚合零写死 |
| `dashboard/service/ReconcileService.java` | 对账（步骤 2）：模板 xlsx（表头 6 列 + 2 行 `#` 示例说明行）；导入（**表头按列名定位**、跳空行与 `#` 行、import_id=REC_yyyyMMddHHmmss、逐行按 metric_code 查 dict + 该年全院当期行、flag 判定、batch insert、RECONCILE/IMPORT 日志）；imports 聚合；rows 分页（onlyIssue）；导出全量 12 列含"问题说明"中文列 |
| `dashboard/controller/DashboardController.java` | GET /api/dashboard/overview?year=（登录即可） |
| `dashboard/controller/ReconcileController.java` | template / import（**@SaCheckRole ADMIN|FUNC_LEADER SaMode.OR**）/ imports / rows / export（导入外登录即可，符合 §12.8） |
| `src/test/java/com/hospital/eval/dashboard/ReconcileFlagTest.java` | 9 个场景：OK（一致）、偏差>5%（含恰好 5% 边界不算）、省分母 0（优先级）、省分子 0（含院内分子也 0 时不命中）、NOT_FOUND（编号不存在/无院内行）、**省值为 0 比较分子分母**（不等 DIFF_GT_5 / 相等 OK）、省值未填退化比较、院内行存在但数值未填、中文映射 |
| `scripts/smoke/TASK_17.ps1` | 冒烟（UTF-8 BOM；下载用 curl.exe；multipart 用 .NET HttpClient） |
| `src/views/screen/dashboard.vue` | 监测大屏（步骤 3，见下） |
| `src/views/screen/reconcile.vue` | 省平台对账（步骤 4，见下） |

未改动任何既有文件（api/dashboard.js 六个函数 TASK-12 已备齐；前端两页为占位页替换）。

## 三、验收命令与真实输出

### 3.1 Maven（任务书验收命令）

```text
PS> docker run --rm ... maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
（明细含本任务新增 ReconcileFlagTest 9：OK/偏差边界/省分母0/省分子0/NOT_FOUND/省值0比分子分母/省值null/院内值null/中文映射）
```

### 3.2 部署

```text
PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Container eval-server Started
2026-11-17T08:17:22.110+08:00  INFO --- com.hospital.eval.EvalApplication : Started EvalApplication in 4.681 seconds
```

### 3.3 冒烟输出（`powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_17.ps1`）

```text
PASS: 1a cards.metricTotal=1216 > 0
PASS: 1b chapters 7 >= 5
PASS: 1c specialties 29 >= 20
    cards: metricTotal=1216 sysRate=97.1 manualRate=2.7 archivedRate=0.0
PASS: 2a 模板下载 3701 字节 > 3KB
PASS: 2b NEU-EPI-01 有 2025 年院内结果 num=84.0000 den=142.0000 val=59.1549
PASS: 2c Python 写入 3 行测试数据
PASS: 3a 导入成功 importId=REC_20261117082051
PASS: 3b total=3 == 3
PASS: 3c issueCount=2 == 2
PASS: 4a onlyIssue 返回 2 条 == 2
PASS: 4b flags=NOT_FOUND,PROV_DEN_ZERO 正确
PASS: 5 导出 3999 字节 > 3KB
SMOKE TASK_17 ALL PASS
```

### 3.4 前端构建（最终态）

```text
dist/assets/index-CT0vFCO2.js             1,034.94 kB │ gzip: 343.42 kB
dist/assets/index-D73BZIyD.js             1,288.71 kB │ gzip: 418.50 kB
✓ built in 34.23s
```

### 3.5 git status --short（提交前）

```text
 M hospital-eval-web/src/views/screen/dashboard.vue
 M hospital-eval-web/src/views/screen/reconcile.vue
?? hospital-eval-server/src/main/java/com/hospital/eval/dashboard/
?? hospital-eval-server/src/test/java/com/hospital/eval/dashboard/
?? scripts/smoke/TASK_17.ps1
```

（恰为白名单文件与目录。）

## 四、大屏接口 cards 四个数值（year=2025 实测）

| 卡片 | 值 | 口径 |
| :--- | :--- | :--- |
| 适用指标数 metricTotal | **1216** | `is_applicable=true` |
| 信息化提取率 sysRate | **97.1%** | 适用中 `extract_mode IN ('SYS','HYBRID')` 占比（1 位小数） |
| 人工填报占比 manualRate | **2.7%** | 适用中 MANUAL 占比 |
| 终审归档率 archivedRate | **0.0%** | 2025 年全院当期 L3_ARCHIVED / 适用指标数（尚未跑批归档，真实为 0） |

chapters=7（level=4：25.2.2.1/25.2.2.2/25.2.3.1~25.2.3.5）、specialties=29（25.2.3.3. 下第 5 级）。

## 五、前端两页实现要点

**监测大屏 dashboard.vue**：深色 `#0b1a33`，标题 + 年份选择 + 全屏按钮（requestFullscreen）；4 指标卡；左章节柱状（指标数 vs 已填报）/ 中信息化提取方式饼图（复用 workbenchOverview 的 byExtractMode）/ 右零值柱状；重点专业横向条形（按完成率排序，**点击条形 → `/summary/current?chapterCode=`**）+ 年度趋势折线（trend 2022~2025）；全部图表 window resize + 卸载 dispose；接口空显示"暂无数据"，零写死数字；未引入 JimuReport/DataV。

**省平台对账 reconcile.vue**：下载模板（blob download）/ 导入（el-upload，仅 ADMIN/FUNC_LEADER 显示）/ 导入批次下拉（批次号·人·时间·计数）；统计条总数/一致/省分母0/省分子0/偏差>5%/院内无此指标（数字+配色）+ **分母非零占比 = (总数 − 省分母为0) / 总数**；明细表 11 列（省/院内 三值、问题类型红/橙/灰标签、备注）、只看问题项开关、导出差异清单；指标编号可点跳 `/summary/current?keyword=<编号>`。

## 六、开发过程中的两处自纠（均修复后复验通过）

1. **冒烟脚本 openpyxl 起始行错误**：初版 `enumerate(rows, start=1)` 把模板第 1 行表头覆盖为数据行，导致导入报"表头缺少必需列：指标编号"——改为 `start=2`（第 1 行表头、数据自第 2 行）。
2. **后端 hospitalRow 查询空结果抛异常**：`queryForObject` 在编号不存在（99.99.99 → NOT_FOUND 场景）时抛 `EmptyResultDataAccessException` 500——改为 `queryForList` 判空返回 null，重部署后 NOT_FOUND 正确落库。

## 七、安全红线自查

- 本任务**零 Oracle 连接**（任务书明确不连 Oracle）；全部统计与对账落库在本机 PostgreSQL。
- 大屏/对账页零写死示例数字（冒烟的 3 行测试数据为脚本运行时写入并导入，非前端代码内）；接口空显示"暂无数据"。
- 导入接口仅 ADMIN/FUNC_LEADER（SaMode.OR 注解 + 前端按钮双控）。
- 对账数据仅含指标编号/名称与数值，无患者信息；无 TODO/mock；改动恰为白名单文件。

## 八、待确认事项（不阻塞）

1. **省值未填（null）的退化比较**：04 §12.8 只定义了"省值为 0 时比较分子分母"；实现将"省结果值未填"同样退化为分子分母相等性比较（null 视为未填，null==null 算相等），单测覆盖。如主控希望"省值未填"另立 flag（如 PROV_VAL_MISSING），需先改契约再调整。
2. **统计条全量计数方式**：前端统计条对所选批次拉一次全量行（size=5000）在前端计数；对账导入量级为百级（77 表 × 年份），可接受。若未来单批次超 5000 行需改后端聚合接口。
3. **归档率当前为 0**：2025 年当期数据尚未跑批+归档（Oracle 白名单未放行），archivedRate 真实为 0.0；跑批归档后该卡片自动生效。
4. 冒烟的 3 行对账测试数据导入后**未清理**（eval_prov_reconcile 中 REC_20261117082051 共 3 行，1 行 OK + 2 行问题项）——任务书未要求清理且对账表是业务数据表，保留供主控浏览器复核页面展示；如需清理由主控决定（`DELETE FROM eval_prov_reconcile WHERE import_id='REC_20261117082051'`）。

## 九、Git 提交

提交信息：`TASK-17: 监测大屏与省平台对账`

```text
（提交后回填）
```
