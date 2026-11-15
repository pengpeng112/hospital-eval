# -*- coding: utf-8 -*-
"""TASK-04 步骤 5.2：科室 / 用户 / 一标多科实例 幂等入库。

- sys_dept：信息科(INFO) + 全部实例 resp_dept_name(CLINICAL) + 6 个职能部门(FUNC)
- sys_user：liaison_name(LIAISON，科室=实例科室) + func_contact_name(AUDITOR，科室=医疗部)；
  username=姓名，同名不同科室 → 姓名_科室；BCrypt(EVAL_INIT_USER_PASSWORD)；同一人双角色都给
- eval_metric_dept_scope：只导 metric_code 非空的实例；冲突键 (metric_id, resp_dept_id)
可重复执行（第二次行数不变）。
"""
import bcrypt
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from migration.common.db import get_conn, upsert_many  # noqa: E402

DIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "migration", "dist")
FUNC_DEPTS = ("医疗部", "护理部", "院感办", "药学部", "门诊部", "质控办")


def load(name):
    with open(os.path.join(DIST, name), encoding="utf-8") as f:
        return json.load(f)


def fetch_map(conn, sql):
    with conn.cursor() as cur:
        cur.execute(sql)
        return {r[0]: r[1] for r in cur.fetchall()}


def main():
    scopes = load("dept_scopes.json")["scopes"]
    dicts = load("metric_dict.json")["metrics"]
    dict_extract = {m["metric_code"]: m["extract_mode"] for m in dicts}

    conn = get_conn()
    try:
        # ---- sys_dept ----
        dept_rows = [{"dept_name": "信息科", "dept_type": "INFO"}]
        seen = {"信息科"}
        for s in scopes:
            name = s["resp_dept_name"]
            if name and name not in seen:
                seen.add(name)
                dept_rows.append({"dept_name": name, "dept_type": "CLINICAL"})
        for name in FUNC_DEPTS:
            if name not in seen:
                seen.add(name)
                dept_rows.append({"dept_name": name, "dept_type": "FUNC"})
        upsert_many(conn, "sys_dept", dept_rows, ["dept_name"], ["dept_type"])

        dept_id = fetch_map(conn, "SELECT dept_name, id FROM sys_dept")
        role_id = fetch_map(conn, "SELECT role_code, id FROM sys_role")

        # ---- sys_user（姓名 → [(角色, 科室)]）----
        name_roles = {}
        for s in scopes:
            if s.get("liaison_name"):
                name_roles.setdefault(s["liaison_name"], []).append(("LIAISON", s["resp_dept_name"]))
            if s.get("func_contact_name"):
                name_roles.setdefault(s["func_contact_name"], []).append(("AUDITOR", "医疗部"))
        init_pwd = os.getenv("EVAL_INIT_USER_PASSWORD", "")
        if not init_pwd:
            print("ERROR: deploy/.env 缺少 EVAL_INIT_USER_PASSWORD")
            sys.exit(2)
        pwd_hash = bcrypt.hashpw(init_pwd.encode("utf-8"), bcrypt.gensalt()).decode("ascii")

        # username 规则：同名但科室不同 → 姓名_科室
        user_defs = {}   # username → {real_name, dept, roles[]}
        for name, role_depts in name_roles.items():
            depts = sorted({d for _r, d in role_depts})
            if len(depts) == 1:
                username = name
                user_defs[username] = {"real_name": name, "dept": depts[0],
                                       "roles": sorted({r for r, _d in role_depts})}
            else:
                for d in depts:
                    user_defs[f"{name}_{d}"] = {"real_name": name, "dept": d,
                                                "roles": sorted({r for r, rd in role_depts if rd == d})}
        user_rows = [{
            "username": u,
            "real_name": v["real_name"],
            "dept_id": dept_id.get(v["dept"]),
            "password_hash": pwd_hash,
            "must_change_pwd": True,
            "status": 1,
        } for u, v in sorted(user_defs.items())]
        # 冲突时不覆盖 password_hash（避免重置用户已改密码）
        with conn.cursor() as cur:
            for r in user_rows:
                cur.execute(
                    "INSERT INTO sys_user (username, real_name, dept_id, password_hash, must_change_pwd, status) "
                    "VALUES (%(username)s, %(real_name)s, %(dept_id)s, %(password_hash)s, TRUE, 1) "
                    "ON CONFLICT (username) DO UPDATE SET real_name = EXCLUDED.real_name, dept_id = EXCLUDED.dept_id",
                    r)
        user_id = fetch_map(conn, "SELECT username, id FROM sys_user")

        link_rows = []
        seen_link = set()
        for u, v in sorted(user_defs.items()):
            for role in v["roles"]:
                rid = role_id.get(role)
                uid = user_id.get(u)
                if rid and uid and (uid, rid) not in seen_link:
                    seen_link.add((uid, rid))
                    link_rows.append({"user_id": uid, "role_id": rid})
        upsert_many(conn, "sys_user_role", link_rows, ["user_id", "role_id"], ["role_id"])

        # ---- eval_metric_dept_scope ----
        # (姓名, 角色) → user_id：优先精确 username；同名多科室时按 姓名_实例科室 匹配
        def user_by_name_role(name, role, dept=None):
            if not name:
                return None
            uid = user_id.get(name)
            if uid is not None and user_defs.get(name) and role in user_defs[name]["roles"]:
                return uid
            if dept and f"{name}_{dept}" in user_id and role in user_defs.get(f"{name}_{dept}", {}).get("roles", []):
                return user_id[f"{name}_{dept}"]
            # 同名多科室时兜底：取该姓名下含此角色的任一用户
            for u, v in user_defs.items():
                if v["real_name"] == name and role in v["roles"]:
                    return user_id.get(u)
            return None

        scope_rows = []
        for s in scopes:
            if not s.get("metric_code"):
                continue
            dept_name = s["resp_dept_name"]
            override = None
            if dict_extract.get(s["metric_code"]) != s["extract_mode"]:
                override = s["extract_mode"]
            scope_rows.append({
                "metric_code": s["metric_code"],   # 占位列，落库前替换为 metric_id
                "resp_dept_name": dept_name,
                "his_dept_names": ",".join(s["his_dept_names"]),
                "liaison_user_id": user_by_name_role(s.get("liaison_name"), "LIAISON", dept_name),
                "auditor_user_id": user_by_name_role(s.get("func_contact_name"), "AUDITOR", "医疗部"),
                "func_leader_user_id": None,
                "extract_mode_override": override,
                "skip_flag": bool(s["skip_flag"]),
                "skip_reason": s["skip_reason"],
                "specialty_name": s["specialty_name"],
                "caliber_desc": s["caliber_desc"],
                "confirm_person": s["confirm_person"],
                "source_ref": s["source_ref"],
                "font_color": s["font_color"],
            })
        # metric_code → metric_id，合并出 metric_id + resp_dept_id
        metric_id = fetch_map(conn, "SELECT metric_code, id FROM eval_metric_dict")
        final_rows = []
        seen_scope = set()
        for r in scope_rows:
            mid = metric_id.get(r["metric_code"])
            did = dept_id.get(r["resp_dept_name"])
            if mid is None or did is None:
                continue
            key = (mid, did)
            if key in seen_scope:
                continue
            seen_scope.add(key)
            row = {k: v for k, v in r.items() if k != "metric_code"}
            row["metric_id"] = mid
            row["resp_dept_id"] = did
            final_rows.append(row)
        upsert_many(conn, "eval_metric_dept_scope", final_rows,
                    ["metric_id", "resp_dept_id"],
                    ["resp_dept_name", "his_dept_names", "liaison_user_id", "auditor_user_id",
                     "func_leader_user_id", "extract_mode_override", "skip_flag", "skip_reason",
                     "specialty_name", "caliber_desc", "confirm_person", "source_ref", "font_color"])
        conn.commit()
        print(f"sys_dept={len(dept_rows)} sys_user={len(user_rows)} sys_user_role={len(link_rows)} "
              f"eval_metric_dept_scope={len(final_rows)} (跳过 metric_code 为空实例 "
              f"{sum(1 for s in scopes if not s.get('metric_code'))} 条)")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
