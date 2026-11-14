# TASK-10：结果汇总、患者明细、病历原文调阅与总表回填导出接口

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 5 · 第 2 次 |
| 依赖 | TASK-09 |
| 预计改动 | 10~14 个 Java 文件 + 冒烟脚本 |
| 是否连数据库 / 服务器 | 本机 PostgreSQL；Oracle **只读**（病历原文接口）；**不连** 10.10.8.75 |

## 1. 目标
实现 04 §12.6 的全部接口：当期/历史汇总列表、科室分布、指标核验面板、患者明细分页与导出、院内 360 视图链接、MTL/JHEMR 病历文书列表与正文、4,718 行总表原格式回填导出。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 5.4、8.3、10、12.6、14 节
2. 本文件

## 3. 前置检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_09.ps1
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-server/src/main/java/com/hospital/eval/result/**`
- `hospital-eval-server/src/test/java/com/hospital/eval/result/**`
- `scripts/smoke/TASK_10.ps1`
- `docs/task_receipts/TASK_10_回执.md`

## 5. 禁止事项
- 病历接口禁止无患者条件查询；每条病历 SQL 都必须带 `患者ID+住院次数` 或 `住院号+住院次数` 绑定参数；禁止字符串拼接参数（必须用 `?` 绑定）。
- 禁止在日志中打印患者姓名、住院号、病历正文。
- 导出文件禁止写到服务器项目目录，直接以流返回。

## 6. 操作步骤

### 步骤 1：汇总列表 `GET /api/result/summary`
- 只查全院行 `scope_id = 0`；
- `batchId` 为空 → 条件 `is_current = true`；不为空 → 条件 `batch_id = :batchId`；
- 以 `eval_metric_dict` 为主表分页（按 `master_row_no`），LEFT JOIN 结果表，把多个年份的结果组装到 `years` Map（键为年份字符串）；没有结果的年份不出现在 Map 中；
- `zeroOnly=true` → 只返回请求年份中任一年 `numerator_val = 0` 或 `denominator_val = 0` 的指标；
- `chapterCode`、`specialty`、`extractMode`、`keyword` 过滤规则同 TASK-08 的 `/api/metric/list`；
- 返回字段严格按 04 §12.6。

### 步骤 2：科室分布 `GET /api/result/metric/{metricId}/scopes`
- 列出该指标全部科室实例（包括 `skip_flag=true` 的，前端显示“免统”）；每个实例按年组装结果（当期或指定批次）。

### 步骤 3：核验面板 `GET /api/result/metric/{metricId}/panel`
- `dict`：字典全部字段；
- `trend`：全院行当期结果按年份升序（2022~2025）；
- `sqls`：该指标所有 `is_active=true` 的 SQL（`sqlRole, deptName, periodStart, periodEnd, sqlAnnotated, versionNo`）；
- `auditLogs`：该指标所有结果行的复核日志，按时间倒序最多 100 条。

### 步骤 4：批次列表 `GET /api/result/batches`
- 按 `start_time` 倒序返回全部批次（含 `BATCH_BASELINE_IMPORT`、`BATCH_MANUAL`）。

### 步骤 5：患者明细 `GET /api/result/detail` 与导出
1. 先确定要查哪个批次的明细：
   - 请求带 `batchId` → 用它；
   - 否则：取该 `(metric_id, scope_id, year)` 当期结果行的 `batch_id`；`scope_id=0` 时，取各参与科室当期行的 `batch_id` 集合（全院明细 = 各科室当期明细的并集，**按去重键去重**后返回，即同一患者同一住院只返回一行，保留第一个科室的那行，并在 `ext.__depts` 中列出出现的全部科室名）；
2. `role=NUM`：
   - 若该实例是混合池（04 §9） → 查 `role_type='DEN' AND manual_selected = true`；
   - 否则查 `role_type='NUM'`；
3. `role=DEN` → `role_type='DEN'`；
4. `keyword` 模糊匹配 `inp_no`、`patient_id`、`patient_name`、`main_diag_code`、`main_diag_name`；
5. 返回 `columns`：固定列（`inpNo 住院号`、`patientId 患者ID`、`patientName 姓名`、`visitId 住院次数`、`mainDiagCode 主要诊断编码`、`mainDiagName 主要诊断名称`、`mainOperCode 第一手术编码`、`mainOperName 第一手术名称`、`admitDeptName 入院科室`、`dischargeDeptName 出院科室`、`admitTime 入院时间`、`dischargeTime 出院时间`、`emrDocTitle 文书名称`）+ 本页数据中 `ext_json` 出现过的所有键（`prop` 用 `ext.<键>`，`label` 用键名）；
6. 导出 `/api/result/detail/export`：同条件不分页，最多 100,000 行（超过返回 400 提示缩小范围），POI `SXSSFWorkbook` 流式写出；文件名 `明细_<指标编号>_<year>_<NUM|DEN>.xlsx`（URL 编码）；导出写 `sys_oper_log`（module=`RESULT`，action=`EXPORT_DETAIL`，记录行数，不记患者信息）。

### 步骤 6：院内 360 视图 `GET /api/emr/360-url`
- 读环境变量 `EMR_360_URL_TEMPLATE`；为空或仍含 `<HOSP_360_HOST>` → 返回 `{url:null}`；
- 否则把 `{patientId}`、`{visitId}`、`{inpNo}` 替换为 URL 编码后的参数值返回。

### 步骤 7：病历文书（用 `ODS_RO`，以下 SQL **照抄**，全部用 `?` 绑定参数，执行前同样经过 `ReadOnlySqlValidator`，`queryTimeout=30`）
> 这些表名和关联字段来自现有神经专业已核验 SQL（`五、神经系统疾病…xlsx`），不得自行更换。

**MTL 文书列表**（参数：住院号、住院次数）
```sql
SELECT * FROM (
  SELECT 'MTL' AS SYS,
         dix.UHID || '-' || TO_CHAR(doc.ID) AS DOC_ID,
         NVL(doc.DOC_TITLE, doc.DOC_TYPE_NAME) AS TITLE,
         doc.DOC_CREATE_DATE AS DOC_TIME
  FROM MTL.EPR_INPATIENT_DOCINDEX dix
  JOIN MTL.EPR_INPATIENT doc
    ON doc.UHID = dix.UHID
   AND NVL(doc.DELETEMARK, 0) = 0
  WHERE dix.P_INPATIENT_NO = ?
    AND dix.P_INPATIENT_TIMES = ?
  ORDER BY doc.DOC_CREATE_DATE
) WHERE ROWNUM <= 300
```

**MTL 文书正文**（参数：住院号、住院次数、UHID、文书 ID；`DOC_ID` 按**最后一个** `-` 拆成 UHID 与 ID）
```sql
SELECT NVL(doc.DOC_TITLE, doc.DOC_TYPE_NAME) AS TITLE,
       doc.DOC_CREATE_DATE AS DOC_TIME,
       content.段落内容 AS CONTENT
FROM MTL.EPR_INPATIENT_DOCINDEX dix
JOIN MTL.EPR_INPATIENT doc
  ON doc.UHID = dix.UHID
 AND NVL(doc.DELETEMARK, 0) = 0
JOIN MTL.EMRSD_CONTENT content
  ON content.UHID = doc.UHID
 AND content.段落编号 = doc.ID
WHERE dix.P_INPATIENT_NO = ?
  AND dix.P_INPATIENT_TIMES = ?
  AND dix.UHID = ?
  AND TO_CHAR(doc.ID) = ?
  AND ROWNUM <= 1
```

**JHEMR 文书列表**（参数：患者ID、住院次数）
```sql
SELECT * FROM (
  SELECT 'JHEMR' AS SYS,
         TO_CHAR(idx.FILE_UNIQUE_ID) AS DOC_ID,
         idx.TOPIC AS TITLE,
         idx.CREATE_DATE_TIME AS DOC_TIME
  FROM JHEMR.JHMR_FILE_INDEX idx
  WHERE idx.PATIENT_ID = ?
    AND idx.VISIT_ID = ?
    AND NVL(idx.DELETE_FLAG, 0) = 0
  ORDER BY idx.CREATE_DATE_TIME
) WHERE ROWNUM <= 300
```

**JHEMR 文书正文**（参数：患者ID、住院次数、文书唯一标识）
```sql
SELECT idx.TOPIC AS TITLE,
       idx.CREATE_DATE_TIME AS DOC_TIME,
       txt.MR_CONTENT AS CONTENT
FROM JHEMR.JHMR_FILE_INDEX idx
LEFT JOIN JHEMR.JHMR_FILE_CONTENT_TEXT txt
  ON txt.FILE_UNIQUE_ID = idx.FILE_UNIQUE_ID
WHERE idx.PATIENT_ID = ?
  AND idx.VISIT_ID = ?
  AND TO_CHAR(idx.FILE_UNIQUE_ID) = ?
  AND ROWNUM <= 1
```

- `GET /api/emr/documents`：两个系统都查（缺少对应参数的系统跳过），合并后按 `docTime` 升序返回；某个系统查询报错 → 该系统返回空并在返回中附 `warnings:["MTL 查询失败: ORA-xxxxx"]`，不影响另一个系统。
- `GET /api/emr/document-content`：CLOB 用 `getClob().getSubString(1, 20000)` 读取；返回纯文本，**不做任何 HTML 处理**（前端负责转义）。
- 两个接口登录即可调用（专家要看病历），但每次调用写 `sys_oper_log`（module=`EMR`，action=`VIEW_DOC`，detail 只记 `sys`、`docId` 和调用人，不记正文）。

### 步骤 8：总表回填导出 `GET /api/export/master`（`ADMIN`、`FUNC_LEADER`、`EXPERT`、`LEADER`）
1. 生成与 `数据指标导出调整版.xlsx` **同结构**的工作簿：Sheet 名 `sheet1`；第 1 行 `编号 | 名称 | 类型 | 2022 | 2022 | 2023 | 2023 | 2024 | 2024 | 2025 | 2025`；第 2 行 `编号 | 名称 | 类型 | 数据 | 数据来源 | 数据 | 数据来源 | …`；从第 3 行起按 `eval_master_row.row_no` 升序逐行写出，共 4,718 行；类型列写中文（`章节/指标/基础指标`）。
2. 每个年份（只输出请求 `years` 中的年份列，未请求的年份两列留空）的取值，用全院行（`scope_id=0`、当期或指定批次）：
   - CHAPTER 行：两列都空；
   - METRIC 行：`数据` = `qualitative_result` 非空则用它，否则 `result_val`（数字单元格）；
   - ATOM `NUM` → `numerator_val`；`DEN` → `denominator_val`；`SINGLE` → `numerator_val`，若为空则用 `qualitative_result`；`EXTRA` → `atom_values_json` 中该原子编号的值；
   - `数据来源`：指标 `is_applicable=false` → `不适用`；否则 `data_source_tag` 映射：`SYS→医院系统提取`、`HYBRID→医院系统提取+手工统计`、`MANUAL→手工统计`、`PROV→省级系统提取`；
3. **兜底**：某行某年系统内没有结果 → 用 `eval_master_row.orig_values_json` 中该年原值填写，并计数；
4. 追加第二个 Sheet `导出说明`：导出时间、导出人、批次（当期或批次号）、年份、“系统结果填写单元格数”、“沿用原表值单元格数”、“空单元格数”；
5. 文件名 `三级医院评审指标回填_<yyyyMMddHHmm>.xlsx`；写 `sys_oper_log`（action=`EXPORT_MASTER`）。

### 步骤 9：单元测试
- `MasterExportServiceTest`：用内存数据构造 1 个章节 + 1 个指标 + 2 个原子（NUM/DEN）+ 1 个 3 原子复合指标，断言导出后各单元格取值与来源列正确、兜底计数正确；
- `EmrDocIdTest`：`"ABC-12-345"` 拆成 `UHID="ABC-12"`、`ID="345"`。

### 步骤 10：冒烟脚本 `scripts/smoke/TASK_10.ps1`
1. `/api/result/summary?page=1&size=10&years=2022,2023,2024,2025` → `total == 1485`；
2. 用 `keyword=NEU-EPI-01` 查询，`years["2025"]` 存在（来自存量基线）；
3. `/api/result/metric/{id}/scopes` 返回 ≥ 1 条；`/panel` 的 `trend` 长度 ≥ 1、`sqls` 长度 ≥ 1；
4. `/api/result/batches` 含 `BATCH_BASELINE_IMPORT`；
5. 若 TASK-07 冒烟已产生跑批明细：取其中一个有明细的 `(metricId, scopeId, 2025)` 调 `/api/result/detail?role=DEN` → `total > 0`，并打印 `columns` 的 `label` 列表（**不打印 list 内容**）；否则 `SKIP`；
6. 若上一步有明细且 `ODS_RO` 已配置：取第一行的 `patientId/visitId/inpNo` 调 `/api/emr/documents` → 打印返回文书数量与 `warnings`；再取第一个文书调 `/api/emr/document-content` → 打印正文**长度**（不打印正文）；否则 `SKIP`；
7. `/api/export/master?years=2022,2023,2024,2025` 下载到 `$env:TEMP\master_export.xlsx`，用 Python 校验：Sheet `sheet1` 行数 == 4720（含 2 行表头），第 807 行（Excel 行号）编号 == `25.2.3.3.5.1.1`；
8. 打印 `SMOKE TASK_10 DONE`（有 SKIP 时打印数量）。

## 7. 验收命令
```powershell
docker run --rm -v "${PWD}:/workspace" -v "$env:USERPROFILE/.m2:/root/.m2" -w /workspace/hospital-eval-server maven:3.9-eclipse-temurin-17 mvn -B clean package
docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
Start-Sleep -Seconds 25
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_10.ps1
git status --short
```

## 8. 回执必须包含
- Maven 测试汇总行；冒烟输出（无患者信息）；总表导出校验结果与“导出说明”Sheet 中的三个计数；
- **声明：病历接口只执行了带患者条件的 SELECT，Oracle 写入数量为 0**；
- 提交号。

提交信息：`TASK-10: 结果汇总、明细、病历调阅与总表导出接口`
