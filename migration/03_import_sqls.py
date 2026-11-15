# -*- coding: utf-8 -*-
"""TASK-04 步骤 5.3：sqls.json 幂等入库（只导 scope_key 能找到 scope_id 的 SQL）。

- 幂等：按 source_ref 查重，存在则 UPDATE，不存在则 INSERT；
- 每条新插入 SQL 同步写一条 eval_metric_sql_his（change_type='新增'）。
可重复执行（第二次行数不变）。
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from migration.common.db import get_conn  # noqa: E402
from migration.common.excel_utils import strip_sql_comments  # noqa: E402

DIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "migration", "dist")

FIELDS = ("metric_id", "scope_id", "sql_role", "datasource_key", "period_start", "period_end",
          "sql_annotated", "sql_executable", "version_no", "is_active", "last_verify_status",
          "source_ref", "exec_mode", "ds_flag", "period_flag", "emr_sys",
          "last_modify_user", "last_modify_reason")


def norm_sql(t):
    return re.sub(r"\s+", " ", strip_sql_comments(str(t or ""))).strip().upper()


def main():
    with open(os.path.join(DIST, "sqls.json"), encoding="utf-8") as f:
        sqls = json.load(f)["sqls"]

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT d.metric_code || '|' || s.resp_dept_name, d.id, s.id "
                "FROM eval_metric_dept_scope s JOIN eval_metric_dict d ON d.id = s.metric_id")
            scope_map = {r[0]: (r[1], r[2]) for r in cur.fetchall()}
            cur.execute("SELECT source_ref, id FROM eval_metric_sql")
            existing = dict(cur.fetchall())

        skipped = 0
        matched_records = []
        last_idx_by_group = {}
        for r in sqls:
            pair = scope_map.get(r["scope_key"])
            if pair is None:
                skipped += 1
                continue
            metric_id, scope_id = pair
            period_start = f"{r['year_from']}01"
            period_end = f"{r['year_to']}12"
            group_key = (metric_id, scope_id, r["sql_role"], period_start, period_end,
                         norm_sql(r["sql_executable"]))
            idx = len(matched_records)
            last_idx_by_group[group_key] = idx
            matched_records.append((r, metric_id, scope_id, period_start, period_end, group_key))

        inserted = updated = active_cnt = inactive_dup = 0
        with conn.cursor() as cur:
            for idx, (r, metric_id, scope_id, period_start, period_end, group_key) in enumerate(matched_records):
                is_active = (idx == last_idx_by_group[group_key])
                if is_active:
                    active_cnt += 1
                    modify_reason = "TASK-04 存量导入"
                else:
                    inactive_dup += 1
                    modify_reason = "TASK-04 存量导入（与同实例同角色另一列SQL完全一致，自动停用防重复跑批）"
                vals = {
                    "metric_id": metric_id,
                    "scope_id": scope_id,
                    "sql_role": r["sql_role"],
                    "datasource_key": r["datasource_key"],
                    "period_start": period_start,
                    "period_end": period_end,
                    "sql_annotated": r["sql_annotated"],
                    "sql_executable": r["sql_executable"],
                    "version_no": 1,
                    "is_active": is_active,
                    "last_verify_status": 0,
                    "source_ref": r["source_ref"],
                    "exec_mode": "RAW",
                    "ds_flag": r["ds_flag"],
                    "period_flag": r["period_flag"],
                    "emr_sys": r["emr_sys"],
                    "last_modify_user": "MIGRATION",
                    "last_modify_reason": modify_reason,
                }
                sid = existing.get(r["source_ref"])
                if sid is not None:
                    sets = ", ".join(f"{c} = %({c})s" for c in FIELDS if c != "metric_id")
                    vals["sid"] = sid
                    cur.execute(
                        f"UPDATE eval_metric_sql SET metric_id = %(metric_id)s, {sets} WHERE id = %(sid)s", vals)
                    updated += 1
                else:
                    cols = ", ".join(FIELDS)
                    phs = ", ".join(f"%({c})s" for c in FIELDS)
                    cur.execute(
                        f"INSERT INTO eval_metric_sql ({cols}) VALUES ({phs}) "
                        f"RETURNING id", vals)
                    new_id = cur.fetchone()[0]
                    cur.execute(
                        "INSERT INTO eval_metric_sql_his (sql_id, metric_id, sql_role, version_no, "
                        "change_type, change_reason, period_start, period_end, old_sql_annotated, "
                        "new_sql_annotated, modify_user_id, modify_user_name) "
                        "VALUES (%s, %s, %s, 1, '新增', '存量导入', %s, %s, NULL, %s, NULL, 'MIGRATION')",
                        (new_id, metric_id, r["sql_role"], vals["period_start"],
                         vals["period_end"], r["sql_annotated"]))
                    existing[r["source_ref"]] = new_id
                    inserted += 1
        conn.commit()
        print(f"eval_metric_sql inserted={inserted} updated={updated} active={active_cnt} "
              f"inactive_dup={inactive_dup} skipped(无scope)={skipped} eval_metric_sql_his 新增={inserted}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
