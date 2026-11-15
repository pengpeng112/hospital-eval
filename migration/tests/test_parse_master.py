# -*- coding: utf-8 -*-
"""TASK-01：总表解析产物断言（用例清单见 docs/tasks/TASK_01.md 步骤 4）。

依赖先生成产物：`python migration/00a_parse_master.py`。
"""
import json
from pathlib import Path

DIST = Path(__file__).resolve().parents[1] / "dist"
MASTER_PATH = DIST / "master_rows.json"
DICT_PATH = DIST / "metric_dict.json"


def _load(path: Path):
    if not path.is_file():
        from pytest import fail

        fail(f"产物不存在：{path}，请先运行 python migration/00a_parse_master.py")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def test_total_counts():
    master = _load(MASTER_PATH)
    counts = master["meta"]["counts"]
    assert counts["TOTAL"] == 4718
    assert counts["CHAPTER"] == 410
    assert counts["METRIC"] == 1485
    assert counts["ATOM"] == 2823


def _find_metric(metrics, code):
    for m in metrics:
        if m["metric_code"] == code:
            return m
    return None


def test_metric_25233351_atoms_and_alias():
    d = _load(DICT_PATH)
    m = _find_metric(d["metrics"], "25.2.3.3.5.1.1")
    assert m is not None
    assert m["num_atom_code"] == "0.25.252.1"
    assert m["den_atom_code"] == "0.25.252.2"
    assert m["alias_code"] == "NEU-EPI-01"


def test_metric_icu05_extra_atoms():
    d = _load(DICT_PATH)
    m = _find_metric(d["metrics"], "25.2.3.3.26.3")
    assert m is not None
    assert len(m["extra_atoms"]) == 4
    assert m["num_atom_code"] is None and m["den_atom_code"] is None


def test_every_metric_has_chapter_code_and_path():
    d = _load(DICT_PATH)
    missing = [
        m["metric_code"]
        for m in d["metrics"]
        if not m.get("chapter_code") or not m.get("chapter_path")
    ]
    assert missing == [], f"chapter_code/chapter_path 为空的指标: {missing[:10]}"


def test_atom_roles_on_master_rows():
    master = _load(MASTER_PATH)
    num = den = None
    for r in master["rows"]:
        if r["code"] == "0.25.252.1":
            num = r
        elif r["code"] == "0.25.252.2":
            den = r
    assert num is not None and den is not None
    assert num["atom_role"] == "NUM" and den["atom_role"] == "DEN"
    assert num["metric_code"] == "25.2.3.3.5.1.1"
    assert den["metric_code"] == "25.2.3.3.5.1.1"


def test_values_keys_fixed():
    master = _load(MASTER_PATH)
    for r in master["rows"][:50]:
        assert set(r["values"].keys()) == {"2022", "2023", "2024", "2025"}
        for v in r["values"].values():
            assert set(v.keys()) == {"data", "source"}
            assert v["data"] is None or isinstance(v["data"], str)
            assert v["source"] is None or isinstance(v["source"], str)
