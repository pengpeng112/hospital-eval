# TASK-01：总表三层结构解析与公共解析库

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 1 · 第 1 次 |
| 依赖 | 无 |
| 预计改动 | 6~8 个文件 |
| 是否连数据库 / 服务器 | **否**（纯本机只读 Excel） |

## 1. 目标
解析 `数据指标导出调整版.xlsx` 的 4,718 行，输出 `migration/dist/master_rows.json` 和 `migration/dist/metric_dict.json`，并写好后续任务共用的解析工具函数与单元测试。

## 2. 开工前必读（按顺序）
1. `AGENTS.md`
2. `04_开发契约与编码规范.md` 第 3、4、6.1、6.2、7.1、7.3、7.4、7.5、7.6、7.7 节
3. 本文件

## 3. 允许新建 / 修改的文件（白名单，其他文件一律不许动）
- `migration/requirements.txt`
- `migration/common/__init__.py`
- `migration/common/excel_utils.py`
- `migration/00a_parse_master.py`
- `migration/tests/test_excel_utils.py`
- `migration/tests/test_parse_master.py`
- `migration/dist/master_rows.json`（脚本生成）
- `migration/dist/metric_dict.json`（脚本生成）
- `docs/reports/TASK01_总表解析报告.md`（脚本生成）
- `docs/task_receipts/TASK_01_回执.md`

## 4. 禁止事项
- 禁止修改 `E:\三甲复审\` 下任何文件（只读打开：`openpyxl.load_workbook(path, read_only=True, data_only=True)`）。
- 禁止按“看起来差不多”改数字；统计数字与本文件不一致时，**如实写进回执**，不要凑数。
- 禁止安装 `requirements.txt` 以外的包。

## 5. 操作步骤

### 步骤 1：依赖文件
新建 `migration/requirements.txt`，内容固定为：
```text
openpyxl>=3.1
psycopg[binary]>=3.1
python-dotenv>=1.0
pytest>=8.0
```
执行：`python -m pip install -r migration/requirements.txt`

### 步骤 2：公共库 `migration/common/excel_utils.py`
必须实现以下函数（函数名、参数、返回值**照抄**，TASK-02/03 会直接调用）：

| 函数 | 作用 | 规则出处 |
| :--- | :--- | :--- |
| `strip_sql_comments(text: str) -> str` | 去注释（尊重单引号字符串与双引号标识符），去首尾空白和末尾分号 | 04 §7.4 |
| `classify_cell(value) -> str` | 返回 `EMPTY` / `NUMBER` / `SQL` / `COMMENT_ONLY` / `DIRTY` / `TEXT` | 04 §7.3 |
| `detect_result_shape(sql_executable: str) -> tuple[str, bool]` | 返回 `("DTL"或"SUM", 是否猜测)` | 04 §7.5 |
| `extract_output_columns(sql: str) -> list[str]` | 提取 `AS 别名` 列表，去重保序 | 04 §7.5 |
| `header_year_range(header: str) -> tuple[int, int]` | 表头推年份范围 | 04 §7.6 |
| `literal_year_range(sql: str) -> tuple[int, int] \| None` | SQL 日期字面量推年份范围，推不出返回 `None` | 04 §7.6 |
| `final_year_range(header: str, sql: str) -> tuple[int, int, str \| None]` | 返回 `(year_from, year_to, period_flag)`，已做 `max(…, 2022)` | 04 §7.6 |
| `route_datasource(sql: str) -> tuple[str, str \| None, str]` | 返回 `(datasource_key, ds_flag, emr_sys)` | 04 §7.7 |
| `extract_alias_code(name: str) -> str \| None` | 提取专科代号（先去全部空白） | 04 §7.1 |
| `normalize_metric_name(name: str) -> str` | 标准化指标名称 | 04 §7.10 |
| `normalize_code(code: str) -> str` | 去掉编号末尾的 `(22)`、`(ps)` 等括号后缀 | 04 §7.1 |
| `split_dept_range(text: str) -> list[str]` | 按 `、，,;；` 和换行拆分并去空白、去空串 | 04 §7.2 |
| `map_extract_mode_from_source(text: str) -> tuple[str, bool]` | 总表“数据来源”→ `(extract_mode, is_applicable)` | 04 §7.1 |
| `map_extract_mode_from_raw(text: str, fallback: str) -> str` | 专科表“提取方式”→ `extract_mode` | 04 §7.9 |
| `detect_skip(*texts) -> tuple[bool, str \| None]` | 免统判定 | 04 §7.8 |
| `write_json(path, obj)` | UTF-8、`ensure_ascii=False`、`indent=1`，自动建目录 | 04 §6 |

> 每个函数写中文 docstring，说明规则来源（“见 04 §7.x”）。

### 步骤 3：主脚本 `migration/00a_parse_master.py`
1. 源文件路径写成常量：`SRC = r"E:\三甲复审\1、三甲复审统计指标\数据指标导出调整版.xlsx"`；不存在则打印错误并 `sys.exit(2)`。
2. 读取 `sheet1`，从第 3 行开始逐行读 A~K 列，空编号行跳过（计数记入 `meta.counts.skipped_empty`）。
3. 按 04 §7.1 生成每行 `row_no / code / name / row_type / parent_code / level / metric_code / atom_role / values`。
   - `values` 键固定为 `"2022"`、`"2023"`、`"2024"`、`"2025"`，值为 `{"data": 文本或 null, "source": 文本或 null}`；数字统一转成字符串（`84.0` 保持 `"84.0"`）。
4. 汇总每个 METRIC 下的 ATOM，决定 `atom_role` 和 `num_atom_code / den_atom_code / extra_atoms`。
5. 按 04 §6.2 生成 `metric_dict.json` 的 `metrics`，字段一个不少。
6. 写 `master_rows.json`、`metric_dict.json`。
7. 生成 `docs/reports/TASK01_总表解析报告.md`，内容包括：
   - 各类型行数（CHAPTER / METRIC / ATOM / TOTAL）；
   - ATOM 个数分布（0 / 1 / 2 / ≥3），并列出 ≥3 和 0 的指标编号与名称；
   - `extract_mode` 分布、`is_applicable=false` 数量；
   - `alias_code` 非空数量；
   - 重复的 `metric_code`（如有）；
   - 找不到父章节的行（如有）。
8. 控制台最后一行打印：`OK CHAPTER=<n> METRIC=<n> ATOM=<n> TOTAL=<n>`。

### 步骤 4：单元测试
- `test_excel_utils.py` 至少包含以下用例（全部必须通过）：
  1. `strip_sql_comments("SELECT '--不是注释' AS a FROM dual -- 注释")` == `"SELECT '--不是注释' AS a FROM dual"`
  2. `strip_sql_comments("/* 块 */ SELECT 1 FROM dual;")` == `"SELECT 1 FROM dual"`
  3. `classify_cell("-- 手工统计项,不生成 SQL")` == `"COMMENT_ONLY"`
  4. `classify_cell("https://pmi.NAME")` == `"DIRTY"`；`classify_cell("sql")` == `"DIRTY"`
  5. `classify_cell(129)` == `"NUMBER"`；`classify_cell("  33 ")` == `"NUMBER"`
  6. `classify_cell("WITH a AS (SELECT 1 FROM dual) SELECT * FROM a")` == `"SQL"`
  7. `literal_year_range("... DATE '2024-06-01' ... DATE '2026-01-01'")` == `(2025, 2025)`
  8. `literal_year_range("... DATE '2022-01-01' ... DATE '2025-01-01'")` == `(2022, 2024)`
  9. `final_year_range("2021-2024分母", "... DATE '2025-01-01' ... DATE '2026-01-01'")` == `(2025, 2025, "PERIOD_CONFLICT")`
  10. `route_datasource("SELECT 1 FROM MEDREC.PAT_VISIT v JOIN COMM.DEPT_DICT d ON 1=1")` == `("HIS_RO", None, "NONE")`
  11. `route_datasource("SELECT 1 FROM HIS.PAT_VISIT v JOIN MTL.X m ON 1=1 JOIN JHEMR.Y j ON 1=1")` == `("ODS_RO", None, "MTL+JHEMR")`
  12. `route_datasource("SELECT 1 FROM HIS.A a JOIN MEDREC.B b ON 1=1")[1]` == `"DS_CONFLICT"`
  13. `extract_alias_code("出院时脑梗死患者抗栓治疗率（NEU-STK- 15A）")` == `"NEU-STK-15A"`
  14. `detect_result_shape("SELECT x AS 年份, COUNT(*) AS 分子 FROM t GROUP BY x")` == `("SUM", False)`
  15. `detect_result_shape("SELECT a AS 患者ID FROM t")` == `("DTL", False)`
  16. `detect_skip("本科室无手术，请外科统计")[0]` is `True`
  17. `normalize_code("25.2.3.3.1(22)")` == `"25.2.3.3.1"`
- `test_parse_master.py`：读取生成的 `migration/dist/metric_dict.json` 和 `master_rows.json`，断言：
  - `TOTAL == 4718`、`CHAPTER == 410`、`METRIC == 1485`、`ATOM == 2823`；
  - 编号 `25.2.3.3.5.1.1` 的 `num_atom_code == "0.25.252.1"`、`den_atom_code == "0.25.252.2"`、`alias_code == "NEU-EPI-01"`；
  - 编号 `25.2.3.3.26.3`（ICU-05）的 `extra_atoms` 长度为 4；
  - 每个 METRIC 都有非空 `chapter_code` 和 `chapter_path`。

## 6. 验收命令（全部在项目根目录执行，输出原样贴进回执）
```powershell
python migration/00a_parse_master.py
python -m pytest migration/tests -q
python -c "import json;d=json.load(open('migration/dist/metric_dict.json',encoding='utf-8'));print(d['meta']['counts'])"
git status --short
```
**预期**：
- 第 1 条最后一行为 `OK CHAPTER=410 METRIC=1485 ATOM=2823 TOTAL=4718`；
- pytest 全部通过（0 failed）；
- `atoms_2=1311, atoms_1=169, atoms_3plus=5`（若不同，如实写明并列出差异指标）。

## 7. 回执必须包含
- 上面 4 条命令的真实输出；
- 报告中“ATOM ≥3 / =0 的指标清单”“重复编号”“找不到父章节的行”三项的内容（没有就写“无”）；
- `git commit` 的提交号（`git log --oneline -n 1`）。

提交信息：`TASK-01: 总表三层结构解析与公共解析库`
