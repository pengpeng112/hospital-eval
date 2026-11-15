# -*- coding: utf-8 -*-
"""TASK-04 步骤 5.4：历史基线 baseline_values.json 入库（批次 BATCH_BASELINE_IMPORT）。

- 全院行（scope_id=0）：METRIC 行数值→result_val、非数字文本→qualitative_result；
  NUM 原子→numerator_val；DEN 原子→denominator_val；SINGLE 原子→numerator_val；
  EXTRA 原子→atom_values_json；data_source_tag 按该年数据来源映射。
- 科室行：scope_values → 对应 scope_id 行，numerator_val/denominator_val，tag='HYBRID'。
- 幂等：先删本批次全部 eval_metric_result 再插入（只删 BATCH_BASELINE_IMPORT 的）。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from migration.common.db import get_conn  # noqa: E402

DIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "migration", "dist")
BATCH_NO = "BATCH_BASELINE_IMPORT"


def source_tag(text):
    """总表数据来源文本 → SYS / HYBRID / MANUAL（04 步骤 5.4：系统→SYS、系统+手工→HYBRID、其他→MANUAL）。"""
    t = str(text or "")
    if "系统" in t and "手工" in t:
        return "HYBRID"
    if "系统" in t:
        return "SYS"
    return "MANUAL"


def to_num(v):
    if v is None:
        return None
    try:
        return float(str(v).replace("%", "").strip())
    except (ValueError, TypeError):
        return None


def main():
    with open(os.path.join(DIST, "baseline_values.json"), encoding="utf-8") as f:
        base = json.load(f)
    master_values = base["master_values"]
    scope_values = base["scope_values"]

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO eval_exec_batch (batch_no, trigger_type, is_current_version, version_tag, "
                "exec_status, operator_name, end_time) "
                "VALUES (%s, 'IMPORT', TRUE, '存量基线导入', 'SUCCESS', 'MIGRATION', CURRENT_TIMESTAMP) "
                "ON CONFLICT (batch_no) DO UPDATE SET trigger_type='IMPORT', is_current_version=TRUE, "
                "version_tag='存量基线导入', exec_status='SUCCESS' "
                "RETURNING id", (BATCH_NO,))
            batch_id = cur.fetchone()[0]
            cur.execute("DELETE FROM eval_metric_result WHERE batch_id = %s", (batch_id,))

            cur.execute("SELECT metric_code, id FROM eval_metric_dict")
            metric_id = dict(cur.fetchall())
            cur.execute(
                "SELECT d.metric_code || '|' || s.resp_dept_name, s.id "
                "FROM eval_metric_dept_scope s JOIN eval_metric_dict d ON d.id = s.metric_id")
            scope_map = dict(cur.fetchall())

            # ---- 全院行：按 (metric_code, year) 汇总 master_values ----
            groups = {}
            for m in master_values:
                code = m.get("metric_code")
                if not code:
                    continue
                groups.setdefault((code, m["year"]), []).append(m)
            hospital_rows = 0
            for (code, year), items in sorted(groups.items()):
                metric_entry = next((x for x in items if x["row_type"] == "METRIC"), None)
                result_val = qualitative = None
                numerator = denominator = None
                atom_json = {}
                source_text = None
                for x in items:
                    nv = to_num(x["data"])
                    if x["row_type"] == "METRIC":
                        source_text = source_text or x.get("source")
                        if nv is not None:
                            result_val = nv
                        elif x.get("data"):
                            qualitative = str(x["data"])[:64]
                    elif x["atom_role"] == "NUM" and nv is not None:
                        numerator = nv
                    elif x["atom_role"] == "DEN" and nv is not None:
                        denominator = nv
                    elif x["atom_role"] == "SINGLE" and nv is not None:
                        numerator = nv
                    elif x["atom_role"] == "EXTRA" and nv is not None:
                        atom_json[x["code"]] = nv
                    if source_text is None:
                        source_text = x.get("source")
                mid = metric_id.get(code)
                if mid is None:
                    continue
                cur.execute(
                    "INSERT INTO eval_metric_result (batch_id, metric_id, scope_id, stat_period_type, "
                    "stat_period, numerator_val, denominator_val, result_val, qualitative_result, "
                    "atom_values_json, data_source_tag, audit_status, is_current) "
                    "VALUES (%s, %s, 0, 'YEAR', %s, %s, %s, %s, %s, %s, %s, 'DRAFT', TRUE)",
                    (batch_id, mid, str(year), numerator, denominator, result_val, qualitative,
                     json.dumps(atom_json) if atom_json else None, source_tag(source_text)))
                hospital_rows += 1

            # ---- 科室行 ----
            scope_rows = 0
            skipped = 0
            for v in scope_values:
                sid = scope_map.get(v["scope_key"])
                if sid is None:
                    skipped += 1
                    continue
                mid = metric_id.get(v["scope_key"].rsplit("|", 1)[0])
                if mid is None:
                    skipped += 1
                    continue
                cur.execute(
                    "INSERT INTO eval_metric_result (batch_id, metric_id, scope_id, stat_period_type, "
                    "stat_period, numerator_val, denominator_val, data_source_tag, audit_status, is_current) "
                    "VALUES (%s, %s, %s, 'YEAR', %s, %s, %s, 'HYBRID', 'DRAFT', TRUE)",
                    (batch_id, mid, sid, str(v["year"]), v["num"], v["den"]))
                scope_rows += 1
        conn.commit()
        print(f"batch={BATCH_NO} 全院行={hospital_rows} 科室行={scope_rows} 跳过(无scope)={skipped}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
