# TASK-18：真实数据分批跑批与历史基线交叉对账

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 9 · 第 2 次 |
| 依赖 | TASK-17；`deploy/.env` 已配置 Oracle 只读账号且 TASK-06 冒烟 Oracle 连通 |
| 预计改动 | 2~3 个脚本 + 1 份报告 |
| 是否连数据库 / 服务器 | 本机后端与 PostgreSQL；Oracle **只读**（经后端跑批引擎）；**不连** 10.10.8.75 |

## 1. 目标
用系统自己的跑批引擎，对已完成的 12 个重点专业**分批**实际跑出 2022–2025 年数据，与存量基线（专科表年度数字 / 总表数字）逐项对比，输出《真实数据跑批对账报告》，找出口径或迁移问题。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 8、9、12.3 节
2. `docs/reports/存量指标与SQL清洗预检台账.md`（了解 DS_CONFLICT 等标记）
3. 本文件

## 3. 前置检查（任一不满足即停止并报告）
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_06.ps1     # ODS_RO 与 HIS_RO 的 ping 都必须 ok=true（不能是 SKIP）
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_07.ps1     # 无 FAIL
```

## 4. 允许新建 / 修改的文件
- `migration/05_run_real_batches.py`
- `migration/06_compare_baseline.py`
- `docs/reports/真实数据跑批对账报告.md`
- `docs/task_receipts/TASK_18_回执.md`

## 5. 禁止事项
- 禁止一次提交全部指标；必须按专业分批，**每批最多 30 个科室实例**，上一批完成再提交下一批。
- 禁止在工作时间（08:00–18:00）跑神经、肿瘤、心血管这三个大专业（它们 SQL 多且含 CLOB 文书）：脚本检测当前时间，在工作时间只跑其余专业，大专业留到晚上执行（回执写明执行时间）。
- 禁止修改任何 SQL；发现问题只记录。
- 报告中禁止出现患者姓名、住院号、患者ID。

## 6. 操作步骤

### 步骤 1：`migration/05_run_real_batches.py`
1. 登录后端（admin，密码从 `deploy/.env` 读，不打印）；
2. 从 PostgreSQL 查询 `ROOT_12` 来源（`eval_metric_dept_scope.source_ref` 不以 `第三章` 开头且属于 12 个专业文件）的、`skip_flag=false` 且有启用 SQL 的科室实例 id，按专业分组；
3. 执行顺序（小专业在前）：病案、康复、营养、麻醉、感染、疼痛、脑损伤、重症、呼吸 → （晚上）心血管、肿瘤、神经；
4. 每组切成每批 ≤ 30 个实例，调用 `/api/batch/run`（`scopeIds`、`years=[2022,2023,2024,2025]`、`versionTag="TASK-18 真实数据首跑-<专业>"`）；
5. 每 30 秒轮询批次状态；单批超过 2 小时未完成 → 记录超时，继续下一批（不中断整体）；
6. 每批结束把批次号、任务统计、耗时追加写到 `migration/.tmp/run_log.json`（目录已被忽略）；
7. 支持参数 `--only 专业名` 和 `--resume`（跳过 `run_log.json` 中已成功的批次），方便晚上续跑。

### 步骤 2：`migration/06_compare_baseline.py`
对每个跑过的科室实例和年份，取：
- **新值**：该实例该年当期行（来自 TASK-18 批次）的分子、分母；
- **基线 A**：`BATCH_BASELINE_IMPORT` 中同实例同年的科室行（来自专科表年度数字）；
- **基线 B**：同指标同年的全院行（来自总表）——仅当该指标只有 1 个非免统科室时才可比。
判定（分子、分母分别判定）：
- 与基线 A 完全相等 → `一致`；
- 差异 ≤ 2% 或绝对差 ≤ 2 → `轻微差异`；
- 差异更大 → `显著差异`；
- 基线不存在 → `无基线`；新值为空 → `未产出`（附子任务错误信息）。

### 步骤 3：报告 `docs/reports/真实数据跑批对账报告.md`
1. 执行概况：每个专业的批次数、子任务成功/失败/跳过数、总耗时、最慢的 10 条 SQL（指标编号、科室、耗时）；
2. 失败原因分布：按 `error_msg` 前缀分组计数（`ORA-xxxxx`、`NO_YEAR_COLUMN`、`SANDBOX_REJECTED`、`ROW_LIMIT_EXCEEDED`、`DATASOURCE_NOT_CONFIGURED` 等），每类列前 10 条明细（指标编号、科室、sql_id、错误）；
3. 对账汇总：每个专业 一致 / 轻微 / 显著 / 无基线 / 未产出 的数量与一致率；
4. 显著差异明细表：指标编号、指标名、科室、年份、新分子/基线分子、新分母/基线分母、可能原因（脚本按规则给出提示：`PERIOD_CONFLICT` 标记 → “时段判定冲突”；`DS_CONFLICT` → “数据源冲突”；新值远大于基线 → “可能缺科室或去重条件”；新值为 0 → “可能科室范围或时间窗不匹配”）；
5. 结论与建议：需要用户/科室确认的事项清单。

## 7. 验收命令
```powershell
python migration/05_run_real_batches.py            # 白天先跑小专业
python migration/05_run_real_batches.py --resume   # 晚上 18:00 后续跑大专业
python migration/06_compare_baseline.py
Get-Content docs/reports/真实数据跑批对账报告.md -TotalCount 60
git status --short
```

## 8. 回执必须包含
- 执行时间段、每个专业的成功/失败数、一致率；
- 失败原因分布表；
- 显著差异数量（明细见报告）；
- **声明：全程只通过沙箱执行 SELECT，Oracle 写入数量为 0；报告不含患者身份信息**；
- 提交号（`.tmp` 下日志不提交）。

提交信息：`TASK-18: 真实数据跑批与基线对账报告`

> **TASK-18 之后由主控 AI 与用户一起逐条看“显著差异”和“失败原因”，必要时安排修正任务（修 SQL 走【算法管理】留痕），再进入 TASK-19。**
