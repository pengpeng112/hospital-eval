# TASK-01 回执：总表三层结构解析与公共解析库

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 08:20 / 2026-11-15 08:55（Asia/Shanghai）
- 状态：已完成
- Git 提交号：见本文档末尾（提交后回填）

## 一、前置检查结果

> 说明：`docs/tasks/TASK_01.md` 中**没有**名为"前置检查"的节（该文件各节为：目标/开工前必读/允许新建修改的文件/禁止事项/操作步骤/验收命令/回执必须包含）。按执行规则第 3 条，我执行了本任务的**隐含前置检查**并全部通过，未因此停止：

```text
$ python --version
Python 3.14.4
$ python -c "import sys; print(sys.executable)"
C:\Users\Administrator\AppData\Local\Python\pythoncore-3.14-64\python.exe
$ ls "E:\三甲复审\1、三甲复审统计指标\数据指标导出调整版.xlsx"
E:\三甲复审\1、三甲复审统计指标\数据指标导出调整版.xlsx   （存在，只读打开）
$ python -c "import openpyxl; print('openpyxl', openpyxl.__version__)"
openpyxl 3.1.5
```

开工前只读预扫描（未修改任何源文件）：sheet 名为 `sheet1`，共 4,720 行（数据 3~4,720 = 4,718 行）；类型列恰好为 章节 410 / 指标 1,485 / 基础指标 2,823；"数据来源"列全部取值仅为契约所列 5 种文本（医院系统提取 / 医院系统提取+手工统计 / 手工统计 / 省级系统提取 / 不适用）；与 AGENTS.md 记载一致。

## 二、新建 / 修改的文件

| 文件 | 新建/修改 | 说明 |
| :--- | :--- | :--- |
| `migration/requirements.txt` | 新建 | 4 个固定依赖（04 §4.6 原文） |
| `migration/common/__init__.py` | 新建 | 包标识 |
| `migration/common/excel_utils.py` | 新建 | 16 个公共函数，中文 docstring 均注明 04 §规则出处 |
| `migration/00a_parse_master.py` | 新建 | 总表解析主脚本（步骤 3 全部 8 条要求） |
| `migration/tests/test_excel_utils.py` | 新建 | 38 个用例（含步骤 4 要求的 17 条必测用例） |
| `migration/tests/test_parse_master.py` | 新建 | 产物断言（计数/目标指标/ICU-05/章节链/原子角色/values 键） |
| `migration/dist/master_rows.json` | 新建（脚本生成） | 4,718 行原样结构，meta.counts 与 duplicate_codes |
| `migration/dist/metric_dict.json` | 新建（脚本生成） | 1,485 条指标字典 |
| `docs/reports/TASK01_总表解析报告.md` | 新建（脚本生成） | 解析台账（7 节） |
| `docs/task_receipts/TASK_01_回执.md` | 新建 | 本回执 |

未触碰白名单以外任何文件；`E:\三甲复审\` 下零改动（源 Excel 仅 `load_workbook(read_only=True, data_only=True)` 只读打开后关闭）。

## 三、验收命令与真实输出

```text
$ python migration/00a_parse_master.py
OK CHAPTER=410 METRIC=1485 ATOM=2823 TOTAL=4718
```
（stderr 另有 openpyxl 无害告警一行：`UserWarning: Workbook contains no default style, apply openpyxl's default`——源工作簿缺默认样式定义所致，不影响数据读取，如实记录。）

```text
$ python -m pytest migration/tests -q
......................................                                   [100%]
38 passed in 0.26s
```

```text
$ python -c "import json;d=json.load(open('migration/dist/metric_dict.json',encoding='utf-8'));print(d['meta']['counts'])"
{'metrics': 1485, 'with_alias_code': 692, 'atoms_2': 1311, 'atoms_1': 169, 'atoms_3plus': 5, 'atoms_0': 0}
```

```text
$ git status --short
?? docs/reports/
?? migration/
```
（`--untracked-files=all` 展开为且仅为上表 10 个文件中的 9 个新文件 + 本回执；`__pycache__/`、`target/`、`node_modules/`、`*.csv`、`.env` 均已被 .gitignore 排除。）

## 四、关键数字（按任务说明书"回执必须包含"逐项填写）

- **第 1 条验收命令最后一行**：`OK CHAPTER=410 METRIC=1485 ATOM=2823 TOTAL=4718` ✓
- **pytest**：38 passed，0 failed ✓（含 TASK_01.md 步骤 4 列出的全部 17 条必测断言）
- **atoms 分布**：`atoms_2=1311, atoms_1=169, atoms_3plus=5`，与任务说明书预期**完全一致**，无差异指标；另 atoms_0=0
- **"ATOM ≥3 / =0 的指标清单"**（报告 §2.1/§2.2）：
  - ≥3 共 5 条：`25.2.3.1.1.3.2` 院区及床位数（14 个原子）、`25.2.3.1.1.3.3` 评审周期内新增量（8）、`25.2.3.2.2.1.33` 危急值报告时间（3）、`25.2.3.3.1.6(22)` 检验前周转时间中位数（3，其 3 个原子编号同样带 (22) 后缀）、`25.2.3.3.26.3` 感染性休克患者集束化治疗（bundle）完成率（ICU-05）（4，extra_atoms 长度 4 断言通过）——与 04 §7.1 所列"已知 5 条"完全一致
  - =0：**无**
- **重复编号**：无（`meta.duplicate_codes = []`）
- **找不到父章节的行**（报告 §6）：仅 1 行——Excel 第 3 行 `CHAPTER 25.2 指标`（三层结构的根节点，属预期；第一/二/三部分均以 25.2 为父章节正常挂接）
- **报告路径**：`docs/reports/TASK01_总表解析报告.md`
- 补充分布数字（报告 §3/§4）：extract_mode = SYS 598 / HYBRID 583 / MANUAL 302 / PROV 2；is_applicable=false（不适用）269 条；alias_code 非空 692/1485 条

## 五、安全自检（逐项勾选，未做到的写明原因）

- [x] Oracle 写入数量为 0（本任务未连接任何数据库，Oracle/PostgreSQL 均未接触）
- [x] 仓库、回执中无密码 / 密钥 / Token / deploy/.env 内容
- [x] 回执与仓库中无患者姓名、住院号、患者ID（总表为指标口径数据，无患者字段）
- [x] 本机 JAVA_HOME 未修改（`java -version` 实测仍为 `openjdk version "1.8.0_292"`，AdoptOpenJDK）
- [x] 只改动了任务说明书白名单内的文件（git status 逐文件核对，见第三节）
- [x] 未操作 10.10.8.75（本任务全程本机，未连接任何服务器）

## 六、待确认事项（文档没写清楚、我没有自行决定的地方）

1. **TASK_01.md 无"前置检查"节**：用户指令要求"先运行 TASK_01.md 第'前置检查'节的命令"，但该文件不存在此节。我按隐含口径执行了前置检查（源文件存在性、Python 3.14、openpyxl 可用）并全部通过，未中断任务。建议主控 AI 后续在 TASK_02~20 中明确该节。
2. **`parent_code` 存储形态**：04 §7.1 只规定父子匹配用"标准化编号"，未明确 `parent_code` 字段存原始编号还是标准化编号。我存储**父章节行的原始编号**（如 `25.2.3.3.1(22)`，该章节行在源表中真实带后缀），保证 `parent_code` 永远可回链到 `rows` 中某行的 `code` 列；前缀匹配与 `level` 计算则全部用标准化编号。若主控要求存标准化编号，只需改 `00a_parse_master.py` 两处赋值后重跑。
3. **`metric_code` 唯一性口径**：按原始编号（含后缀）判定唯一并查重；实测无重复。标准化编号层面同样无冲突（带后缀行均有独立原子组）。
4. **`map_extract_mode_from_source` 判定顺序**：契约 §7.1 的字面顺序会使"省级系统提取"因子串"系统提取"被误判为 SYS。我按"不适用 → 省级 → 系统+手工 → 系统 → 手工"的优先级实现（实测来源列仅 5 种文本，结果不受影响），已在 docstring 注明。
5. **`extract_alias_code` 正则实现**：契约 §7.1 的 `[A-Z]{2,6}(-[A-Z0-9]+)+` 为捕获组写法，Python `findall` 对捕获组只返回组内容（实测返回 `-15A`）。我改为等价的非捕获组 `[A-Z]{2,6}(?:-[A-Z0-9]+)+` 以返回完整代号（`NEU-STK-15A`），匹配语义与契约一致。
6. **`normalize_metric_name` 的前导编号剥离**：对"数字后紧跟字母"的前缀（如 `48h 内肠内营养…`）不剥离，视为名称本体（否则 `48` 会被误删）；数字/中文序号（`1.`、`15.1`、`（一）`、`(1)`）照剥。该函数对专科表与总表两侧对称生效，不影响 §7.10 匹配公平性。
7. **`values.data` 数字转字符串**：实测指标值多为全精度浮点（如 `59.1549295775`），按"数字统一转成字符串"如实保留全精度（04 §6.1 示例中的 `59.15` 为展示截断）。

## 七、未完成

无。
