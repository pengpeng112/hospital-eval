# TASK-02：专科表 / 第三章总表 / 医疗部表解析（科室实例 + SQL）

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 1 · 第 2 次 |
| 依赖 | TASK-01（需要 `migration/common/excel_utils.py` 与 `migration/dist/metric_dict.json`） |
| 预计改动 | 4~6 个文件 |
| 是否连数据库 / 服务器 | **否** |

## 1. 目标
把所有专科来源 Excel 解析成**科室实例**（`dept_scopes.json`）和**分子/分母 SQL**（`sqls.json`），处理好“一标多科”子行、免统、列错位、真假 SQL 甄别，并把每个科室实例匹配到总表指标编号。

## 2. 开工前必读
1. `AGENTS.md` 第一、二节
2. `04_开发契约与编码规范.md` 第 6.3、6.4、7.2~7.10 节
3. 本文件

## 3. 前置检查（不满足就停止并在回执中报告）
```powershell
Test-Path migration/common/excel_utils.py      # 必须 True
Test-Path migration/dist/metric_dict.json      # 必须 True
python -m pytest migration/tests -q             # 必须全部通过
```

## 4. 允许新建 / 修改的文件
- `migration/00b_parse_specialty.py`
- `migration/tests/test_parse_specialty.py`
- `migration/manual_mapping.json`（脚本首次生成；已存在则**只读不覆盖**）
- `migration/dist/dept_scopes.json`、`migration/dist/sqls.json`（脚本生成）
- `docs/reports/TASK02_专科表解析报告.md`（脚本生成）
- `docs/task_receipts/TASK_02_回执.md`
- 若发现 `excel_utils.py` 有 bug，**允许修改**，但必须同时补测试用例并在回执说明改了什么

## 5. 禁止事项
- 禁止按固定列号读取（除 04 §7.2 写明的“负责科室找不到时用第 7 列”这一个兜底）。
- 禁止把 `COMMENT_ONLY / NUMBER / DIRTY / TEXT` 单元格写进 `sqls`。
- 禁止修改 SQL 原文（`sql_annotated` 必须与单元格原文逐字一致）。
- 禁止修改源 Excel。

## 6. 数据源清单（全部只读）

| 来源 | 路径 | `source_type` | 读哪个 Sheet |
| :--- | :--- | :--- | :--- |
| 12 个单专业表 | `E:\三甲复审\1、三甲复审统计指标\` 下文件名含 `年版` 的 `.xlsx`（用 `glob` 找，**不含**子目录） | `ROOT_12` | 第 1 个 Sheet |
| 第三章总表 | `E:\三甲复审\1、三甲复审统计指标\02_重点专业指标\第三章重点专业质量控制指标.xlsx` | `CH3_SHEET` | 见下方规则 |
| 第三章职能指标 | 同上 | `CH3_FUNC` | Sheet 名以 `职能指标` 开头的 2 个 |
| 医疗部 | `E:\三甲复审\1、三甲复审统计指标\医疗部数据提取.xlsx` | `MEDICAL_DEPT` | 第 1 个 Sheet |
| 单病种 | `E:\三甲复审\1、三甲复审统计指标\单病种(按主要诊断_主要手术统计)2025年.xlsx` | `SINGLE_DISEASE` | 第 1 个 Sheet（见步骤 5） |
| 分工联络人表 | `E:\三甲复审\1、三甲复审统计指标\科室分工重点专业质量控制指标各科室联络人(1).xlsx` | — | `Sheet1` |

**第三章总表 Sheet 选择规则**：
- 取 Sheet 名开头的中文序号（如 `三、`、`二十一、`）；
- 若某个 `ROOT_12` 文件名以同一序号开头 → **跳过**这个 Sheet（以根目录单专业表为准）；
- 跳过名为 `HIS统计肿瘤 勿动` 的 Sheet；
- 其余专业 Sheet 用 `CH3_SHEET`；`职能指标-*` 用 `CH3_FUNC`。
- 预期 `CH3_SHEET` 恰好 14 个：一、二、四、六、七、八、十一、十七、十九、二十、二十二、二十三、二十四、二十五（实际不同则如实报告）。

## 7. 操作步骤

### 步骤 1：读取分工联络人表
- `Sheet1` 第 1 行是表头：`第三章 重点专业质量控制指标 | 科室 | 联络员姓名 | 职能部门对接人员 | 涉及科室`。
- A 列专业名为空的行继承上一行专业名（合并单元格）。
- 建立映射：`(专业序号, 科室) → {liaison_name, func_contact_name, dept_range}`，以及 `专业序号 → 第一行的联络信息`（兜底用）。
- 专业序号 = 专业名开头的中文序号（如 `五、`）。

### 步骤 2：逐个工作簿解析（`ROOT_12` / `CH3_SHEET` / `CH3_FUNC` / `MEDICAL_DEPT`）
对每个要读的 Sheet：
1. 用 `read_only=False` 打开（需要读字体颜色）。如果某个 Sheet 的 `max_column > 200`，只读前 60 列（防止 16,384 列的副表拖慢）。
2. 按 04 §7.2 识别第 2 行表头 → 得到标准字段到列号的映射；把映射写进报告（每个 Sheet 一行）。
3. 从第 3 行起按 04 §7.2 判定行类型（分组标题 / 主行 / 子行 / 空行 / 异常行）。
4. 对每个**主行**和**子行**生成一个科室实例：
   - `resp_dept_name`：本行负责科室，去空白；主行负责科室为空时用“未指定”。
   - `his_dept_names`：本行“科室范围”拆分结果；本行为空时，用分工表中 `(专业序号, 科室)` 的 `dept_range`；仍为空则 `[]`。
   - `extract_mode` 按 04 §7.9；`skip_flag` 按 04 §7.8；`font_color` 按 04 §7.2。
   - `liaison_name` / `func_contact_name`：先按 `(专业序号, 科室)` 查分工表，查不到用专业兜底；`MEDICAL_DEPT` 与 `CH3_FUNC` 留空。
   - `source_ref` = `文件名!Sheet名!R行号`。
   - 指标匹配按 04 §7.10 得到 `metric_code` 和 `match_method`。
   - `scope_key` = `metric_code|resp_dept_name`；未匹配时用 `UNMATCHED:source_ref|resp_dept_name`。
   - 同一个 `scope_key` 出现两次（同指标同科室重复行）→ 保留第一个，第二个记入报告“重复实例”。
5. 对本行每个表头含 `分子` / `分母` 的列，按 04 §7.3 分类：
   - `SQL` → 生成一条 sql 记录（字段见 04 §6.4）：`sql_annotated` = 原文；`sql_executable` = `strip_sql_comments(原文)`；`sql_role`、`year_from/year_to/period_flag`、`datasource_key/ds_flag/emr_sys`、`output_columns` 按 04 §7.5~7.7；`source_ref` = `文件名!Sheet名!R行C列`；`formal_file_match = null`；
   - `sql_key` = `scope_key|NUM或DEN|year_from-year_to`；若重复（同一实例同角色同年份出现两条 SQL）→ 第二条的 `sql_key` 末尾追加 `|2`，并在报告“同期多 SQL”中列出；
   - 其余类型 → 写入 `non_sql_cells`（`preview` 取前 60 字）。

### 步骤 3：`manual_mapping.json`
- 文件不存在时：把所有 `unmatched` 写成 `{"<source_ref>": null, ...}` 生成该文件；
- 文件已存在时：只读取其中非 null 的映射使用，**不覆盖**。

### 步骤 4：输出
- `dept_scopes.json`、`sqls.json` 按 04 §6.3、§6.4 的结构写出，`meta.counts` 填写完整。

### 步骤 5：单病种文件
- 先打印该文件前 5 行看结构，在报告中原样描述其列结构；
- 若其表头格式与 04 §7.2 一致 → 按同样方式解析为 `SINGLE_DISEASE`；
- 若不一致 → **不要猜**，只在报告中写“单病种文件结构不同，待人工确认”，不生成实例。

### 步骤 6：报告 `docs/reports/TASK02_专科表解析报告.md`
必须包含以下表格：
1. **按来源文件/Sheet 统计**：表头映射结果、主行数、子行数、科室实例数、免统数、SQL 数、COMMENT_ONLY / NUMBER / DIRTY / TEXT 数；
2. **指标匹配统计**：CODE / NAME / MANUAL / MANUAL_NEEDED 数量，以及全部 `unmatched` 明细与候选；
3. **SQL 统计**：按 `sql_role`、`datasource_key`、`emr_sys` 分布；`DS_CONFLICT`、`DS_UNKNOWN`、`PERIOD_CONFLICT`、`SHAPE_GUESS` 明细（`source_ref` + 表头）；
4. **一标多科统计**：每个专业的科室数与实例数；
5. **异常行、重复实例、同期多 SQL** 明细。

### 步骤 7：单元测试 `test_parse_specialty.py`
读取生成的 JSON 断言：
- `ROOT_12` 来源的主行+子行实例总数为 **1452**（不一致时测试失败，回执中说明原因，**不要改断言凑数**）；
- `ROOT_12` 来源中 `match_method == "MANUAL_NEEDED"` 的数量 ≤ 1；
- 感染专业（文件名含 `十六、`）的 SQL 记录中，表头为 `分母SQL` 的记录 `sql_role` 以 `DEN_` 开头；
- 脑损伤（文件名含 `二十一、`）不存在 `sql_annotated` 以 `https://` 开头的记录；
- 神经（文件名含 `五、`）表头为 `2025分子` 的列里，数字单元格都出现在 `non_sql_cells`（kind=`NUMBER`）而不在 `sqls`；
- 所有 sql 记录的 `sql_executable` 都以 `SELECT` 或 `WITH`（忽略大小写、前导括号）开头；
- 呼吸（文件名含 `三、`）的 sql 中 `NUM_SUM + DEN_SUM` 数量 > `NUM_DTL + DEN_DTL` 数量。

## 8. 验收命令
```powershell
python migration/00b_parse_specialty.py
python -m pytest migration/tests -q
python -c "import json;d=json.load(open('migration/dist/dept_scopes.json',encoding='utf-8'));print(d['meta']['counts'])"
python -c "import json;d=json.load(open('migration/dist/sqls.json',encoding='utf-8'));print(d['meta']['counts'])"
git status --short
```
**预期**：脚本退出码 0；pytest 全部通过；`ROOT_12` 实例 1452、SQL 记录数约 2,900~3,100 条（以实际为准如实填写）。

## 9. 回执必须包含
- 上述命令真实输出；
- 报告第 2 项（未匹配清单）和第 3 项（DS_CONFLICT / DS_UNKNOWN / PERIOD_CONFLICT 数量）的摘要；
- `CH3_SHEET` 实际读取的 Sheet 名列表；
- 单病种文件的处理结论；
- 提交号。

提交信息：`TASK-02: 专科表科室实例与SQL解析`
