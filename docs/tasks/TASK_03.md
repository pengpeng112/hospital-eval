# TASK-03：正式 SQL 目录交叉比对、历史基线提取与《预检台账》

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 2 · 第 1 次 |
| 依赖 | TASK-01、TASK-02 |
| 预计改动 | 4~5 个文件 |
| 是否连数据库 / 服务器 | **否** |

## 1. 目标
1. 把 `sqls.json` 里每条 SQL 与 `统计完成文件\{专业}\正式SQL\` 目录里的 `.sql` 文件做比对，填写 `formal_file_match`（**以 Excel 为准，目录只做比对，不替换**）。
2. 提取总表 2022–2025 历史值与专科表内置的年度分子/分母数字，生成 `baseline_values.json`。
3. 汇总 TASK-01~03 的全部统计，生成给人看的《存量指标与SQL清洗预检台账》（Markdown + Excel 两份）。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 6.4、6.5、7.3、7.4 节
2. `docs/reports/TASK01_总表解析报告.md`、`docs/reports/TASK02_专科表解析报告.md`
3. 本文件

## 3. 前置检查
```powershell
Test-Path migration/dist/master_rows.json, migration/dist/metric_dict.json, migration/dist/dept_scopes.json, migration/dist/sqls.json   # 4 个都必须 True
python -m pytest migration/tests -q
```

## 4. 允许新建 / 修改的文件
- `migration/00c_crosscheck_and_report.py`
- `migration/tests/test_crosscheck.py`
- `migration/dist/sqls.json`（**只允许**填写 `formal_file_match` 字段和 `meta.counts.formal_*` 统计）
- `migration/dist/baseline_values.json`
- `docs/reports/存量指标与SQL清洗预检台账.md`
- `docs/reports/存量指标与SQL清洗预检台账.xlsx`
- `docs/task_receipts/TASK_03_回执.md`

## 5. 禁止事项
- 禁止用目录里的 SQL 覆盖 Excel 里的 SQL（规则依据：`AI统一接手说明` 要求“SQL 修订后必须同步更新主 Excel”，所以 Excel 是最新口径）。
- 禁止把任何患者明细（`.csv` 内容）读进 JSON 或报告；本任务**不读** `明细\`、`汇总结果\` 目录下的 csv。
- 禁止改动 `sqls.json` 中除 `formal_file_match` 外的字段。

## 6. 操作步骤

### 步骤 1：扫描正式 SQL 文件
- 根目录：`E:\三甲复审\1、三甲复审统计指标\统计完成文件\`
- 递归找所有路径中包含 `\正式SQL\` 的 `.sql` 文件（已知约 2,100 个，分布在神经、心血管、肿瘤、呼吸、麻醉等目录）。
- 读取文件内容：先试 `utf-8-sig`，失败再试 `gbk`；两种都失败 → 记入报告“无法解码文件”。
- 对每个文件计算 `norm = 去注释后（strip_sql_comments）把所有空白压成单个空格并转大写` 的文本。

### 步骤 2：两种匹配方式
对 `sqls.json` 中每条 SQL 也计算同样的 `norm`：
1. **坐标匹配**（呼吸、病案等目录的文件名形如 `三_呼吸内科专业医疗质量控制指标_2019_年版__Sheet1__R18C14.sql`）：
   - 用正则 `__([^_]+)__R(\d+)C(\d+)\.sql$` 取 Sheet 名、行号、列号；
   - 与 sql 记录 `source_ref` 中的 `R行C列` 以及文件名前缀中的中文序号（如 `三`）对应的源文件匹配。
2. **内容匹配**（其余文件名形如 `CA-BC-01_乳腺疾病科一_分母_2025.sql`）：
   - 在所有未被坐标匹配的文件中，找 `norm` 完全相同的文件 → `SAME`；
   - 否则按文件名里的专科代号（`extract_alias_code`）+ 科室名 + `分子/分母` 找候选文件，找到但 `norm` 不同 → `DIFF`（`file` 填候选文件相对路径）；
   - 都找不到 → `NOT_FOUND`。
3. 坐标匹配成功时也比较 `norm`：相同 `SAME`，不同 `DIFF`。
4. `formal_file_match.file` 填相对 `统计完成文件\` 的路径（用 `/` 分隔）。
5. 统计“目录中没有被任何 Excel SQL 匹配到的文件数”，按专业目录分组写进报告（这些可能是辅助/明细导出 SQL，**不入库**，只报告）。

### 步骤 3：历史基线 `baseline_values.json`
1. **总表值** `master_values`：遍历 `master_rows.json`，对每行每年 `data` 非空的生成一条；`num_value` = 能转数字就转（去掉 `%`），否则 `null`。
2. **专科表值** `scope_values`：重新打开 `ROOT_12` 文件（只读），对表头**正好是** `20XX分子` / `20XX分母`（4 位年份 + 分子/分母，不含 `SQL`、不含 `-`）且单元格为 `NUMBER` 的值，按行找到对应的 `scope_key`（用 TASK-02 同样的行类型判定与继承规则，或用 `dept_scopes.json` 中的 `source_ref` 行号对应），生成 `{scope_key, year, num, den, source_ref}`；同一 `scope_key+year` 的分子分母合并为一条。
3. **医疗部格式**：`医疗部数据提取.xlsx` 中 `20XX年统计数据` 列可能是 `分子:0；分母:908` 这种文本 → 用正则 `分子[:：]\s*(\d+(\.\d+)?)` 和 `分母[:：]\s*(\d+(\.\d+)?)` 解析；纯数字则当作分子。

### 步骤 4：预检台账（给人看）
`docs/reports/存量指标与SQL清洗预检台账.md` 按以下章节输出：
1. **总览**：总表 CHAPTER/METRIC/ATOM 数；科室实例数（按 `source_type`）；SQL 数（按 `sql_role`）；免统数；未匹配数。
2. **需要人工处理的清单**（最重要，每类一张表，列出 `source_ref`、指标名、科室、问题说明）：
   - 未匹配指标（`MANUAL_NEEDED`）；
   - `DS_CONFLICT` / `DS_UNKNOWN`；
   - `PERIOD_CONFLICT`；
   - `SHAPE_GUESS`；
   - 正式 SQL 目录比对结果为 `DIFF` 的；
   - 异常行、重复实例、同期多 SQL。
3. **各专业明细**：每个专业一张小表（指标数、实例数、SQL 数、SAME/DIFF/NOT_FOUND、免统数）。
4. **历史基线覆盖情况**：有 2025 年总表数据的指标数、有专科表年度数字的实例数。

`docs/reports/存量指标与SQL清洗预检台账.xlsx`：每个“需要人工处理的清单”一个 Sheet，外加 `总览`、`科室实例`（全部实例，不含 SQL 原文）、`SQL清单`（不含 SQL 原文，只含 sql_key、角色、年份、数据源、标记、比对状态）三个 Sheet。表头加粗、冻结首行、自动筛选。

### 步骤 5：单元测试 `test_crosscheck.py`
- `sqls.json` 中每条记录的 `formal_file_match` 都不是 `null`，`status` 属于 `SAME/DIFF/NOT_FOUND`；
- `baseline_values.json` 的 `master_values` 中存在 `code == "0.25.252.1"`、`year == 2025`、`num_value == 84.0` 的记录；
- 台账 md 和 xlsx 文件存在且大小 > 1KB。

## 7. 验收命令
```powershell
python migration/00c_crosscheck_and_report.py
python -m pytest migration/tests -q
python -c "import json;d=json.load(open('migration/dist/sqls.json',encoding='utf-8'));from collections import Counter;print(Counter(s['formal_file_match']['status'] for s in d['sqls']))"
git status --short
```

## 8. 回执必须包含
- 命令真实输出；
- `SAME / DIFF / NOT_FOUND` 数量，`DIFF` 前 20 条清单；
- 台账“需要人工处理的清单”每类的数量；
- 提交号。

提交信息：`TASK-03: 正式SQL比对、历史基线与预检台账`

> **TASK-03 完成后由主控 AI 核查台账，并与用户一起处理“需要人工处理的清单”（尤其是未匹配指标写入 `manual_mapping.json`），确认后才进入 TASK-04。**
