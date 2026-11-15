# -*- coding: utf-8 -*-
"""TASK-03：交叉比对与基线产物断言（用例清单见 docs/tasks/TASK_03.md 步骤 5）。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SQLS = ROOT / "migration" / "dist" / "sqls.json"
BASELINE = ROOT / "migration" / "dist" / "baseline_values.json"
LEDGER_MD = ROOT / "docs" / "reports" / "存量指标与SQL清洗预检台账.md"
LEDGER_XLSX = ROOT / "docs" / "reports" / "存量指标与SQL清洗预检台账.xlsx"


def _load(path: Path):
    if not path.is_file():
        from pytest import fail

        fail(f"产物不存在：{path}，请先运行 python migration/00c_crosscheck_and_report.py")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def test_formal_file_match_filled_and_valid():
    sq = _load(SQLS)
    sqls = sq["sqls"]
    assert sqls, "sqls 为空"
    bad = [r["sql_key"] for r in sqls if r.get("formal_file_match") is None]
    assert bad == [], f"formal_file_match 为 null: {bad[:5]}"
    bad_status = [r["sql_key"] for r in sqls
                  if r["formal_file_match"]["status"] not in ("SAME", "DIFF", "NOT_FOUND")]
    assert bad_status == [], f"status 非法: {bad_status[:5]}"
    counts = sq["meta"]["counts"]
    c = {"SAME": 0, "DIFF": 0, "NOT_FOUND": 0}
    for r in sqls:
        c[r["formal_file_match"]["status"]] += 1
    assert counts["formal_same"] == c["SAME"]
    assert counts["formal_diff"] == c["DIFF"]
    assert counts["formal_not_found"] == c["NOT_FOUND"]


def test_baseline_master_value_252_1_2025():
    b = _load(BASELINE)
    hits = [m for m in b["master_values"]
            if m["code"] == "0.25.252.1" and m["year"] == 2025]
    assert hits, "master_values 缺少 0.25.252.1 / 2025"
    assert any(m["num_value"] == 84.0 for m in hits), \
        f"0.25.252.1/2025 的 num_value 应为 84.0，实际 {[m['num_value'] for m in hits]}"


def test_baseline_scope_values_structure():
    b = _load(BASELINE)
    sv = b["scope_values"]
    assert sv, "scope_values 为空"
    for v in sv[:100]:
        assert set(v.keys()) == {"scope_key", "year", "num", "den", "source_ref"}
        assert isinstance(v["year"], int) and 2022 <= v["year"] <= 2025
        assert v["num"] is None or isinstance(v["num"], (int, float))
        assert v["den"] is None or isinstance(v["den"], (int, float))


def test_ledger_files_exist_and_nonempty():
    for p in (LEDGER_MD, LEDGER_XLSX):
        assert p.is_file(), f"台账文件不存在：{p}"
        assert p.stat().st_size > 1024, f"台账文件小于 1KB：{p}"
