import subprocess

cleanup = (
    "DELETE FROM eval_audit_log WHERE result_id IN "
    "(SELECT id FROM eval_metric_result WHERE stat_period = '2099');\n"
    "DELETE FROM eval_metric_result WHERE stat_period = '2099';\n"
    "DELETE FROM eval_manual_evidence WHERE stat_period = '2099';"
)
cmd = [
    "docker", "exec", "eval-postgres", "sh", "-c",
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "' + cleanup + '"',
]
r = subprocess.run(cmd, capture_output=True, text=True)
print("stdout:", r.stdout[-500:] if r.stdout else "(empty)")
print("stderr:", r.stderr[-300:] if r.stderr else "(empty)")
print("exit:", r.returncode)
