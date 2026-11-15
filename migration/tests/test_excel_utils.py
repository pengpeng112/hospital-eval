# -*- coding: utf-8 -*-
"""TASK-01：公共解析库单元测试（用例清单见 docs/tasks/TASK_01.md 步骤 4）。"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from migration.common.excel_utils import (  # noqa: E402
    classify_cell,
    detect_result_shape,
    detect_skip,
    extract_alias_code,
    extract_output_columns,
    final_year_range,
    header_year_range,
    literal_year_range,
    map_extract_mode_from_raw,
    map_extract_mode_from_source,
    normalize_code,
    normalize_metric_name,
    route_datasource,
    split_dept_range,
    strip_sql_comments,
)


# ---- strip_sql_comments（04 §7.4 必测用例 + 补充） ----
def test_strip_line_comment_keeps_string():
    assert strip_sql_comments("SELECT '--不是注释' AS a FROM dual -- 注释") == \
        "SELECT '--不是注释' AS a FROM dual"


def test_strip_block_comment_and_semicolon():
    assert strip_sql_comments("/* 块 */ SELECT 1 FROM dual;") == "SELECT 1 FROM dual"


def test_strip_keeps_double_dash_inside_string_with_escape():
    sql = "SELECT 'it''s -- ok' FROM dual -- tail"
    assert strip_sql_comments(sql) == "SELECT 'it''s -- ok' FROM dual"


def test_strip_keeps_quoted_identifier():
    sql = 'SELECT "weird -- name" FROM t /* c */'
    assert strip_sql_comments(sql) == 'SELECT "weird -- name" FROM t'


def test_strip_multiline_line_comment_keeps_newline():
    sql = "SELECT 1 FROM dual -- note\nWHERE x = 1"
    assert strip_sql_comments(sql) == "SELECT 1 FROM dual \nWHERE x = 1"


# ---- classify_cell（04 §7.3） ----
def test_classify_comment_only():
    assert classify_cell("-- 手工统计项,不生成 SQL") == "COMMENT_ONLY"


def test_classify_dirty():
    assert classify_cell("https://pmi.NAME") == "DIRTY"
    assert classify_cell("sql") == "DIRTY"
    assert classify_cell("SQL") == "DIRTY"


def test_classify_number():
    assert classify_cell(129) == "NUMBER"
    assert classify_cell("  33 ") == "NUMBER"
    assert classify_cell("84.0") == "NUMBER"


def test_classify_sql_with_cte():
    assert classify_cell("WITH a AS (SELECT 1 FROM dual) SELECT * FROM a") == "SQL"


def test_classify_empty_and_text():
    assert classify_cell(None) == "EMPTY"
    assert classify_cell("   ") == "EMPTY"
    assert classify_cell("请外科统计") == "TEXT"


# ---- 年份范围（04 §7.6） ----
def test_literal_year_range_mid_year_start():
    assert literal_year_range("... DATE '2024-06-01' ... DATE '2026-01-01'") == (2025, 2025)


def test_literal_year_range_jan_bounds():
    assert literal_year_range("... DATE '2022-01-01' ... DATE '2025-01-01'") == (2022, 2024)


def test_literal_year_range_none_when_no_dates():
    assert literal_year_range("SELECT 1 FROM dual") is None


def test_final_year_range_conflict_prefers_literal():
    assert final_year_range("2021-2024分母", "... DATE '2025-01-01' ... DATE '2026-01-01'") == \
        (2025, 2025, "PERIOD_CONFLICT")


def test_final_year_range_header_fallback_and_clamp():
    assert final_year_range("2025分子", "") == (2025, 2025, None)
    assert final_year_range("无年份表头", "") == (2022, 2025, None)
    assert final_year_range("2022-2024分母", "SELECT 1 FROM dual") == (2022, 2024, None)


def test_header_year_range_basic():
    assert header_year_range("2021-2024分子") == (2022, 2024)
    assert header_year_range("2022-2024分母") == (2022, 2024)
    assert header_year_range("2025分子") == (2025, 2025)
    assert header_year_range("2022-2025分子") == (2022, 2025)


# ---- 数据源路由（04 §7.7） ----
def test_route_his_group():
    assert route_datasource("SELECT 1 FROM MEDREC.PAT_VISIT v JOIN COMM.DEPT_DICT d ON 1=1") == \
        ("HIS_RO", None, "NONE")


def test_route_ods_group_mtl_jhemr():
    assert route_datasource("SELECT 1 FROM HIS.PAT_VISIT v JOIN MTL.X m ON 1=1 JOIN JHEMR.Y j ON 1=1") == \
        ("ODS_RO", None, "MTL+JHEMR")


def test_route_conflict():
    assert route_datasource("SELECT 1 FROM HIS.A a JOIN MEDREC.B b ON 1=1")[1] == "DS_CONFLICT"


def test_route_unknown():
    assert route_datasource("SELECT 1 FROM dual")[0] == "ODS_RO"
    assert route_datasource("SELECT 1 FROM dual")[1] == "DS_UNKNOWN"


# ---- 别名代号 / 名称标准化 / 编号标准化（04 §7.1 / §7.10） ----
def test_extract_alias_code_removes_whitespace():
    assert extract_alias_code("出院时脑梗死患者抗栓治疗率（NEU-STK- 15A）") == "NEU-STK-15A"
    assert extract_alias_code("癫痫发作频率记录率（NEU-EPI-01）") == "NEU-EPI-01"
    assert extract_alias_code("无代号指标") is None


def test_normalize_code_strips_paren_suffix():
    assert normalize_code("25.2.3.3.1(22)") == "25.2.3.3.1"
    assert normalize_code("25.2.3.3.1(ps)") == "25.2.3.3.1"
    assert normalize_code(" 0.25.252.1 ") == "0.25.252.1"


def test_normalize_metric_name_variants():
    assert normalize_metric_name("1.癫痫发作频率记录率（NEU-EPI-01）") == "癫痫发作频率记录率"
    assert normalize_metric_name("（一）癫痫与惊厥癫痫持续状态") == "癫痫与惊厥癫痫持续状态"
    assert normalize_metric_name("15.1 出院患者住院死亡率") == "出院患者住院死亡率"
    assert normalize_metric_name("48h\u3000内肠内营养（EN）启动率") == "48h内肠内营养启动率"


# ---- 结果形态 / 输出列（04 §7.5） ----
def test_detect_result_shape_sum():
    assert detect_result_shape("SELECT x AS 年份, COUNT(*) AS 分子 FROM t GROUP BY x") == ("SUM", False)


def test_detect_result_shape_dtl():
    assert detect_result_shape("SELECT a AS 患者ID FROM t") == ("DTL", False)


def test_detect_result_shape_guess():
    assert detect_result_shape("SELECT a FROM t") == ("DTL", True)


def test_extract_output_columns_dedup_keep_order():
    sql = 'SELECT 年份 AS "年份", a AS 患者ID, b AS 住院号, c AS 患者ID FROM t'
    assert extract_output_columns(sql) == ["年份", "患者ID", "住院号"]


# ---- 科室范围 / 提取方式 / 免统（04 §7.2 / §7.1 / §7.9 / §7.8） ----
def test_split_dept_range():
    assert split_dept_range("神经内一科、神经内二科，康复科; 针灸科\n推拿科") == \
        ["神经内一科", "神经内二科", "康复科", "针灸科", "推拿科"]
    assert split_dept_range("") == []
    assert split_dept_range(None) == []


def test_map_extract_mode_from_source():
    assert map_extract_mode_from_source("医院系统提取") == ("SYS", True)
    assert map_extract_mode_from_source("医院系统提取+手工统计") == ("HYBRID", True)
    assert map_extract_mode_from_source("手工统计") == ("MANUAL", True)
    assert map_extract_mode_from_source("省级系统提取") == ("PROV", True)
    assert map_extract_mode_from_source("不适用") == ("MANUAL", False)
    assert map_extract_mode_from_source("") == ("MANUAL", True)
    assert map_extract_mode_from_source(None) == ("MANUAL", True)


def test_map_extract_mode_from_raw():
    assert map_extract_mode_from_raw("系统提取+手工核对", "SYS") == "HYBRID"
    assert map_extract_mode_from_raw("人工填报", "SYS") == "MANUAL"
    assert map_extract_mode_from_raw("医院系统提取", "MANUAL") == "SYS"
    assert map_extract_mode_from_raw("", "HYBRID") == "HYBRID"


def test_detect_skip_hit():
    hit, reason = detect_skip("本科室无手术，请外科统计")
    assert hit is True
    assert reason
    assert detect_skip("本科室无手术，请外科统计")[0] is True


def test_detect_skip_miss():
    assert detect_skip("正常统计口径", None) == (False, None)
