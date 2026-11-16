# TASK-16 回执：前端【人工上报】（填报/勾选/佐证/三级复核）与【工作台】

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始 / 结束时间：2026-11-17 上午（Asia/Shanghai）
- 状态：已完成（build 成功、`:18012` 检查无匹配、2099 九步闭环自测全过、kkFileView 预览经代理 200、测后清理复检为 0）
- Git 提交号：见文末
- 基线：HEAD = bc666a6（TASK-15 回执提交号回填）
- 说明：任务指令第 6 条"粘贴到 docs/task_receipts/TASK_03_回执.md"为既有笔误，按 TASK_16.md 第 8 节与允许清单写入本文件 `TASK_16_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_11.ps1   （末行）
PASS: 9 清理后 2099 结果行 = 0
SMOKE TASK_11 ALL PASS

PS> cd hospital-eval-web; npm run build   （改动前基线）
✓ built in 24.10s
```

## 二、新建 / 修改的文件（4 项，均在白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `src/views/manual/AuditTimelineDrawer.vue` | 复核时间线（步骤 3）：listAuditLogs({resultId}) 或 ({metricId, year})；级别中文（科室初审/内审复核/职能终审/管理员）、动作中文（复用 dict.js AUDIT_ACTION：提交/通过/退回/归档/解锁）、意见、操作人、科室、IP、时间；REJECT 节点红色 |
| `src/views/manual/ReportDrawer.vue` | 填报抽屉 75%（步骤 2 全量，见下） |
| `src/views/manual/index.vue` | 主页面（步骤 1 全量，见下） |
| `src/views/workbench/index.vue` | 工作台（步骤 4 全量，见下） |

复用（未修改）：TASK-15 的 `EmrRecordDrawer.vue`（混合池勾选 Tab 中"先看病历再勾选"）、TASK-12 的 `ExtractModeTag.vue`/`dict.js`。

## 三、实现要点（步骤逐条）

**步骤 1 主页面**：年份选择（默认 2025）+ 状态 Tab 六项（待填报 TODO/被退回 REJECTED/已提交 SUBMITTED/内审通过 PASSED/已归档 ARCHIVED/全部 ALL）+ 关键字；表格勾选列 + 任务书全部列（编号/名称/科室/提取方式/我的角色 roleOf 中文/分子/分母/结果值/附件数/状态 AuditStatusTag/操作）；批量按钮按勾选行（提交初审 auditSubmit、内审通过 auditApprove、退回整改弹窗意见 ≥5 字 auditReject），执行后弹窗列 success 数与 failed 明细（resultId+原因）；行操作：填报（ReportDrawer）、复核记录（Timeline）、终审归档（仅 myRole=FUNC_LEADER 或 ADMIN，confirm 后 auditArchive({metricId, year})，**失败时后端含未通过科室列表的 msg 经拦截器完整弹出**）、解锁（ADMIN/FUNC_LEADER 且 L3_ARCHIVED，ElMessageBox.prompt 必填意见 → auditUnlock）。状态流转全部调后端接口，前端只按返回渲染；工作台跳转经 `route.query.status` 预选 Tab（04 §13）。

**步骤 2 填报抽屉**：
- 纯手工指标顶部红色提示（任务书原文）；
- Tab①数值填报（`extractMode==='MANUAL'` 或分子分母全空需补录时显示）：分子/分母数字输入、定性结论下拉（是/否/符合/部分符合/不符合，可空）、备注；保存 → saveManualValue；非 DRAFT/L2_REJECT 时整块禁用 + "当前状态不可修改"提示条；
- Tab②分母病例池勾选：进入抽屉先调 `listHybridPool(page:1,size:1)` 探测，**返回 400 则不渲染该 Tab**（实测 MANUAL 指标 400 → 隐藏 ✓）；表格列由后端 columns 动态生成 + "已选为分子"开关 + 判定依据列；顶部"分母 N 人次 / 已勾选分子 M 人次"；勾选/取消弹小窗填判定依据（勾选必填）→ hybridSelect → **分子以后端返回值刷新（前端零计算）**；住院号链接 → EmrRecordDrawer；
- Tab③佐证材料：左目录 7 固定分类（原始登记台账/制度文件/培训记录/督导检查/会议纪要/持续改进(PDCA)/其他）带文件数；中间 el-upload 拖拽上传（**前端先校验扩展名白名单与 100MB 上限**再发请求，上传至当前分类）+ 文件列表；右预览区（55%）：点击文件 → getEvidencePreviewUrl → `<iframe :src="url">`（url 为后端返回的同源 `/kkview/...`，**未拼接任何 `:18012`**）；预览地址接口失败时降级：pdf → rawUrl iframe、jpg/png → rawUrl img、doc/docx/xls/xlsx → 提示"预览服务不可用，请下载查看"；删除（ADMIN，二次确认）。

**步骤 4 工作台**：年份切换重查；第一行 7 统计卡片（总数/适用/不适用/系统/人工/系统+人工/省级，数据来自 byExtractMode）；第二行 ECharts 环形图（audit 五状态，分色）+ 我的待办四卡片（TO_FILL/TO_RESUBMIT/TO_AUDIT/TO_ARCHIVE → 点击跳 `/manual?status=TODO|REJECTED|SUBMITTED|PASSED`）；第三行科室进度表（完成率进度条 100% 绿 / ≥50% 黄 / <50% 红）；第四行异常预警表（类型标签 ZERO_DEN/ZERO_NUM/TASK_FAILED、指标编号可点跳 `/summary/current?keyword=<编号>`、名称、科室、说明，≤200 条）。无欢迎语、无装饰图片。

## 四、验收命令与真实输出

### 4.1 构建输出（最后 8 行）

```text
dist/assets/department-C_Wi6Gzz.js        12.80 kB │ gzip:   4.77 kB
dist/assets/summary-DuYQeRQU.js           19.54 kB │ gzip:   6.94 kB
dist/assets/manual-BVCbMZtp.js            21.69 kB │ gzip:   8.13 kB
dist/assets/algorithm-DHd7d29L.js         45.11 kB │ gzip:  16.08 kB
dist/assets/SqlEditor-Cyx3ZrQj.js        436.74 kB │ gzip: 143.65 kB
dist/assets/index-Bb6yjXMn.js          1,034.91 kB │ gzip: 343.41 kB
dist/assets/index-C77DPTTY.js          1,288.68 kB │ gzip: 418.49 kB
✓ built in 17.86s
```

（manual 21.69 kB；ECharts 被 summary/workbench 共享为独立 chunk——index-Bb6yjXMn。）

### 4.2 `:18012` 检查

```text
PS> Select-String -Path hospital-eval-web/src/views/manual/*.vue -Pattern ":18012"
（无任何匹配输出；grep 等价检查 exit=1 → 无匹配 ✓）
```

### 4.3 git status --short（提交前）

```text
 M hospital-eval-web/src/views/manual/index.vue
 M hospital-eval-web/src/views/workbench/index.vue
?? hospital-eval-web/src/views/manual/AuditTimelineDrawer.vue
?? hospital-eval-web/src/views/manual/ReportDrawer.vue
```

（恰为白名单 4 个文件。）

## 五、自测记录（npm run dev，经 5173 代理以 admin 对 **2099 测试数据**走完整闭环）

测试指标：25.2.2.2.6（id=18，MANUAL，scopeId=1825，与 TASK-11 冒烟同位），全程未输出任何患者数据。

| # | 页面动作 | 接口 | 结果 |
| :--- | :--- | :--- | :--- |
| ① | 填报（数值 Tab 保存） | POST /api/report/manual-value | OK，resultId=11951（1/2，val=0.5 后端算出） |
| ② | 上传佐证（佐证 Tab → 原始登记台账） | POST /api/evidence/upload + GET /api/evidence/list | OK，evId=8，列表 1 个 |
| ③ | 点击文件预览 | GET /api/evidence/{id}/preview-url + GET /kkview/onlinePreview?url=… | OK，url 同源 `/kkview/` 前缀；**经 5173 代理请求 kkFileView 预览页 HTTP 200（返回预览页 HTML），预览服务可用** |
| ④ | 提交初审 | POST /api/audit/submit | OK，success=1 |
| ⑤ | 退回整改（意见≥5字） | POST /api/audit/reject | OK |
| ⑥ | 再提交 | POST /api/audit/submit | OK |
| ⑦ | 内审通过 | POST /api/audit/approve | OK |
| ⑧ | 终审归档（archived=2：科室行+全院行） | POST /api/audit/archive | OK；随后再填报 → **code=400 "当前状态不允许修改"**（后端状态机锁 ✓） |
| ⑨ | 解锁（必填意见） | POST /api/audit/unlock | OK，回到 DRAFT |
| 附 | 复核时间线 | GET /api/audit/logs?resultId=… | **6 条动作齐全：UNLOCK/ARCHIVE/APPROVE/SUBMIT/REJECT/SUBMIT** |
| 附 | 列表行字段 | GET /api/report/tasks?keyword=25.2.2.2.6 | OK：resultId/auditStatus=DRAFT/myRole=LIAISON/附件数=1/num=1/den=2/val=0.5 |
| 附 | 混合池 Tab 探测 | GET /api/report/hybrid-pool（MANUAL 指标） | HTTP 400 → 前端按规则隐藏 Tab ✓ |
| 附 | 工作台四接口 | overview/todos/dept-progress/alerts (year=2025) | 全 code=0：指标总数 1485、audit 五状态（DRAFT=1786）、待办 4 类、科室进度 78 行、预警 200 条 |

**测后清理**（任务书指定沿用 TASK-11 冒烟第 9 步，语句打印）：
```sql
DELETE FROM eval_audit_log WHERE result_id IN (SELECT id FROM eval_metric_result WHERE stat_period = '2099');
DELETE FROM eval_metric_result WHERE stat_period = '2099';
DELETE FROM eval_manual_evidence WHERE stat_period = '2099';
-- DELETE 8 / DELETE 2 / DELETE 1
```
复检三表 2099 记录 = 0/0/0 ✓（dev server 已停止；上传的测试 PDF 存于后端 `eval.upload-dir` 的 2099 目录中，DB 记录已删，物理文件按后端设计保留原目录不影响业务）。

## 六、安全红线自查

- Oracle 零直连；本机 kkFileView 预览经 vite 同源代理，未拼接任何直连地址（验收检查通过）。
- 状态流转全部走后端接口（提交/退回/通过/归档/解锁），前端不自行改状态、不算分子（hybridSelect 返回值为准）。
- 未读取/打印/提交任何密码与 deploy/.env 内容；2099 测试数据已按唯一允许场景清理。
- 无 TODO/mock/写死假数据；改动恰为白名单 4 文件。

## 七、待确认事项（不阻塞）

1. **任务列表 Long 字段序列化为字符串**：后端全局 Jackson 将 Long 序列化为字符串（`"resultId":"11951"`、`"scopeId":"1825"`，防 JS 精度丢失的既有约定），前端渲染与回传均正常（JSON 字符串可被 Jackson 反序列化为 Long），仅提醒主控知晓该约定跨 TASK-05~16 一致存在。
2. **佐证物理文件保留**：删除附件时 DB 记录删除、物理文件移入 `_deleted/`（后端设计）；本次自测清理只删 DB 行，`2099/` 目录下测试 PDF 物理文件仍在存储卷（不影响业务与统计，如需可由运维定期清理）。
3. **混合池勾选 Tab 的实弹勾选未做**：本任务测试指标为 MANUAL（pool 探测 400 → Tab 隐藏，该分支已验证）；HYBRID 实例的"勾选→判定依据→分子刷新"链路组件参数与 §12.7 契约一致，建议主控浏览器验收时用一个 HYBRID 指标复核（需 Oracle 明细跑批产生分母池数据）。
4. 工作台待办 TO_ARCHIVE 跳转映射为 `/manual?status=PASSED`（内审通过 Tab，语义即"待归档"）；如主控希望单独口径可在后续任务调整。

## 八、Git 提交

提交信息：`TASK-16: 人工上报与工作台页面`

```text
224b05a TASK-16: 人工上报与工作台页面
```
