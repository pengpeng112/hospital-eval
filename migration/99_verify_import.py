# -*- coding: utf-8 -*-
"""TASK-04 步骤 6：入库核验。打印 `表名=行数`，最后一行 VERIFY OK 或 VERIFY FAIL: 原因。"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from migration.common.db import get_conn  # noqa: E402

DIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "migration", "dist")


def main():
    conn = get_conn()
    problems = []
    try:
        cur = conn.cursor()
        checks = [
            ("eval_master_row", "SELECT count(*) FROM eval_master_row", lambda n: n == 4718, "期望 4718"),
            ("eval_metric_dict", "SELECT count(*) FROM eval_metric_dict", lambda n: n == 1485, "期望 1485"),
            ("eval_metric_dept_scope", "SELECT count(*) FROM eval_metric_dept_scope", None, ""),
            ("eval_metric_sql", "SELECT count(*) FROM eval_metric_sql", None, ""),
            ("eval_metric_sql_his", "SELECT count(*) FROM eval_metric_sql_his", None, ""),
            ("eval_metric_result", "SELECT count(*) FROM eval_metric_result", None, ""),
            ("sys_dept", "SELECT count(*) FROM sys_dept", None, ""),
            ("sys_user", "SELECT count(*) FROM sys_user", None, ""),
            ("sys_role", "SELECT count(*) FROM sys_role", lambda n: n == 6, "期望 6"),
        ]
        counts = {}
        for name, sql, check, note in checks:
            cur.execute(sql)
            n = cur.fetchone()[0]
            counts[name] = n
            print(f"{name}={n}")
            if check is not None and not check(n):
                problems.append(f"{name}={n} {note}".strip())

        # 科室实例下限：1452 减未匹配数（dept_scopes.json 实测口径）
        try:
            with open(os.path.join(DIST, "dept_scopes.json"), encoding="utf-8") as f:
                sc = json.load(f)
            min_scope = 1452 - sc["meta"]["counts"].get("manual_needed", 0)
            if counts["eval_metric_dept_scope"] < min_scope:
                problems.append(f"eval_metric_dept_scope={counts['eval_metric_dept_scope']} 期望 ≥ {min_scope}")
        except OSError:
            problems.append("dept_scopes.json 缺失，无法核对科室实例下限")

        cur.execute("SELECT count(*) FROM eval_metric_sql WHERE sql_executable !~* '^\\s*\\(?\\s*(select|with)'")
        bad_sql = cur.fetchone()[0]
        print(f"eval_metric_sql 非SELECT/WITH开头={bad_sql}")
        if bad_sql != 0:
            problems.append(f"eval_metric_sql 非SELECT/WITH开头={bad_sql} 期望 0")

        cur.execute("SELECT count(*) FROM eval_metric_result WHERE is_current AND scope_id=0 AND stat_period='2025'")
        cur_2025 = cur.fetchone()[0]
        print(f"eval_metric_result 当期2025全院行={cur_2025}")
        if cur_2025 <= 1000:
            problems.append(f"eval_metric_result 当期2025全院行={cur_2025} 期望 > 1000")

        cur.execute("SELECT tablename FROM pg_tables WHERE tablename LIKE 'eval_metric_detail_%'")
        parts = [r[0] for r in cur.fetchall()]
        print(f"eval_metric_detail 分区数={len(parts)}")
        if len(parts) != 9:
            problems.append(f"eval_metric_detail 分区数={len(parts)} 期望 9")
    finally:
        conn.close()
    if problems:
        print("VERIFY FAIL: " + "；".join(problems))
        sys.exit(1)
    print("VERIFY OK")


if __name__ == "__main__":
    main()
