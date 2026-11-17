# TASK-18 回执：真实数据分批跑批与历史基线交叉对账

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-start-plan）
- 开始时间：2026-11-17（Asia/Shanghai）
- 状态：**前置检查未通过，按 TASK_18.md 第 3 节"任一不满足即停止"与执行规则 1 停止**；未创建任何任务文件（两个 migration 脚本与报告均未开工）
- Git 提交号：见文末（本回执单独提交）
- 基线：HEAD = c7ca10d（TASK-17 回执提交号回填）

## 一、前置检查真实输出（不满足项加粗说明）

```text
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_06.ps1
PASS: 1 数据源列表返回 2 条
PASS: 1b 返回不含 password/username 字段
PASS: 2a DELETE 被拦截 valid=false
PASS: 2b SELECT 放行 valid=true
SKIP: ODS_RO 本机网络或Oracle监听白名单未放行 (<SQLRecoverableException (vendorCode=17002)>)
SKIP: HIS_RO 本机网络或Oracle监听白名单未放行 (<SQLRecoverableException (vendorCode=17002)>)
SKIP: 5 ODS_RO ping 未连通
SKIPPED: 3

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_07.ps1
PASS: 1 空 metricIds/scopeIds 被拒绝 code=400
SKIP: Oracle 未连通 (SQLRecoverableException (vendorCode=17002))，真实跑批部分跳过
SMOKE TASK_07 DONE
```

**判定**：TASK_18.md 前置检查要求"ODS_RO 与 HIS_RO 的 ping 都必须 ok=true（**不能是 SKIP**）"，实际两个源均为 `SKIP`（`SQLRecoverableException vendorCode=17002`，即本机 IP 未获 Oracle 监听白名单放行）——**不满足，按规则停止**。

## 二、缺什么（唯一阻塞项）

**本机到院内 Oracle 只读源（HIS_RO `10.10.10.15:1521/his`、ODS_RO `10.10.8.216:1521/orcl`）的网络通道未打通。** 后端、跑批引擎、沙箱均就绪（TASK_06 其余断言全 PASS），仅网络层被 Oracle 监听 IP 白名单拦截。

按 `AGENTS.md` §三.2 记载的两条已拍板补救路径（需**用户显式操作**，AI 不可自行开启）：

1. **SSH 端口转发**：用户显式开启 `data-asset-83`（091 前置机，10.10.8.74）SSH 隧道，把 `10.10.10.15:1521` 与 `10.10.8.216:1521` 转发到本机端口，并同步调整 `deploy/.env` 中两个只读数据源的 host/port 指向隧道地址（`.env` 由用户维护，AI 不改）；
2. **白名单放行**：由 DBA 将本机内网 IP 加入 Oracle 监听白名单（生产 `10.10.8.75` 已在白名单内，本机开发机不在）。

通道打通后任一验证方式：重跑 `scripts/smoke/TASK_06.ps1`，两个 `SKIP` 变为 ping `ok=true` 即满足 TASK-18 前置条件。

## 三、停止时的产出清单

- 无代码产出（`migration/05_run_real_batches.py`、`migration/06_compare_baseline.py`、对账报告均未创建）——避免在无法验证真实 Oracle 行为的情况下预写脚本。
- 本回执（提交入库，供主控核查阻塞原因）。

## 四、通道恢复后的执行计划（供重投 TASK-18 时沿用）

1. 重跑两项前置检查至全绿；
2. `migration/05_run_real_batches.py`：按小专业在前（病案/康复/营养/麻醉/感染/疼痛/脑损伤/重症/呼吸）分批跑，每批 ≤30 实例、years=[2022~2025]、versionTag="TASK-18 真实数据首跑-<专业>"，30 秒轮询、单批 2 小时超时不中断整体，`migration/.tmp/run_log.json` 记录，支持 `--only/--resume`；08:00–18:00 不跑神经/肿瘤/心血管（大专业留到 18:00 后）；
3. `migration/06_compare_baseline.py`：新值 vs 基线 A（BATCH_BASELINE_IMPORT 同实例同年）/基线 B（仅单科室指标比全院行），判定 一致/轻微(≤2% 或 ≤2)/显著/无基线/未产出；
4. 产出 `docs/reports/真实数据跑批对账报告.md`（执行概况/失败原因分布/对账汇总/显著差异明细含原因提示/结论建议，不含患者身份信息）。

## 五、Git 提交

提交信息：`TASK-18: 前置检查未通过停止回执（Oracle 白名单未放行）`

```text
5a88e4f TASK-18: 前置检查未通过停止回执（Oracle 白名单未放行）
```
