# -*- coding: utf-8 -*-
"""TASK-04 步骤 5.1：master_rows.json / metric_dict.json 幂等入库。

- master_rows.json   → eval_master_row   （冲突键 row_no）
- metric_dict.json   → eval_metric_dict  （冲突键 metric_code）
  指标字段先用总表（metric_dict.json）能得到的；空字段用 dept_scopes.json 中
  同一 metric_code 的第一个 ROOT_12 实例补齐；hosp_caliber_desc ← 实例 caliber_desc；
  specialty_code ← 实例 specialty_name。
可重复执行（第二次行数不变）。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from migration.common.db import get_conn, upsert_many  # noqa: E402

DIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "migration", "dist")
SOURCE_PRIORITY = {
    name: idx for idx, name in enumerate(("ROOT_12", "CH3_SHEET", "MEDICAL_DEPT", "CH3_FUNC", "SINGLE_DISEASE"))
}


def load(name):
    with open(os.path.join(DIST, name), encoding="utf-8") as f:
        return json.load(f)


def first_non_empty(scopes, field):
    for s in scopes:
        v = s.get(field)
        if v:
            return v
    return None


def main():
    master = load("master_rows.json")
    metric_dict = load("metric_dict.json")
    scopes_doc = load("dept_scopes.json")

    # 按来源优先级聚合同一 metric_code 的实例（ROOT_12 优先，其余来源补充空字段）
    scopes_by_mc = {}
    for s in scopes_doc["scopes"]:
        if s.get("metric_code"):
            scopes_by_mc.setdefault(s["metric_code"], []).append(s)
    for sc_list in scopes_by_mc.values():
        sc_list.sort(key=lambda s: SOURCE_PRIORITY.get(s.get("source_type"), 99))

    master_rows = [{
        "row_no": r["row_no"],
        "code": r["code"],
        "name": r["name"] or "",
        "row_type": r["row_type"],
        "parent_code": r["parent_code"],
        "level": r["level"],
        "metric_code": r["metric_code"],
        "atom_role": r["atom_role"],
        "orig_values_json": r["values"],
    } for r in master_doc_rows(master)]

    dict_rows = []
    for m in metric_dict["metrics"]:
        sc_list = scopes_by_mc.get(m["metric_code"], [])
        dict_rows.append({
            "metric_code": m["metric_code"],
            "alias_code": m["alias_code"],
            "master_row_no": m["master_row_no"],
            "num_atom_code": m["num_atom_code"],
            "den_atom_code": m["den_atom_code"],
            "extra_atoms_json": m["extra_atoms"] or None,
            "chapter_part": m["chapter_part"],
            "chapter_code": m["chapter_code"],
            "chapter_path": m["chapter_path"],
            "specialty_code": first_non_empty(sc_list, "specialty_name"),
            "metric_name": m["metric_name"],
            "metric_attr": m["metric_attr"] or first_non_empty(sc_list, "metric_attr") or "定量指标",
            "metric_category": m.get("metric_category") or first_non_empty(sc_list, "metric_category"),
            "unit": m.get("unit") or first_non_empty(sc_list, "unit"),
            "direction": m.get("direction") or first_non_empty(sc_list, "direction"),
            "extract_mode": m["extract_mode"],
            "agg_mode": "DEDUP_DTL",
            "is_applicable": bool(m["is_applicable"]),
            "inapplicable_reason": None,
            "std_formula_desc": first_non_empty(sc_list, "std_formula_desc"),
            "hosp_caliber_desc": first_non_empty(sc_list, "caliber_desc"),
            "data_sys_source": first_non_empty(sc_list, "sys_source"),
        })

    conn = get_conn()
    try:
        n1 = upsert_many(conn, "eval_master_row", master_rows,
                         ["row_no"], ["code", "name", "row_type", "parent_code", "level",
                                      "metric_code", "atom_role", "orig_values_json"])
        n2 = upsert_many(conn, "eval_metric_dict", dict_rows,
                         ["metric_code"],
                         ["alias_code", "master_row_no", "num_atom_code", "den_atom_code",
                          "extra_atoms_json", "chapter_part", "chapter_code", "chapter_path",
                          "specialty_code", "metric_name", "metric_attr", "metric_category",
                          "unit", "direction", "extract_mode", "agg_mode", "is_applicable",
                          "inapplicable_reason", "std_formula_desc", "hosp_caliber_desc",
                          "data_sys_source"])
        conn.commit()
        print(f"eval_master_row upsert={n1} eval_metric_dict upsert={n2}")
    finally:
        conn.close()


def master_doc_rows(master_doc):
    return master_doc["rows"]


if __name__ == "__main__":
    main()
