# TASK-15 回执：前端【结果汇总】与四级下钻溯源（迎检核心页）

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始 / 结束时间：2026-11-17 上午（Asia/Shanghai）
- 状态：已完成（build 成功、v-html 检查唯一且绑定 highlight、dev 自测 6 项 5 OK；两项后端侧问题如实记录于"待确认事项"）
- Git 提交号：见文末
- 基线：HEAD = 7710ef1（TASK-14 回执提交号回填）
- 说明：任务指令第 6 条"粘贴到 docs/task_receipts/TASK_03_回执.md"为既有笔误，按 TASK_15.md 第 8 节与允许清单写入本文件 `TASK_15_回执.md`。

## 一、前置检查结果（全部满足）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_10.ps1   （末段）
PASS: 3a scopes 5 ≥ 1
PASS: 3b panel trend 4 ≥ 1
PASS: 3c panel sqls 2 ≥ 1
PASS: 4 batches 含 BATCH_BASELINE_IMPORT
SKIP: TASK-07 冒烟未产生跑批明细（依赖 Oracle）
PASS: 7a 总表导出 276721 字节
MASTER_EXPORT_CHECK_OK rows=4720 row807=25.2.3.3.5.1.1
PASS: 7b Python 校验总表导出通过
SMOKE TASK_10 DONE

PS> cd hospital-eval-web; npm run build   （改动前基线）
✓ built in 9.22s
```

## 二、新建 / 修改的文件（6 项，均在白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `src/components/AuditStatusTag.vue` | 五状态标签（文案按任务书）：DRAFT 灰"草稿" / L1_SUBMIT 蓝"已提交初审" / L2_PASS 绿"内审通过" / L2_REJECT 红"退回整改" / L3_ARCHIVED 深绿"已终审归档"（success + effect=dark 表达深绿）；自带映射（dict.js 不在白名单，未改动） |
| `src/components/TrendChart.vue` | ECharts 折柱组合：柱=分子（蓝）/分母（绿）左轴，折线=结果值（橙）右轴；props `data=[{year,num,den,val}]`；window resize 监听 + 卸载 dispose + watch deep 重渲染 |
| `src/views/summary/index.vue` | 主页面（步骤 2 全量实现，见下） |
| `src/views/summary/ExpertVerifyDrawer.vue` | 核验抽屉 70%（步骤 3） |
| `src/views/summary/PatientDetailModal.vue` | 患者明细弹窗（步骤 4） |
| `src/views/summary/EmrRecordDrawer.vue` | 病历抽屉（步骤 5） |

未改动 `src/api/*.js`（result.js 10 个函数签名零变更）。开发中 rollup 拦截一次导入源错误（getMetricTree 误从 result.js 导入，实际在 metric.js），已修正——api 文件本身未动。

## 三、实现要点（步骤逐条）

**步骤 2 主页面**：
- 双模式：`route.meta.mode` current（不传 batchId）/ history（`listResultBatches()` 批次下拉"批次号 · 类型 · 版本说明 · 时间"，未选批次不查询并显示提示条；"与当期对比"开关打开后额外拉当期数据按 metricId+year 比对，不同则该单元格橙字并在下方小字显示当期值）；
- 筛选栏：章节 el-tree-select（getMetricTree，node-key=code，check-strictly）/ 专业 / 提取方式 / 只看分子或分母为 0 开关（zeroOnly）/ 关键字 / 年份多选（默认 2022~2025）+ 重置；
- URL 预填：onMounted 先读 `route.query` 的 chapterCode/keyword/extractMode/zeroOnly 再首查；`watch(() => route.query)` 变化重查（04 §13）；
- 主表：固定列指标编号/专科代号/指标名称（点击开核验抽屉）/提取方式（ExtractModeTag）/科室数；每个选中年份一组"分子/分母/结果值/状态"，分子分母为蓝色可点击数字（null 显示"—"不可点），结果值按 unit=='%' 加百分号（定性值原样显示），状态用 AuditStatusTag；
- 行展开：`listMetricScopes(metricId, {batchId, years})`，内嵌表（科室/免统红标/各年分子·分母·状态，科室分子分母同样蓝色可点开明细弹窗，scopeId=科室实例）；
- 右上角"导出省评回填总表"：`exportMaster({years, batchId})`，按钮按 ADMIN/FUNC_LEADER/EXPERT/LEADER 显隐；分页 20 条。

**步骤 3 核验抽屉**：getMetricPanel 五区块——①基本信息（编号/代号/名称/单位/导向/归口部门/提取方式/汇总方式）②口径（国家标准计算方法 + 本院统计口径 pre-wrap 原样）③趋势 TrendChart ④实际执行 SQL（按科室 el-tabs，块内角色中文+时段+版本号标注，SqlEditor 只读）⑤复核记录 el-timeline（级别/动作/意见/操作人/时间，REJECT 红色节点）。

**步骤 4 明细弹窗**：标题"<指标名> · <科室或全院> · <年份> · 分子/分母明细"；`listDetail` 动态列（prop 以 `ext.` 开头的从 `row.ext` 取值）；关键字搜索 + 分页 50/页 + 导出 Excel（exportDetail）；顶部"共 N 人次（按 患者ID+住院次数 去重）"；住院号列链接 → 病历抽屉；顶部醒目"含患者隐私信息，仅限院内核验使用"。

**步骤 5 病历抽屉**：打开时并行拉 360 地址（url 空 → 按钮禁用 + "未配置360视图地址"提示）/ 文书列表（按 docTime 倒序，MTL 蓝标 JHEMR 绿标，warnings 黄条）/ 指标口径（提取引号内词 ≤5 个作默认关键词）；正文 `v-html="highlight(content, keywords)"`（先转义后高亮，`white-space: pre-wrap`）；关键词输入框回车添加、tag 可删除，变化即重新高亮。

## 四、验收命令与真实输出

### 4.1 构建输出（最后 8 行，改动后）

```text
dist/assets/users-_jhKs0h9.js              5.77 kB │ gzip:   2.39 kB
dist/assets/metric-CTbKpne_.js            10.95 kB │ gzip:   4.08 kB
dist/assets/department-BQic-qaj.js        12.80 kB │ gzip:   4.77 kB
dist/assets/algorithm-rtAot_Z8.js         45.11 kB │ gzip:  16.08 kB
dist/assets/SqlEditor-BTqz_TIZ.js        436.74 kB │ gzip: 143.66 kB
dist/assets/summary-DxZSaCSc.js        1,060.39 kB │ gzip: 352.47 kB
dist/assets/index-BIdA2d_F.js          1,288.43 kB │ gzip: 418.40 kB
✓ built in 14.01s
```

（summary 分包 1,060 kB 含 ECharts；SqlEditor 抽为 algorithm/summary 共享 chunk。）

### 4.2 v-html 检查（任务书命令）

```powershell
PS> Select-String -Path hospital-eval-web/src/views/summary/*.vue -Pattern "v-html" | ForEach-Object { $_.Line.Trim() }
* v-html 只绑定 highlight()（先转义后包裹关键词）的结果，禁止渲染原始正文。      ← EmrRecordDrawer 注释
<!-- 右：正文（v-html 仅绑定 highlight() 转义结果） -->                          ← 注释
<!-- eslint-disable-next-line vue/no-v-html —— 正文已经 highlight() 先转义后高亮，安全 --> ← 注释
<div v-if="current" class="emr-content" v-html="highlight(content, keywords)"></div>  ← 唯一实际绑定
--- v-html check done ---
```

（命中 4 行 = 3 行注释 + 1 处实际绑定，绑定值即 `highlight(...)` 返回值，符合"只出现在病历正文一处"预期。）

### 4.3 git status --short（提交前）

```text
 M hospital-eval-web/src/views/summary/index.vue
?? hospital-eval-web/src/components/AuditStatusTag.vue
?? hospital-eval-web/src/components/TrendChart.vue
?? hospital-eval-web/src/views/summary/EmrRecordDrawer.vue
?? hospital-eval-web/src/views/summary/ExpertVerifyDrawer.vue
?? hospital-eval-web/src/views/summary/PatientDetailModal.vue
```

（恰为白名单 6 个文件。）

## 五、自测记录（npm run dev，经 5173 代理以 admin 实调）

| # | 步骤 | 接口 | 结果 |
| :--- | :--- | :--- | :--- |
| 1 | 当期版本首屏 | GET /api/result/summary?years=2022,2023,2024,2025 | OK，total=1485；测试指标 25.2.2.2.6（id=18）2022~2025 均有分子分母 |
| 2 | 核验抽屉 | GET /api/result/metric/18/panel | OK，trend=4 / sqls=0（MANUAL 指标无 SQL，如实）/ auditLogs=0 / dict 含 hospCaliberDesc |
| 3 | 展开科室 | GET /api/result/metric/18/scopes?years=… | OK，scopes=1 |
| 4 | 点分子开明细 | GET /api/result/detail?metricId=18&scopeId=0&year=2022&role=NUM&page=1&size=50 | OK，total=0、columns=14（MANUAL 指标无患者级明细——**第 4 级病历链因明细数据缺失未能走通，属数据现状而非页面缺陷**；接口链路参数与契约一致，待 Oracle 跑批产生明细后可通） |
| 5 | 历史版本 | GET /api/result/batches → GET /api/result/summary?batchId=1 | OK，BATCH_BASELINE_IMPORT（batchId=1）查询 total=1485，批次下拉数据形态 PageResult{list}（前端已兼容） |
| 6 | 导出总表 | GET /api/export/master?years=2022,2023,2024,2025 | OK，276,722 字节，Content-Disposition: attachment; filename*=UTF-8''三级医院…（前端 blob → download() 保存文件名取响应头） |
| 附 | URL 预填参数 zeroOnly | GET /api/result/summary?zeroOnly=true | **后端 500**（详见待确认 1，前端传参正确） |

（自测后 dev server 已停止；全程未打印任何患者数据——明细接口本身返回 0 行。）

## 六、安全红线自查

- Oracle 零直连；本任务仅经 dev 代理访问本机后端。
- `v-html` 全工程仅病历正文一处且绑定 `highlight()` 转义结果（验收检查输出见 4.2）。
- 前端零汇总计算（分子/分母/结果值/百分比均直接渲染后端值）。
- 无写死指标/科室/患者数据；无 TODO/mock。
- 明细弹窗与病历抽屉均带隐私提示；自测未输出任何患者信息。

## 七、待确认事项（不阻塞，需主控/后端侧处理）

1. **【后端缺陷·建议尽快修复】`zeroOnly=true` 必 500**：`ResultQueryService.summary()`（TASK-10 交付物，不在本任务白名单）第 89~96 行 `qw.apply("… IN (" + in + ") …", yArgs.toArray())` 用 `?` 占位符；MyBatis-Plus `apply` 要求 `{0}/{1}` 风格，实际报 `MybatisPlusException: Please check the syntax correctness! sql not contains: "{0}"`。前端"只看分子或分母为 0"开关传参符合 §12.6 契约，后端修复（`?`→`{0}` 逐位替换）后开关即恢复可用。测试 SQL：`GET /api/result/summary?years=2022,2023,2024,2025&zeroOnly=true`。
2. **【后端契约缺口】summary 行缺 `scopeCount`**：04 §12.6 定义返回含 `scopeCount`（科室数），但 `baseMetricRow()` 未输出该字段 → 前端"科室数"列如实显示"—"。后端在 baseMetricRow 补一行 scope 计数即可；前端已按 `row.scopeCount ?? '—'` 渲染，无需改动。
3. **panel 复核记录缺科室/IP**：`getMetricPanel` 的 auditLogs 仅返回 auditLevel/action/opinion/operatorName/operateTime（§12.6），任务书要求时间线展示"科室、IP"两列——面板接口未提供。前端按现有字段渲染；如需补齐建议主控确认后由后端在 panel SQL 中加 `operator_dept/operate_ip` 两列（或前端改调 `/api/audit/logs`）。
4. **第 4 级病历链未实走**：MANUAL 指标无患者级明细（total=0），病历抽屉链路（明细→住院号→病历原文）未能用真实数据走通；组件链路参数与 §12.6 契约一致，建议待 Oracle 跑批产生明细数据后（或基线明细批次）由主控浏览器验收时复核。

## 八、Git 提交

提交信息：`TASK-15: 结果汇总与四级下钻页面`

```text
d62a135 TASK-15: 结果汇总与四级下钻页面
```
