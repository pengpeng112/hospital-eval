# TASK-08 回执：指标维护与部门管理接口

- 执行 AI / 模型：ZCode / GLM-5.3-Flash（account:bigmodel-individual-coding-plan）
- 开始 / 结束时间：2026-11-15 21:20 / 2026-11-15 22:15（Asia/Shanghai）
- 状态：已完成（`sync-dept-codes` 因本机 HIS 监听白名单未放行按规则 SKIP）
- Git 提交号：`f5ea57c TASK-08: 指标维护与部门管理接口`（任务交付内容提交，含本回执；本行由紧随其后的一次仅回执小提交入库）
- 基线：HEAD = 4ee1f0a（TASK-07），metric 实体/Mapper 已在 TASK-07 按任务书预告建于同包，本任务直接复用并新增

## 一、前置检查结果

```powershell
PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS
```

## 二、新建 / 修改的文件（8 项，均在 metric/** 白名单与冒烟白名单内）

| 文件 | 说明 |
| :--- | :--- |
| `metric/entity/MetricMasterRow.java` | eval_master_row 逐列（只读使用） |
| `metric/mapper/MetricMasterRowMapper.java` | BaseMapper |
| `metric/dto/MetricDtos.java` | MetricUpdateReq（changeReason @NotBlank）/ CreateScopeReq / UpdateScopeReq |
| `metric/service/MetricService.java` | 章节树（10 分钟内存缓存 + 指标修改清缓存 + 向上累加）、列表（chapterCode 含下级与 `(%)` 后缀、keyword 三字段模糊、specialty/extractMode/applicable 过滤、scopeCount 聚合、master_row_no 升序）、详情（dict 全字段 + ATOM 原子行 + 实例）、修改（枚举/必填校验、仅变化字段 before/after 留痕、稀疏更新避开 JSONB、清缓存） |
| `metric/service/ScopeService.java` | 列表（dict/user×3 五表联查、过滤、数组往返）、新增（重复 400）、修改（skipReason 必填、extractModeOverride 枚举校验、变化留痕）、func-dept-summary（NULL→未分配）、导出/导入（POI，12 固定列）、sync-dept-codes（HIS_RO 精确匹配、ORA-00942 带号返回不猜表名） |
| `metric/controller/MetricController.java` | §12.4 四接口；update @SaCheckRole(ADMIN/FUNC_LEADER, SaMode.OR) |
| `metric/controller/ScopeController.java` | §12.4 全部接口；create ADMIN；update/import ADMIN/FUNC_LEADER（OR）；sync ADMIN；export 登录即可 |
| `scripts/smoke/TASK_08.ps1` | 任务书步骤 8 全部 9 项（UTF-8 BOM） |

未触碰 `eval_master_row`（只读）；未提供实例删除接口；本机 Oracle 未穿透（见 §四）。

## 三、验收命令与真实输出

```text
PS> docker run ... maven:3.9-eclipse-temurin-17 mvn -B "-Dmaven.repo.local=/root/.m2/repository" clean package
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
（TASK-01~07 全部既有测试回归通过，本任务无新增单测——任务书未要求）

PS> docker compose -f deploy/docker-compose.dev.yml --env-file deploy/.env up -d --build eval-server
（Started EvalApplication in 4.336 seconds）

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1（末行）
SMOKE TASK_05 ALL PASS

PS> powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_08.ps1
PASS: 1 章节树根节点 metricCount 总和 1485 == 1485
PASS: 2a keyword=NEU-EPI-01 命中 1 条
PASS: 2b numAtomCode = 0.25.252.1
PASS: 3 指标详情 atoms 长度 = 2
PASS: 4a 缺 changeReason 返回 400
PASS: 4b 修改成功（带 changeReason）
PASS: 4c oper-logs 出现 METRIC/UPDATE_METRIC 记录
PASS: 4d 改回原值成功
PASS: 5 scope/list 返回 5 ≥ 1
PASS: 6 func-dept-summary 返回 1 ≥ 1
PASS: 7a 导出文件 107652 字节 > 5KB
7b 导入 updated=1468 errors=408（契约错误行[查不到或重名]=408 / 其他=0）
  示例 row=540 联络员查不到或重名：谭蓓
  示例 row=545 联络员查不到或重名：谭蓓
  示例 row=550 联络员查不到或重名：谭蓓
PASS: 7b 导入错误全部为契约要求的重名/查不到错误行
SKIP: HIS_RO 未连通 (SQLRecoverableException (vendorCode=17002))
SMOKE TASK_08 ALL PASS

PS> git status --short
?? hospital-eval-server/src/main/java/com/hospital/eval/metric/（8 项新文件）
?? scripts/smoke/TASK_08.ps1
（target/ 等已被忽略）
```

## 四、关键结论（回执必含项）

- **章节树**：根节点（25.2 指标）metricCount = **1485** ✓。
- **指标列表/详情**：NEU-EPI-01 精确命中 1 条，numAtomCode=0.25.252.1，atoms=2 ✓。
- **修改留痕**：缺 changeReason→400；带→成功且 sys_oper_log 出现 METRIC/UPDATE_METRIC（仅变化字段 before/after）；随后已改回原值（再带原因）。
- **科室实例**：scope/list 该指标 5 条；func-dept-summary 1 条。
- **导出/导入**：导出 107,652 字节（1917 实例 × 12 列）；原样导入 updated=1468、errors=408——**全部为契约第 6 步明确要求的"查不到或重名 → 记错误行"**（同名联络员如"谭蓓"在多科室，TASK-04 已按 姓名_科室 建多用户，但导出列无科室无法唯一定位，须人工裁决），其他类错误 0。
- **sync-dept-codes**：SKIP（HIS_RO ping 返回 SQLRecoverableException vendorCode=17002，本机监听白名单未放行）——**unmatchedNames 未产生**，待网络放行后复跑冒烟即打印 updated 与 unmatchedNames 清单。
- **声明：本任务 Oracle 只执行了只读 SELECT（COMM.DEPT_DICT 查询，因白名单未实际执行），写入数量为 0。**

## 五、安全自检（逐项勾选）

- [x] Oracle 只读 SELECT；同步接口遇 ORA-00942 返回带 ORA 号的 400，不改表名猜
- [x] 未读取/打印/提交任何密码或 deploy/.env 内容（冒烟 Get-EnvValue 只取值不打印）
- [x] 回执与仓库无患者信息（科室名清单属组织信息，按任务书要求写入回执）
- [x] 本机 JAVA_HOME / PATH 未修改
- [x] 只改 metric/** 白名单文件 + 冒烟脚本 + 回执
- [x] 未操作 10.10.8.75；未触碰其他项目容器

## 六、待确认事项

1. **导入 408 行重名错误行**（契约预期行为，非缺陷）：同名联络员（如"谭蓓"多科室）在导出列（无科室列）下无法唯一定位用户。可选改进：a) 导出/导入增加"责任科室"列，按科室+姓名唯一定位用户；b) 人工在 xlsx 里把联络员改成 姓名_科室 形式的 username 匹配。请主控定方案后再由后续任务落地。
2. **sync-dept-codes 未真实执行**：待 HIS 网络放行后复跑冒烟打印 unmatchedNames 清单。
3. **export 登录即可**（无角色限制）：04 §12.4 未标注，按只读接口处理；import 限 ADMIN/FUNC_LEADER。
4. **指标更新采用稀疏更新实体**：绕开 `extra_atoms_json` JSONB 列被按 varchar 回写的 PG 类型错误（MyBatis-Plus updateById 全字段更新触发）；同步把 update_time 刷新。JSONB 列的类型处理器留待 TASK-10 明细写入时统一定义。

## 七、未完成

无（sync-dept-codes 为预设 SKIP 分支，见第 2 条待确认）。
