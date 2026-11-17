# TASK-19 数据迁移：本机开发库 → 生产 75
# 教训固化：PS 5.1 的 `>` 重定向会按文本解码/重编码，损坏 -Fc 二进制归档；
#           恢复/计数经 ssh "bash -s" << 远端脚本执行，避免多层 shell 引号问题。
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
$dumpWin = "$env:TEMP\eval_dev.dump"
$dumpNix = "C:/Users/Administrator/AppData/Local/Temp/eval_dev.dump"

Write-Host "== 1. 容器内 pg_dump -f + docker cp（二进制安全）=="
docker exec eval-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/eval_dev.dump'
if ($LASTEXITCODE -ne 0) { Write-Host "pg_dump FAILED"; exit 1 }
docker cp eval-postgres:/tmp/eval_dev.dump $dumpNix
docker exec eval-postgres rm /tmp/eval_dev.dump
$size = (Get-Item $dumpWin).Length
Write-Host "dump bytes $size"
if ($size -lt 100000) { Write-Host "dump too small"; exit 1 }

Write-Host "== 2. 归档完整性校验（pg_restore -l）=="
docker exec -i eval-postgres pg_restore -l < $dumpNix | Select-Object -First 2
if ($LASTEXITCODE -ne 0) { Write-Host "归档校验 FAILED"; exit 1 }

Write-Host "== 3. scp 到 /opt/hospital-eval/backup/ =="
scp -o BatchMode=yes $dumpNix hospital-eval-75:/opt/hospital-eval/backup/eval_dev.dump
if ($LASTEXITCODE -ne 0) { Write-Host "scp FAILED"; exit 1 }

Write-Host "== 4. 远端等待 pg 就绪 + pg_restore --clean --if-exists + 行数对比 =="
$remote = @'
echo "-- wait pg ready --"
for i in $(seq 1 24); do docker exec eval-postgres pg_isready 2>/dev/null | grep -q accepting && break; sleep 5; done
echo "-- pg_restore --clean --if-exists --"
docker exec -i eval-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < /opt/hospital-eval/backup/eval_dev.dump
echo "RESTORE_DONE (inherited-constraint NOTICE 属分区明细表 --clean 首次正常输出，errors ignored 计数见上)"
echo "-- prod counts --"
docker exec -i eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A' <<'SQL'
SELECT 'eval_metric_dict', count(*) FROM eval_metric_dict
UNION ALL SELECT 'eval_metric_dept_scope', count(*) FROM eval_metric_dept_scope
UNION ALL SELECT 'eval_metric_sql', count(*) FROM eval_metric_sql
UNION ALL SELECT 'eval_metric_result', count(*) FROM eval_metric_result
UNION ALL SELECT 'sys_user', count(*) FROM sys_user
UNION ALL SELECT 'eval_metric_detail_2025', count(*) FROM eval_metric_detail_2025;
SQL
'@
$remote | ssh -o BatchMode=yes hospital-eval-75 "bash -s"

Write-Host "== 5. 本机行数 =="
$localSql = "SELECT 'eval_metric_dict', count(*) FROM eval_metric_dict UNION ALL SELECT 'eval_metric_dept_scope', count(*) FROM eval_metric_dept_scope UNION ALL SELECT 'eval_metric_sql', count(*) FROM eval_metric_sql UNION ALL SELECT 'eval_metric_result', count(*) FROM eval_metric_result UNION ALL SELECT 'sys_user', count(*) FROM sys_user UNION ALL SELECT 'eval_metric_detail_2025', count(*) FROM eval_metric_detail_2025;"
$localSql | docker exec -i eval-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A'

Write-Host "== 6. 删除本机临时 dump（含患者明细）=="
Remove-Item $dumpWin -Force
Write-Host "MIGRATE DONE"
