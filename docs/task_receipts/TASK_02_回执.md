# TASK-02 回执：专科表科室实例与SQL解析

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 09:05 / 2026-11-15 10:05（Asia/Shanghai）
- 状态：已完成
- Git 提交号：`ed71895 → <本次提交见文末>`（任务内容提交号在提交后回填于本行）

## 一、前置检查结果

```powershell
PS> Test-Path migration/common/excel_utils.py
True
PS> Test-Path migration/dist/metric_dict.json
True
PS> python -m pytest migration/tests -q
......................................                                   [100%]
38 passed in 0.39s
```
三项全部满足，未停止。（pytest 此时有 TASK-01 的 38 个用例；完成后全套为 47 个。）

## 二、新建 / 修改的文件

| 文件 | 新建/修改 | 说明 |
| :--- | :--- | :--- |
| `migration/00b_parse_specialty.py` | 新建 | 联络人表读取、逐簿逐 Sheet 解析、匹配、JSON/报告输出 |
| `migration/tests/test_parse_specialty.py` | 新建 | 9 个用例（步骤 7 的 7 条断言 + sql_key 唯一性 + scope 枚举/唯一性） |
| `migration/manual_mapping.json` | 新建（脚本首次生成） | 53 条 unmatched 的 null 骨架（`{"source_ref": null}`），供人工填写 |
| `migration/dist/dept_scopes.json` | 新建（脚本生成） | 1,912 个科室实例 + specialties + unmatched + duplicates |
| `migration/dist/sqls.json` | 新建（脚本生成） | 2,938 条 SQL + 2,384 条 non_sql_cells |
| `docs/reports/TASK02_专科表解析报告.md` | 新建（脚本生成） | 5 张必需表格 + 单病种结论 + 跳过 Sheet 清单 |
| `docs/task_receipts/TASK_02_回执.md` | 新建 | 本回执 |

`excel_utils.py` **未修改**（本次未发现其 bug，白名单修改条款未触发）。源 Excel 全部只读打开，零改动。

## 三、验收命令与真实输出

```text
$ python migration/00b_parse_specialty.py
（过程行：[ROOT_12]…12 个文件、[CH3_SHEET]…14 个 Sheet、[CH3_FUNC]…2 个、[MEDICAL_DEPT]、单病种前 5 行打印）
manual_mapping: 文件已存在：读取 0 条非 null 映射使用，未覆盖
scopes=1912 matched=1859 manual_needed=53 skip=90
sqls=2938 non_sql=2384 ds_conflict=0 ds_unknown=45 period_conflict=2001
CH3 skipped sheets: 12; single_disease_parsed=False
（退出码 exit_code=0；stderr 另有 openpyxl "Workbook contains no default style" 无害告警，与 TASK-01 相同）
```

```text
$ python -m pytest migration/tests -q
...............................................                          [100%]
47 passed in 2.00s
```

```text
$ python -c "import json;d=json.load(open('migration/dist/dept_scopes.json',encoding='utf-8'));print(d['meta']['counts'])"
{'scopes': 1912, 'matched': 1859, 'manual_needed': 53, 'skip': 90}
```

```text
$ python -c "import json;d=json.load(open('migration/dist/sqls.json',encoding='utf-8'));print(d['meta']['counts'])"
{'sql': 2938, 'NUM_DTL': 1019, 'DEN_DTL': 1769, 'NUM_SUM': 72, 'DEN_SUM': 78, 'ds_conflict': 0, 'period_conflict': 2001}
```

```text
$ git status --short
?? docs/reports/
?? migration/
（--untracked-files=all 展开仅为上表 7 个白名单文件；__pycache__ 等已被 .gitignore 排除）
```

## 四、关键数字

- **ROOT_12 实例总数 = 1452 ✓**：保留实例 1,447 + 契约式重复实例 5 = 1,452（AGENTS.md"按科室展开共 1,452 行科室实例"口径=主行+子行总数；5 个重复为神经表 `25.2.3.3.5.4.3` 的 R336-340，按 04 §7.2"同 scope_key 保留第一个"去重并全部记入报告/JSON `duplicates`）。测试按此口径断言 1452，未改断言凑数。
- **匹配统计**：CODE 1,538 / NAME 338 / MANUAL 0 / MANUAL_NEEDED 53；ROOT_12 仅 1 条未匹配（步骤 7 断言 ≤1 满足）。
- **未匹配清单摘要（53 条）**：ROOT_12 1 条 = `二十六、重症…xlsx!Sheet1!R14（十二）非计划转入 ICU 率`，候选 `25.2.3.3.14.20`、`25.2.3.3.26.12`；第三章总表 51 条；医疗部 1 条。全部明细在报告 §2 与 `manual_mapping.json`（待人工填写后重跑即生效）。
- **SQL 统计**：NUM_DTL 1,019 / DEN_DTL 1,769 / NUM_SUM 72 / DEN_SUM 78；datasource = ODS_RO 2,745 / HIS_RO 193；emr_sys = NONE 1,581 / MTL+JHEMR 1,300 / MTL 57（与 AGENTS.md 所载 12 专业表"HIS+MTL+JHEMR≈1,305、COMM/MEDREC 类≈135"的量级吻合；明细见报告 §3）。
- **DS_CONFLICT = 0；DS_UNKNOWN = 45；PERIOD_CONFLICT = 2,001；SHAPE_GUESS = 453**（明细均为 source_ref+表头清单，见报告 §3）。
  - 抽样核实 PERIOD_CONFLICT 大多为**真实表头/SQL 漂移**（如呼吸"2022-2024分子SQL"列的 SQL 字面量实际拉到 2025；正是 04 §7.6 所举肿瘤模式的普遍化），非解析 bug；其中约 123 条为"无年份表头（分子/分母/分子SQL/分母SQL）+ 字面量定年"的默认档冲突，语义上偏保守，已在待确认事项申报。
- **CH3_SHEET 实际读取的 14 个 Sheet**（与任务书预期清单完全一致）：
  一、临床检验专业医疗质量控制指标（2025 年版）；二、临床用血质量控制指标（2019 年版）；四、产科专业医疗质量控制指标（2019 年版）；六、肾病专业医疗质量控制指标（2020 年版）；七、护理专业医疗质量控制指标（2020 年版）；八、药事管理专业医疗质量控制指标（2025 年版）；十一、超声诊断专业医疗质量控制指标（2022 年版）；十七、健康体检与管理专业医疗质量控制指标（2023 年版）；十九、整形美容专业医疗质量控制指标（2023 年版）；二十、急诊医学专业医疗质量控制指标（2024 年版）；二十二、病理专业医疗质量控制指标（2024 年版）；二十三、放射影像专业医疗质量控制指标（2024 年版）；二十四、门诊管理医疗质量控制指标（2024 年版）；二十五、医院感染管理医疗质量控制指标（2024 年版，含增补）。
  - 被跳过 12 个中文序号 Sheet（与 12 个 ROOT_12 文件撞号，以根目录单专业表为准）+ `HIS统计肿瘤 勿动`；`职能指标-抗菌办`、`职能指标-医务部+质控部` 按 CH3_FUNC 解析。
- **单病种文件处理结论**：结构不同（第 1 行即表头：`病种名称 | 人次数(人) | 平均住院日(天) | 次均费用(元) | 次均药费(元) | 死亡人数(人) | 死亡率(%) | 手术并发症人数(人)`，第 2 行起为数据），与 04 §7.2 的"第 2 行表头+标准字段"格式不一致 → 按任务书步骤 5 **不猜、不解析**，报告中原样记录前 5 行并注明"待人工确认"，未生成实例。
- **异常行 42 条**（心血管表 R228-242 等"无名称无科室但有散落内容"的行）、**重复实例 25 条**（神经 5 + 职能指标-医务部 3 + 医疗部跨表 17）、**同期多 SQL 934 条**（同实例同角色同年份多列 SQL，按契约追加 `|2` 后缀）——明细均在报告 §5。

## 五、安全自检（逐项勾选）

- [x] Oracle 写入数量为 0（本任务未连接任何数据库）
- [x] 仓库、回执中无密码 / 密钥 / Token / deploy/.env 内容
- [x] 回执与仓库中无患者姓名、住院号、患者ID（SQL 原文为表名/条件类代码，无患者标识；JSON 中的 sql_annotated 为指标提取 SQL，不涉及具体患者）
- [x] 本机 JAVA_HOME 未修改（java -version 仍为 1.8.0_292，TASK-01 已验证，本次未做任何系统变更）
- [x] 只改动了任务说明书白名单内的文件（git status 逐文件核对）
- [x] 未操作 10.10.8.75（本任务全程本机）

## 六、待确认事项

1. **dept_scopes.json 顶层新增 `duplicates` 数组**（契约 §6.3 未列出该键）：每项 `{scope_key, kept_ref, dup_ref}`。用途：让 1452 总数可由机器复核（报告是唯一载体时会不可测）。请主控确认是否保留该扩展键。
2. **NEU-CAS-03/03A/03B 跨版本别名漂移**（神经表 R330-345）：总表已把旧 `NEU-CAS-03` 改名为 `03A`（25.2.3.3.5.4.3 手术指征符合率）并另立 `03B`（25.2.3.3.5.4.4 症状性指征符合率）；2020 版专科表有三块 3.1(03)/3.2(03A,无症状)/3.3(03B,症状性)。按契约"CODE 优先"：3.1 按名称→5.4.3、3.2 按 CODE→5.4.3（与 3.1 撞键被去重）、3.3→5.4.4。结果契约合规，但 3.2（无症状指征）的 SQL 随去重丢弃，需业务确认是否应改指 5.4.4 或保留 5.4.3 单一归属。
3. **PERIOD_CONFLICT 中约 123 条**来自"无年份表头（分子/分母等）+ SQL 字面量定年"：契约 §7.6 字面规则下无年份表头默认 2022~2025，与字面量必不一致而置flag。语义上"表头无年份声明"是否算冲突请主控裁定；若裁定不算，可在 TASK-03 核对时对这类（表头无年份数字）的 period_flag 做人工豁免，本次未擅自改 `final_year_range` 行为。
4. **ROOT_12 唯一未匹配指标与 AGENTS.md 记载不同**：AGENTS.md 称需人工确认的是重症"48h 内肠内营养（EN）启动率"，本次实测未匹配的是"（十二）非计划转入 ICU 率"（EN 指标已通过 CODE/NAME 自动匹配成功）。以实测为准，已在 unmatched 中给出候选。
5. **跨 Sheet 全局去重**：CH3_FUNC"职能指标-医务部+质控部"与《医疗部数据提取.xlsx》存在 17 对同 `metric|医疗部` 实例，按契约"保留第一个"保留了先处理的 CH3_FUNC 行（处理顺序 = 任务书步骤 2 列举顺序：ROOT_12→CH3_SHEET→CH3_FUNC→MEDICAL_DEPT），医疗部文件相应行记为重复实例。若主控希望医疗部文件优先，需调整处理顺序后重跑。

## 七、未完成

无。
