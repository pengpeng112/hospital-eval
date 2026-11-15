# -*- coding: utf-8 -*-
"""TASK-02：专科表解析产物断言（用例清单见 docs/tasks/TASK_02.md 步骤 7）。

依赖先生成产物：`python migration/00b_parse_specialty.py`。
"""
import json
import re
from pathlib import Path

DIST = Path(__file__).resolve().parents[1] / "dist"
SCOPE_PATH = DIST / "dept_scopes.json"
SQL_PATH = DIST / "sqls.json"

_SQL_HEAD_RE = re.compile(r"^[\s(]*(SELECT|WITH)\b", re.IGNORECASE)


def _load(path: Path):
    if not path.is_file():
        from pytest import fail

        fail(f"产物不存在：{path}，请先运行 python migration/00b_parse_specialty.py")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _data():
    sc = _load(SCOPE_PATH)
    sq = _load(SQL_PATH)
    return sc, sq


def _root12_files(scopes):
    return {s["source_ref"].split("!")[0] for s in scopes if s["source_type"] == "ROOT_12"}


def test_root12_main_child_instance_total_1452():
    """ROOT_12 主行+子行实例总数 = 1452（AGENTS.md 口径，含后续被契约去重的重复行）。

    契约（04 §7.2 / TASK-02 步骤 2）规定同一 scope_key 重复时保留第一个、第二个记入
    "重复实例"，因此：实例总数（主行+子行）= 保留的 ROOT_12 scopes + ROOT_12 重复实例。
    """
    sc, _ = _data()
    scopes = sc["scopes"]
    r12_files = _root12_files(scopes)
    r12_scopes = [s for s in scopes if s["source_type"] == "ROOT_12"]
    r12_dups = [d for d in sc.get("duplicates", [])
                if d["dup_ref"].split("!")[0] in r12_files]
    assert len(r12_scopes) + len(r12_dups) == 1452, (
        f"ROOT_12 保留实例 {len(r12_scopes)} + 重复实例 {len(r12_dups)} "
        f"= {len(r12_scopes) + len(r12_dups)} != 1452")


def test_root12_manual_needed_at_most_1():
    sc, _ = _data()
    n = sum(1 for s in sc["scopes"]
            if s["source_type"] == "ROOT_12" and s["match_method"] == "MANUAL_NEEDED")
    assert n <= 1


def test_infection_den_sql_roles():
    sq = _load(SQL_PATH)
    hits = [s for s in sq["sqls"]
            if s["source_ref"].startswith("十六、感染性疾病专业医疗质量控制指标")
            and s["header"] == "分母SQL"]
    assert hits, "感染专业未找到表头为 分母SQL 的 SQL 记录"
    bad = [s for s in hits if not s["sql_role"].startswith("DEN_")]
    assert bad == [], f"分母SQL 表头的 sql_role 非 DEN_ 前缀: {[s['sql_role'] for s in bad]}"


def test_brain_injury_no_url_sql():
    sq = _load(SQL_PATH)
    bad = [s for s in sq["sqls"]
           if s["source_ref"].startswith("二十一、脑损伤评价医疗质量控制指标")
           and s["sql_annotated"].lstrip().lower().startswith("https://")]
    assert bad == [], f"脑损伤表存在 https:// 开头的 SQL 记录: {[s['source_ref'] for s in bad]}"


def test_neuro_2025_numbers_go_to_non_sql_only():
    sq = _load(SQL_PATH)
    neuro = "五、神经系统疾病医疗质量控制指标（2020 年版）.xlsx"
    sql_refs = {s["source_ref"] for s in sq["sqls"]
                if s["source_ref"].startswith(neuro) and s["header"] == "2025分子"}
    number_refs = {c["source_ref"] for c in sq["non_sql_cells"]
                   if c["source_ref"].startswith(neuro)
                   and c["header"] == "2025分子" and c["kind"] == "NUMBER"}
    assert number_refs, "神经表 2025分子 列未发现 NUMBER 类单元格（预期 24/25 列重复表头含数值）"
    overlap = sql_refs & number_refs
    assert not overlap, f"NUMBER 单元格混入了 sqls: {sorted(overlap)[:5]}"


def test_all_sql_executable_start_with_select_or_with():
    sq = _load(SQL_PATH)
    bad = [s["source_ref"] for s in sq["sqls"]
           if not _SQL_HEAD_RE.match(s["sql_executable"])]
    assert bad == [], f"sql_executable 不以 SELECT/WITH 开头: {bad[:5]}"


def test_respiratory_sum_dominates_dtl():
    sq = _load(SQL_PATH)
    roles = [s["sql_role"] for s in sq["sqls"]
             if s["source_ref"].startswith("三、呼吸内科专业医疗质量控制指标")]
    assert roles, "呼吸专业未找到 SQL 记录"
    sum_cnt = roles.count("NUM_SUM") + roles.count("DEN_SUM")
    dtl_cnt = roles.count("NUM_DTL") + roles.count("DEN_DTL")
    assert sum_cnt > dtl_cnt, f"呼吸 SUM={sum_cnt} 不大于 DTL={dtl_cnt}"


def test_sql_schema_invariants():
    sq = _load(SQL_PATH)
    assert len({s["sql_key"] for s in sq["sqls"]}) == len(sq["sqls"]), "sql_key 存在重复"
    for s in sq["sqls"]:
        assert s["formal_file_match"] is None  # TASK-03 才回填
        assert s["sql_role"] in ("NUM_DTL", "DEN_DTL", "NUM_SUM", "DEN_SUM")
        assert s["ds_flag"] in (None, "DS_CONFLICT", "DS_UNKNOWN")
        assert s["period_flag"] in (None, "PERIOD_CONFLICT")


def test_scope_schema_and_uniqueness():
    sc, _ = _data()
    keys = [s["scope_key"] for s in sc["scopes"]]
    assert len(keys) == len(set(keys)), "dept_scopes 存在重复 scope_key"
    for s in sc["scopes"]:
        assert s["match_method"] in ("CODE", "NAME", "MANUAL", "MANUAL_NEEDED")
        assert s["source_type"] in ("ROOT_12", "CH3_SHEET", "CH3_FUNC", "MEDICAL_DEPT", "SINGLE_DISEASE")
        assert s["extract_mode"] in ("SYS", "MANUAL", "HYBRID", "PROV")
        assert isinstance(s["skip_flag"], bool)
