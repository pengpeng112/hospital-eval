# TASK-20：十类高频问题巡检、性能压测、自动备份与操作手册

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 10 · 第 2 次 |
| 依赖 | TASK-19 |
| 预计改动 | 4~6 个脚本/文档 |
| 是否连数据库 / 服务器 | 10.10.8.75（`/opt/hospital-eval/` 内）、生产 PostgreSQL（只读巡检） |

## 1. 目标
对照省卫健委“数据核验十类高频问题”对生产库做自动巡检；对生产系统做接口性能测试；在 10.10.8.75 配置每日自动备份并实际演练一次；编写院内操作手册与迎检专家核验指南。

## 2. 开工前必读
1. `00_项目需求背景与AI接手总览.md` 第一节（十类高频问题）
2. `AGENTS.md` 第三节
3. 本文件

## 3. 前置检查
```powershell
ssh -o BatchMode=yes hospital-eval-75 "docker ps --filter name=eval- --format '{{.Names}} {{.Status}}'"   # 4 个 eval- 容器均 Up
Invoke-RestMethod http://10.10.8.75:18080/api/health
```

## 4. 允许新建 / 修改的文件
- `migration/07_audit_ten_issues.py`
- `deploy/scripts/backup_postgres.sh`
- `deploy/scripts/perf_test.py`
- `docs/reports/十类高频问题自检报告.md`
- `docs/reports/性能测试报告.md`
- `docs/院内操作手册与迎检专家核验指南.md`
- `docs/task_receipts/TASK_20_回执.md`
- 服务器：`/opt/hospital-eval/backup/`、`/opt/hospital-eval/scripts/`，以及 **root 用户 crontab 中新增一行**（只允许新增，不得修改或删除已有行）

## 5. 禁止事项
- 巡检脚本只执行 SELECT；禁止修改生产数据。
- 压测并发不得超过 10，总时长不超过 10 分钟，避免影响同机“AI 病历质控”服务。
- crontab 只能用 `(crontab -l 2>/dev/null; echo "<新行>") | crontab -` 追加，追加前后都要打印 `crontab -l` 对比。

## 6. 操作步骤

### 步骤 1：十类高频问题巡检 `migration/07_audit_ten_issues.py`
通过 SSH 隧道连接生产库（`ssh -N -L 25432:127.0.0.1:<EVAL_PG_PORT> hospital-eval-75`，脚本启动前由执行 AI 在另一个终端开启），只读查询，逐项输出：

| # | 高频问题 | 巡检规则（SQL 或脚本逻辑） | 建议责任部门 |
| :--- | :--- | :--- | :--- |
| 1 | 用中文诊断名代替编码 | 启用 SQL 的 `sql_executable` 中出现 `诊断名称|DIAGNOSIS_DESC|DIAG_NAME` 等列并伴随 `LIKE '%` | 病案室 + 信息科 |
| 2 | 无原始数据无法溯源 | 当期全院行分子 > 0，但对应批次明细（NUM 或混合池已选）人次 = 0 且该指标有 DTL SQL | 信息科 |
| 3 | 可系统提取仍手工填报 | 指标 `extract_mode='MANUAL'` 但存在启用的 SQL | 归口职能部门 |
| 4 | 分母为 0 / 分子为 0 | 当期全院行 `denominator_val=0` 或 `numerator_val=0` | 归口职能部门 |
| 5 | 不同系统同指标多版本数值 | 同一指标同一年：当期值与 `BATCH_BASELINE_IMPORT` 值差异 > 5% | 质控办 |
| 6 | 填报错误 | 结果值为百分比的指标 `result_val > 100` 或分子 > 分母 | 归口职能部门 |
| 7 | 统计错误（跑批失败） | 最近一次批次中 `FAILED` 的子任务 | 信息科 |
| 8 | 口径理解偏差 | `hosp_caliber_desc` 为空的适用指标；`period_flag`/`ds_flag` 非空的启用 SQL | 归口职能部门 + 信息科 |
| 9 | 片面凑提取率 | 适用指标中 `HYBRID` 但无任何 SQL 的科室实例 | 信息科 |
| 10 | 复核未闭环 | 2025 年适用指标中未 `L3_ARCHIVED` 的数量，按归口部门统计 | 质控办 |

输出 `docs/reports/十类高频问题自检报告.md`：每类问题数量 + 前 50 条明细（指标编号、指标名、科室、问题说明、建议责任部门），**不含患者信息**。

### 步骤 2：性能测试 `deploy/scripts/perf_test.py`
- 用 `requests` + `concurrent.futures`，并发 5、每个接口 30 次；测试接口：`/api/result/summary`（size=20）、`/api/result/detail`（选一个明细最多的指标，size=50）、`/api/result/metric/{id}/panel`、`/api/workbench/overview`、`/api/export/master`（只测 3 次，串行）；
- 统计 P50 / P95 / 最大耗时与错误数，写入 `docs/reports/性能测试报告.md`；
- 验收标准：汇总页 P95 < 1s；明细 P95 < 1.5s；总表导出 < 30s；不达标项在报告中列出“原因分析与优化建议”（可加索引的写出建议 SQL，**不要**直接在生产执行 DDL，交给主控 AI 审核）。

### 步骤 3：自动备份 `deploy/scripts/backup_postgres.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail
BACKUP_DIR=/opt/hospital-eval/backup
TS=$(date +%Y%m%d_%H%M%S)
docker exec eval-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$BACKUP_DIR/hospital_eval_$TS.dump"
tar -czf "$BACKUP_DIR/uploads_$TS.tar.gz" -C /opt/hospital-eval/data uploads
find "$BACKUP_DIR" -name 'hospital_eval_*.dump' -mtime +30 -delete
find "$BACKUP_DIR" -name 'uploads_*.tar.gz' -mtime +30 -delete
echo "backup ok $TS"
```
- 上传到 `/opt/hospital-eval/scripts/backup_postgres.sh`，`chmod 700`；
- 手工执行一次，确认生成的 `.dump` 文件 > 1MB；
- 演练恢复：`docker exec -i eval-postgres sh -c 'pg_restore -l' < 备份文件 | head -20`（只列目录，不真正恢复）；
- crontab 追加：`30 2 * * * /opt/hospital-eval/scripts/backup_postgres.sh >> /opt/hospital-eval/backup/backup.log 2>&1`。
- 回执中提醒用户：备份目前在同一台机器上，需由医院安排**异机**拷贝（例如每周拷贝到备份服务器）。

### 步骤 4：操作手册 `docs/院内操作手册与迎检专家核验指南.md`
章节：
1. 系统访问地址与账号说明（不含任何密码；说明初始密码由信息科单独发放，首次登录强制修改）；
2. 角色说明（6 个角色能做什么）；
3. 科室联络员：如何填报、如何勾选混合指标、如何上传佐证、如何提交；
4. 内审员：如何抽查病历、通过或退回；
5. 职能终审人：如何查看全科室进度、终审归档、解锁；
6. 信息科：如何修改 SQL（必须写原因）、如何覆盖评审数据、如何看跑批失败、如何导出省评回填总表、如何做省平台对账；
7. 迎检专家核验路径：结果汇总 → 核验抽屉（口径 + SQL + 趋势）→ 分子明细 → 病历原文 → 历史版本对比 → 复核留痕；
8. 每月封账节奏（5 日前科室提交、10 日前内审、15 日前终审）；
9. 常见问题（忘记密码、预览不显示、跑批失败怎么办）。

## 7. 验收命令
```powershell
python migration/07_audit_ten_issues.py
python deploy/scripts/perf_test.py
ssh -o BatchMode=yes hospital-eval-75 "ls -lh /opt/hospital-eval/backup | tail -5; crontab -l"
git status --short
```

## 8. 回执必须包含
- 十类问题每类数量；性能 P50/P95 表；备份文件名与大小；crontab 追加前后对比；
- `10.10.8.75` 全部容器最终状态（原有服务 + `eval-` 容器）；
- 提交号。

提交信息：`TASK-20: 十类问题巡检、性能测试、备份与操作手册`
