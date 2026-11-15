# -*- coding: utf-8 -*-
"""TASK-02：专科表 / 第三章总表 / 医疗部表解析（科室实例 + SQL）。

输出（04 §6.3 / §6.4）：
- migration/dist/dept_scopes.json   科室实例（一标多科）
- migration/dist/sqls.json          分子/分母 SQL 与非 SQL 单元格台账
- migration/manual_mapping.json     人工确认映射（不存在时生成 null 骨架；存在时只读使用）
- docs/reports/TASK02_专科表解析报告.md

解析规则：04 §7.2~§7.10；源 Excel 只读，不做任何写操作。
"""
import difflib
import glob
import json
import os
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from migration.common.excel_utils import (  # noqa: E402
    classify_cell,
    detect_result_shape,
    detect_skip,
    extract_alias_code,
    extract_output_columns,
    final_year_range,
    map_extract_mode_from_raw,
    normalize_metric_name,
    route_datasource,
    split_dept_range,
    strip_sql_comments,
    write_json,
)

BASE = r"E:\三甲复审\1、三甲复审统计指标"
CH3_PATH = os.path.join(BASE, "02_重点专业指标", "第三章重点专业质量控制指标.xlsx")
MEDICAL_PATH = os.path.join(BASE, "医疗部数据提取.xlsx")
SINGLE_PATH = os.path.join(BASE, "单病种(按主要诊断_主要手术统计)2025年.xlsx")
LIAISON_PATH = os.path.join(BASE, "科室分工重点专业质量控制指标各科室联络人(1).xlsx")

DIST_DIR = os.path.join(ROOT, "migration", "dist")
MANUAL_MAPPING_PATH = os.path.join(ROOT, "migration", "manual_mapping.json")
SCOPE_JSON = os.path.join(DIST_DIR, "dept_scopes.json")
SQL_JSON = os.path.join(DIST_DIR, "sqls.json")
REPORT_MD = os.path.join(ROOT, "docs", "reports", "TASK02_专科表解析报告.md")

CN_NUM_RE = re.compile(r"^([一二三四五六七八九十百零]+)\s*、")
COL_BOUND_LIMIT = 200   # Sheet 列数超过该值时只读前 60 列（04 §7.2 / TASK-02 步骤 2）
COL_BOUND_SMALL = 60
NON_SQL_KINDS = ("COMMENT_ONLY", "NUMBER", "DIRTY", "TEXT")

# 04 §7.2 表头关键字（顺序即优先级；从左到右第一个命中）
HEADER_FIELDS = (
    ("metric_name", lambda h: h == "指标"),
    ("metric_attr", lambda h: "指标属性" in h),
    ("unit", lambda h: "计量单位" in h),
    ("std_formula_desc", lambda h: "计算方法" in h),
    ("direction", lambda h: "指标导向" in h),
    ("metric_category", lambda h: "指标分类" in h),
    ("resp_dept_name", lambda h: ("负责部门" in h) or ("负责科室" in h)),
    ("extract_mode_raw", lambda h: "提取方式" in h),
    ("caliber_desc", lambda h: "统计口径" in h),
    ("sys_source", lambda h: "业务系统来源" in h),
    ("confirm_person", lambda h: "口径确认人" in h),
    ("remark", lambda h: "备注" in h),
    ("dept_range", lambda h: "科室范围" in h),
)


def cn_num(text):
    """取文本开头的中文序号（如 ``五、`` → ``五``），没有则返回 None。"""
    if not text:
        return None
    m = CN_NUM_RE.match(str(text).strip())
    return m.group(1) if m else None


def cell_str(v):
    """单元格 → 去空白文本或 None（用于普通字段）。"""
    if v is None:
        return None
    s = str(v).strip()
    return s if s else None


def font_color_of(cell):
    """读单元格字体颜色并映射（04 §7.2）：FFFF0000→RED / FF000000→BLACK /
    FF7030A0→PURPLE / 其他或无 → DEFAULT。"""
    try:
        color = cell.font.color if cell.font is not None else None
    except AttributeError:
        return "DEFAULT"
    if color is None or getattr(color, "type", None) != "rgb":
        return "DEFAULT"
    rgb = getattr(color, "rgb", None)
    if not rgb:
        return "DEFAULT"
    rgb = str(rgb).upper()
    if rgb == "FFFF0000":
        return "RED"
    if rgb == "FF000000":
        return "BLACK"
    if rgb == "FF7030A0":
        return "PURPLE"
    return "DEFAULT"


def map_headers(header_cells, max_bound):
    """按 04 §7.2 扫描第 2 行表头，返回 (标准字段→列号, SQL列→表头, resp_dept是否关键字命中)。

    ``header_cells`` 为 {列号: 表头文本}。SQL 列 = 表头含 ``分子`` 或 ``分母`` 的所有列。
    ``resp_dept_name`` 找不到关键字时兜底第 7 列（契约允许的唯一兜底）。
    """
    mapping = {}
    sql_cols = {}
    resp_found = False
    for col in sorted(header_cells):
        if col > max_bound:
            break
        h = header_cells[col]
        if not h:
            continue
        if ("分子" in h) or ("分母" in h):
            sql_cols[col] = h
            continue
        for field, test in HEADER_FIELDS:
            if field in mapping:
                continue
            if test(h):
                mapping[field] = col
                if field == "resp_dept_name":
                    resp_found = True
                break
    if "resp_dept_name" not in mapping:
        mapping["resp_dept_name"] = 7
    return mapping, sql_cols, resp_found


def load_liaison():
    """步骤 1：读分工联络人表。

    返回 (pair_map, spec_first)：
    - pair_map: (专业序号, 科室) → {liaison_name, func_contact_name, dept_range}
    - spec_first: 专业序号 → 第一行的同一结构（兜底）
    """
    import openpyxl

    wb = openpyxl.load_workbook(LIAISON_PATH, read_only=True, data_only=True)
    ws = wb["Sheet1"]
    pair_map = {}
    spec_first = {}
    cur_num = None
    for row in ws.iter_rows(min_row=2, max_col=5, values_only=True):
        spec = cell_str(row[0]) if len(row) > 0 else None
        if spec:
            cur_num = cn_num(spec)
        dept = cell_str(row[1]) if len(row) > 1 else None
        if not dept or not cur_num:
            continue
        info = {
            "liaison_name": cell_str(row[2]) if len(row) > 2 else None,
            "func_contact_name": cell_str(row[3]) if len(row) > 3 else None,
            "dept_range": cell_str(row[4]) if len(row) > 4 else None,
        }
        pair_map[(cur_num, dept)] = info
        spec_first.setdefault(cur_num, info)
    wb.close()
    return pair_map, spec_first


def load_metric_dict():
    """加载 TASK-01 的 metric_dict.json，建立别名/名称匹配索引。"""
    with open(os.path.join(DIST_DIR, "metric_dict.json"), encoding="utf-8") as f:
        d = json.load(f)
    metrics = d["metrics"]
    alias_idx = defaultdict(set)
    name_idx = defaultdict(set)
    by_code = {}
    for m in metrics:
        code = m["metric_code"]
        by_code[code] = m
        if m.get("alias_code"):
            alias_idx[m["alias_code"]].add(code)
        norm = normalize_metric_name(m.get("metric_name") or "")
        if norm:
            name_idx[norm].add(code)
    return metrics, alias_idx, name_idx, by_code


class Matcher:
    """04 §7.10 指标匹配：CODE → NAME → MANUAL → MANUAL_NEEDED（含候选）。"""

    def __init__(self, alias_idx, name_idx, manual_map):
        self.alias_idx = alias_idx
        self.name_idx = name_idx
        self.manual_map = manual_map
        self.unmatched = []          # [{source_ref, excel_metric_name, candidates}]
        self._cache = {}

    def candidates_for(self, norm):
        """最多 3 个候选：标准化名称包含关系优先，其次编辑距离最小。"""
        scored = []
        for n, codes in self.name_idx.items():
            if not n or not norm:
                continue
            if n in norm or norm in n:
                scored.append((0, -abs(len(n) - len(norm)), difflib.SequenceMatcher(None, norm, n).ratio(), codes))
            else:
                ratio = difflib.SequenceMatcher(None, norm, n).ratio()
                if ratio >= 0.5:
                    scored.append((1, -ratio, ratio, codes))
        scored.sort(key=lambda t: (t[0], t[1], t[2]), reverse=False)
        # 包含关系组在前（tag 0），组内按长度差小优先；再按比率降序
        scored.sort(key=lambda t: (t[0], -t[2]))
        out = []
        for _, _, _, codes in scored[:3]:
            for c in sorted(codes):
                if c not in out:
                    out.append(c)
        return out[:3]

    def match(self, excel_name, source_ref):
        key = (excel_name, source_ref)
        if key in self._cache:
            return self._cache[key]
        result = self._match(excel_name, source_ref)
        self._cache[key] = result
        return result

    def _match(self, excel_name, source_ref):
        alias = extract_alias_code(excel_name or "")
        if alias and len(self.alias_idx.get(alias, ())) == 1:
            code = next(iter(self.alias_idx[alias]))
            return (code, "CODE")
        norm = normalize_metric_name(excel_name or "")
        if norm and len(self.name_idx.get(norm, ())) == 1:
            code = next(iter(self.name_idx[norm]))
            return (code, "NAME")
        mapped = self.manual_map.get(source_ref)
        if mapped:
            return (mapped, "MANUAL")
        cands = self.candidates_for(norm)
        self.unmatched.append({
            "source_ref": source_ref,
            "excel_metric_name": excel_name,
            "candidates": cands,
        })
        return (None, "MANUAL_NEEDED")


def parse_sheet(wb, file_name, sheet, source_type, specialty_name, seq_num,
                matcher, dict_by_code, liaison_pair, liaison_first,
                sink, stats):
    """解析一个 Sheet，生成科室实例与 SQL 记录（04 §7.2 行类型 + 步骤 2）。

    ``sink`` 需提供 scopes / sqls / non_sql_cells / anomalies / dup_scopes /
    multi_sql 列表容器（dict of list）。
    """
    ws = wb[sheet]
    dim_cols = ws.max_column or 0
    max_bound = COL_BOUND_SMALL if dim_cols > COL_BOUND_LIMIT else max(dim_cols, 1)

    # ---- 第 2 行表头 ----
    header_cells = {}
    for row in ws.iter_rows(min_row=2, max_row=2, max_col=max_bound):
        for cell in row:
            v = cell_str(cell.value)
            if v:
                header_cells[cell.column] = v
    mapping, sql_cols, resp_found = map_headers(header_cells, max_bound)
    stats.append({
        "file": file_name, "sheet": sheet, "source_type": source_type,
        "mapping": {k: mapping.get(k) for k, _ in HEADER_FIELDS},
        "resp_fallback": not resp_found,
        "sql_cols": dict(sorted(sql_cols.items())),
        "main": 0, "child": 0, "group": 0, "empty": 0, "anomaly": 0,
        "scope": 0, "skip": 0, "sql": 0,
        "non_sql": Counter(),
    })
    st = stats[-1]

    name_c = mapping.get("metric_name")
    formula_c = mapping.get("std_formula_desc")
    resp_c = mapping.get("resp_dept_name")
    attr_c = mapping.get("metric_attr")
    unit_c = mapping.get("unit")
    dir_c = mapping.get("direction")
    cat_c = mapping.get("metric_category")
    em_raw_c = mapping.get("extract_mode_raw")
    caliber_c = mapping.get("caliber_desc")
    sys_c = mapping.get("sys_source")
    confirm_c = mapping.get("confirm_person")
    remark_c = mapping.get("remark")
    range_c = mapping.get("dept_range")

    last_main = None       # 上一条主行的指标级信息（供子行继承）
    seen_scope_keys = sink["seen_scope_keys"]  # 全局去重（契约：同 scope_key 保留第一个，不限同一 Sheet）

    for row in ws.iter_rows(min_row=3, max_row=ws.max_row, max_col=max_bound):
        row_no = row[0].row if row else None
        if row_no is None:
            continue

        def gv(col):
            if col is None or col < 1 or col > len(row):
                return None
            return cell_str(row[col - 1].value)

        raw_sql_cells = {}
        has_any = False
        for cell in row:
            v = cell.value
            if v is not None and str(v).strip():
                has_any = True
            if cell.column in sql_cols and v is not None and str(v).strip():
                raw_sql_cells[cell.column] = v
        if not has_any:
            st["empty"] += 1
            continue

        name_v = gv(name_c)
        formula_v = gv(formula_c)
        resp_v = gv(resp_c)

        if name_v and not formula_v and not resp_v:
            st["group"] += 1          # 分组标题行：跳过，仅记当前分组
            continue
        if name_v and (resp_v or formula_v):
            is_main = True
        elif (not name_v) and resp_v:
            if last_main is None:
                st["anomaly"] += 1
                sink["anomalies"].append({
                    "kind": "CHILD_WITHOUT_MAIN", "source_ref": f"{file_name}!{sheet}!R{row_no}",
                    "detail": f"子行 {resp_v} 前无主行",
                })
                continue
            is_main = False
        else:
            st["anomaly"] += 1
            sink["anomalies"].append({
                "kind": "UNCLASSIFIED_ROW", "source_ref": f"{file_name}!{sheet}!R{row_no}",
                "detail": "name=%r resp=%r formula=%r" % (
                    (name_v or "")[:20], (resp_v or "")[:20], (formula_v or "")[:20]),
            })
            continue

        # ---- 指标级字段（子行继承主行）----
        if is_main:
            source_ref = f"{file_name}!{sheet}!R{row_no}"
            metric_code, match_method = matcher.match(name_v, source_ref)
            color_cell = row[name_c - 1] if name_c and name_c <= len(row) else row[0]
            main_info = {
                "excel_metric_name": name_v,
                "alias_code": extract_alias_code(name_v),
                "metric_code": metric_code,
                "match_method": match_method,
                "source_ref": source_ref,
                "row_no": row_no,
                "metric_attr": gv(attr_c),
                "unit": gv(unit_c),
                "std_formula_desc": formula_v,
                "direction": gv(dir_c),
                "metric_category": gv(cat_c),
                "font_color": font_color_of(color_cell),
            }
            last_main = main_info
            st["main"] += 1
        else:
            main_info = dict(last_main)
            main_info["row_no"] = row_no
            st["child"] += 1

        dm = dict_by_code.get(metric_code) if metric_code else None
        resp_dept = resp_v or ("未指定" if is_main else resp_v)
        scope_key = f"{metric_code}|{resp_dept}" if metric_code else f"UNMATCHED:{main_info['source_ref']}|{resp_dept}"
        if scope_key in seen_scope_keys:
            sink["dup_scopes"].append({
                "scope_key": scope_key,
                "kept_ref": sink["scope_refs"].get(scope_key, ""),
                "dup_ref": f"{file_name}!{sheet}!R{row_no}",
            })
            continue
        seen_scope_keys.add(scope_key)

        caliber_v = gv(caliber_c)
        remark_v = gv(remark_c)
        em_raw_v = gv(em_raw_c)
        skip_flag, skip_reason = detect_skip(caliber_v, remark_v, em_raw_v)
        if dm:
            fallback_mode = dm.get("extract_mode") or "MANUAL"
        else:
            fallback_mode = "MANUAL"
        extract_mode = map_extract_mode_from_raw(em_raw_v, fallback_mode)

        dept_range_v = gv(range_c)
        his_depts = split_dept_range(dept_range_v)
        if not his_depts and source_type not in ("MEDICAL_DEPT", "CH3_FUNC"):
            li = liaison_pair.get((seq_num, resp_dept)) or liaison_first.get(seq_num)
            if li and li.get("dept_range"):
                his_depts = split_dept_range(li["dept_range"])
        if source_type in ("MEDICAL_DEPT", "CH3_FUNC"):
            liaison_name, func_contact = None, None
        else:
            li = liaison_pair.get((seq_num, resp_dept)) or liaison_first.get(seq_num)
            liaison_name = li.get("liaison_name") if li else None
            func_contact = li.get("func_contact_name") if li else None

        scope = {
            "scope_key": scope_key,
            "metric_code": metric_code,
            "match_method": main_info["match_method"],
            "excel_metric_name": main_info["excel_metric_name"],
            "alias_code": main_info["alias_code"],
            "specialty_name": specialty_name,
            "source_type": source_type,
            "source_ref": f"{file_name}!{sheet}!R{row_no}",
            "resp_dept_name": resp_dept,
            "his_dept_names": his_depts,
            "extract_mode_raw": em_raw_v,
            "extract_mode": extract_mode,
            "metric_attr": main_info["metric_attr"] or (dm.get("metric_attr") if dm else None),
            "unit": main_info["unit"] or (dm.get("unit") if dm else None),
            "std_formula_desc": main_info["std_formula_desc"],
            "direction": main_info["direction"],
            "metric_category": main_info["metric_category"],
            "caliber_desc": caliber_v,
            "sys_source": gv(sys_c),
            "confirm_person": gv(confirm_c),
            "remark": remark_v,
            "skip_flag": skip_flag,
            "skip_reason": skip_reason,
            "font_color": main_info["font_color"],
            "liaison_name": liaison_name,
            "func_contact_name": func_contact,
        }
        sink["scopes"].append(scope)
        sink["scope_refs"][scope_key] = scope["source_ref"]
        st["scope"] += 1
        if skip_flag:
            st["skip"] += 1

        # ---- 本行 SQL 列逐格分类（04 §7.3/§7.5~7.7）----
        for col in sorted(raw_sql_cells):
            raw = raw_sql_cells[col]
            header = sql_cols[col]
            cell_ref = f"{file_name}!{sheet}!R{row_no}C{col}"
            kind = classify_cell(raw)
            if kind != "SQL":
                st["non_sql"][kind] += 1
                sink["non_sql_cells"].append({
                    "source_ref": cell_ref,
                    "header": header,
                    "kind": kind,
                    "preview": str(raw)[:60],
                })
                continue
            annotated = str(raw)
            executable = strip_sql_comments(annotated)
            if "分子" in header and "分母" in header:
                sink["anomalies"].append({
                    "kind": "ROLE_AMBIGUOUS", "source_ref": cell_ref,
                    "detail": f"表头 {header!r} 同时含分子与分母",
                })
                continue
            role_base = "NUM" if "分子" in header else "DEN"
            shape, guess = detect_result_shape(executable)
            if guess:
                sink["shape_guess"].append({"source_ref": cell_ref, "header": header})
            year_from, year_to, period_flag = final_year_range(header, executable)
            if year_from > year_to:
                sink["anomalies"].append({
                    "kind": "YEAR_RANGE_INVALID", "source_ref": cell_ref,
                    "detail": f"表头 {header!r} 推出 {year_from}>{year_to}，不入库",
                })
                continue
            ds_key, ds_flag, emr_sys = route_datasource(executable)
            sql_key = f"{scope_key}|{role_base}|{year_from}-{year_to}"
            n = sink["sql_key_count"].get(sql_key, 0) + 1
            sink["sql_key_count"][sql_key] = n
            if n > 1:
                sql_key = f"{sql_key}|{n}"
                sink["multi_sql"].append({
                    "sql_key": sql_key, "source_ref": cell_ref, "header": header,
                })
            sql_role = f"{role_base}_{shape}"
            sink["sqls"].append({
                "sql_key": sql_key,
                "scope_key": scope_key,
                "sql_role": sql_role,
                "datasource_key": ds_key,
                "ds_flag": ds_flag,
                "year_from": year_from,
                "year_to": year_to,
                "period_flag": period_flag,
                "emr_sys": emr_sys,
                "header": header,
                "source_ref": cell_ref,
                "sql_annotated": annotated,
                "sql_executable": executable,
                "output_columns": extract_output_columns(executable),
                "formal_file_match": None,
            })
            st["sql"] += 1


def describe_first_rows(path, n=5):
    """步骤 5：打印并返回单病种文件前 n 行文本描述。"""
    import openpyxl

    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    ws = wb.worksheets[0]
    lines = []
    for i, row in enumerate(ws.iter_rows(min_row=1, max_row=n, max_col=min(ws.max_column or 10, 20), values_only=True), start=1):
        cells = ["" if v is None else str(v).replace("\n", "\\n") for v in row]
        lines.append(f"R{i}: " + " | ".join(cells))
        print(lines[-1])
    wb.close()
    return lines


def main():
    import openpyxl

    pair_map, spec_first = load_liaison()
    metrics, alias_idx, name_idx, dict_by_code = load_metric_dict()

    if os.path.isfile(MANUAL_MAPPING_PATH):
        with open(MANUAL_MAPPING_PATH, encoding="utf-8") as f:
            manual_map = {k: v for k, v in json.load(f).items() if v}
        manual_exists = True
    else:
        manual_map = {}
        manual_exists = False

    matcher = Matcher(alias_idx, name_idx, manual_map)
    sink = {
        "scopes": [], "sqls": [], "non_sql_cells": [], "anomalies": [],
        "dup_scopes": [], "multi_sql": [], "shape_guess": [],
        "sql_key_count": {}, "scope_refs": {}, "seen_scope_keys": set(),
    }
    stats = []
    specialties = []
    skipped_ch3 = []

    # ---- ROOT_12：文件名含“年版”的 .xlsx（不含子目录），第 1 个 Sheet ----
    root_files = sorted(glob.glob(os.path.join(BASE, "*年版*.xlsx")))
    root_numerals = {}
    for path in root_files:
        base = os.path.basename(path)
        num = cn_num(base)
        if num:
            root_numerals[num] = base

    for path in root_files:
        base = os.path.basename(path)
        num = cn_num(base)
        wb = openpyxl.load_workbook(path, read_only=False, data_only=True)
        ws = wb.worksheets[0]
        title = cell_str(ws.cell(row=1, column=1).value) or base[:-5]
        print(f"[ROOT_12] {base} sheet={ws.title!r}", flush=True)
        specialties.append({"specialty_name": title, "source_file": base, "source_type": "ROOT_12"})
        parse_sheet(wb, base, ws.title, "ROOT_12", title, num,
                    matcher, dict_by_code, pair_map, spec_first, sink, stats)
        wb.close()

    # ---- 第三章总表：按 Sheet 名规则选择 ----
    wb = openpyxl.load_workbook(CH3_PATH, read_only=False, data_only=True)
    for ws in wb.worksheets:
        name = ws.title
        if name.startswith("职能指标"):
            source_type = "CH3_FUNC"
        elif name == "HIS统计肿瘤 勿动":
            continue
        else:
            num = cn_num(name)
            if num and num in root_numerals:
                skipped_ch3.append({"sheet": name, "reason": f"ROOT_12 文件 {root_numerals[num]} 以同一序号开头"})
                continue
            if not num:
                skipped_ch3.append({"sheet": name, "reason": "无中文序号且非职能指标"})
                continue
            source_type = "CH3_SHEET"
        print(f"[{source_type}] {name}", flush=True)
        specialties.append({"specialty_name": name, "source_file": os.path.basename(CH3_PATH), "source_type": source_type})
        seq = cn_num(name)
        parse_sheet(wb, os.path.basename(CH3_PATH), name, source_type, name, seq,
                    matcher, dict_by_code, pair_map, spec_first, sink, stats)
    wb.close()

    # ---- 医疗部 ----
    wb = openpyxl.load_workbook(MEDICAL_PATH, read_only=False, data_only=True)
    ws = wb.worksheets[0]
    title = cell_str(ws.cell(row=1, column=1).value) or os.path.basename(MEDICAL_PATH)[:-5]
    print(f"[MEDICAL_DEPT] {ws.title!r}", flush=True)
    specialties.append({"specialty_name": title, "source_file": os.path.basename(MEDICAL_PATH), "source_type": "MEDICAL_DEPT"})
    parse_sheet(wb, os.path.basename(MEDICAL_PATH), ws.title, "MEDICAL_DEPT", title, None,
                matcher, dict_by_code, pair_map, spec_first, sink, stats)
    wb.close()

    # ---- 单病种：先看结构再决定是否解析（步骤 5）----
    print("=== 单病种文件前 5 行 ===", flush=True)
    first_rows = describe_first_rows(SINGLE_PATH, 5)
    wb = openpyxl.load_workbook(SINGLE_PATH, read_only=True, data_only=True)
    ws = wb.worksheets[0]
    header_cells = {}
    for row in ws.iter_rows(min_row=2, max_row=2, max_col=min(ws.max_column or 10, 40)):
        for cell in row:
            v = cell_str(cell.value)
            if v:
                header_cells[cell.column] = v
    wb.close()
    mapping_probe, _, resp_found_probe = map_headers(header_cells, 40)
    single_ok = ("metric_name" in mapping_probe) and resp_found_probe
    if single_ok:
        wb = openpyxl.load_workbook(SINGLE_PATH, read_only=False, data_only=True)
        ws = wb.worksheets[0]
        title = cell_str(ws.cell(row=1, column=1).value) or os.path.basename(SINGLE_PATH)[:-5]
        specialties.append({"specialty_name": title, "source_file": os.path.basename(SINGLE_PATH), "source_type": "SINGLE_DISEASE"})
        parse_sheet(wb, os.path.basename(SINGLE_PATH), ws.title, "SINGLE_DISEASE", title, None,
                    matcher, dict_by_code, pair_map, spec_first, sink, stats)
        wb.close()
        single_conclusion = "单病种文件表头与 04 §7.2 一致，已按 SINGLE_DISEASE 解析"
    else:
        single_conclusion = "单病种文件结构不同（第 1 行即数据表头：病种名称/人次数/平均住院日等，无 §7.2 标准字段），待人工确认，未生成实例"

    # ---- 步骤 3：manual_mapping.json ----
    if not manual_exists:
        with open(MANUAL_MAPPING_PATH, "w", encoding="utf-8") as f:
            json.dump({u["source_ref"]: None for u in matcher.unmatched}, f, ensure_ascii=False, indent=1)
        manual_note = "文件不存在：已把全部 unmatched 写为 null 骨架生成"
    else:
        manual_note = f"文件已存在：读取 {len(manual_map)} 条非 null 映射使用，未覆盖"

    # ---- 汇总 counts ----
    scopes = sink["scopes"]
    match_counter = Counter(s["match_method"] for s in scopes)
    skip_count = sum(1 for s in scopes if s["skip_flag"])
    role_counter = Counter(s["sql_role"] for s in sink["sqls"])
    ds_counter = Counter(s["datasource_key"] for s in sink["sqls"])
    emr_counter = Counter(s["emr_sys"] for s in sink["sqls"])
    ds_conflict = sum(1 for s in sink["sqls"] if s["ds_flag"] == "DS_CONFLICT")
    ds_unknown = sum(1 for s in sink["sqls"] if s["ds_flag"] == "DS_UNKNOWN")
    period_conflict = sum(1 for s in sink["sqls"] if s["period_flag"] == "PERIOD_CONFLICT")

    scope_doc = {
        "meta": {
            "generated_at": datetime.now().replace(microsecond=0).isoformat(),
            "generator": "00b_parse_specialty.py",
            "source_files": sorted({sp["source_file"] for sp in specialties}),
            "counts": {
                "scopes": len(scopes),
                "matched": match_counter.get("CODE", 0) + match_counter.get("NAME", 0) + match_counter.get("MANUAL", 0),
                "manual_needed": match_counter.get("MANUAL_NEEDED", 0),
                "skip": skip_count,
            },
            "match_method_distribution": dict(match_counter),
        },
        "specialties": specialties,
        "scopes": scopes,
        "unmatched": matcher.unmatched,
        "duplicates": sink["dup_scopes"],
    }
    sql_doc = {
        "meta": {
            "generated_at": datetime.now().replace(microsecond=0).isoformat(),
            "generator": "00b_parse_specialty.py",
            "source_files": sorted({sp["source_file"] for sp in specialties}),
            "counts": {
                "sql": len(sink["sqls"]),
                "NUM_DTL": role_counter.get("NUM_DTL", 0),
                "DEN_DTL": role_counter.get("DEN_DTL", 0),
                "NUM_SUM": role_counter.get("NUM_SUM", 0),
                "DEN_SUM": role_counter.get("DEN_SUM", 0),
                "ds_conflict": ds_conflict,
                "period_conflict": period_conflict,
            },
            "ds_unknown": ds_unknown,
            "non_sql_cells": len(sink["non_sql_cells"]),
        },
        "sqls": sink["sqls"],
        "non_sql_cells": sink["non_sql_cells"],
    }
    write_json(SCOPE_JSON, scope_doc)
    write_json(SQL_JSON, sql_doc)
    write_report(REPORT_MD, stats, match_counter, matcher.unmatched, sink,
                 role_counter, ds_counter, emr_counter, ds_conflict, ds_unknown,
                 period_conflict, skipped_ch3, single_conclusion, single_ok,
                 first_rows, manual_note, len(metrics))

    print(f"manual_mapping: {manual_note}")
    print(f"scopes={len(scopes)} matched={scope_doc['meta']['counts']['matched']} "
          f"manual_needed={scope_doc['meta']['counts']['manual_needed']} skip={skip_count}")
    print(f"sqls={len(sink['sqls'])} non_sql={len(sink['non_sql_cells'])} "
          f"ds_conflict={ds_conflict} ds_unknown={ds_unknown} period_conflict={period_conflict}")
    print(f"CH3 skipped sheets: {len(skipped_ch3)}; single_disease_parsed={single_ok}")


def write_report(path, stats, match_counter, unmatched, sink, role_counter,
                 ds_counter, emr_counter, ds_conflict, ds_unknown, period_conflict,
                 skipped_ch3, single_conclusion, single_ok, first_rows,
                 manual_note, dict_metric_count):
    """步骤 6：生成解析报告（5 张必需表格 + 单病种结论）。"""
    L = []
    ap = L.append
    ap("# TASK-02 专科表解析报告")
    ap("")
    ap(f"- 生成时间：{datetime.now().replace(microsecond=0).isoformat()}")
    ap("- 生成脚本：`migration/00b_parse_specialty.py`（源 Excel 全部只读打开）")
    ap(f"- 指标字典：`migration/dist/metric_dict.json`（{dict_metric_count} 条指标参与匹配）")
    ap(f"- manual_mapping.json：{manual_note}")
    ap(f"- 单病种结论：{single_conclusion}")
    ap("")
    if not single_ok:
        ap("单病种文件前 5 行原文：")
        ap("")
        ap("```text")
        for ln in first_rows:
            ap(ln)
        ap("```")
        ap("")

    # 1. 按来源文件/Sheet 统计
    ap("## 1. 按来源文件 / Sheet 统计")
    ap("")
    ap("| 文件 | Sheet | 来源 | 表头映射（字段→列号；未映射=–） | 兜底G列 | 分组行 | 主行 | 子行 | 空行 | 异常行 | 实例 | 免统 | SQL | COMMENT | NUMBER | DIRTY | TEXT |")
    ap("|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for st in stats:
        mp = st["mapping"]
        mp_s = " ".join(f"{k.split('_')[0]}={v}" for k, v in mp.items() if v is not None)
        ns = st["non_sql"]
        ap(f"| {st['file'][:28]} | {st['sheet'][:24]} | {st['source_type']} | {mp_s} |"
           f" {'是' if st['resp_fallback'] else '否'} | {st['group']} | {st['main']} | {st['child']} |"
           f" {st['empty']} | {st['anomaly']} | {st['scope']} | {st['skip']} | {st['sql']} |"
           f" {ns.get('COMMENT_ONLY', 0)} | {ns.get('NUMBER', 0)} | {ns.get('DIRTY', 0)} | {ns.get('TEXT', 0)} |")
    ap("")

    # 2. 指标匹配统计
    ap("## 2. 指标匹配统计")
    ap("")
    ap("| match_method | 数量 |")
    ap("|---|---:|")
    for k in ("CODE", "NAME", "MANUAL", "MANUAL_NEEDED"):
        ap(f"| {k} | {match_counter.get(k, 0)} |")
    ap("")
    ap(f"### unmatched 明细（共 {len(unmatched)} 条）")
    ap("")
    if unmatched:
        ap("| source_ref | excel_metric_name | 候选 |")
        ap("|---|---|---|")
        for u in unmatched:
            ap(f"| {u['source_ref']} | {u['excel_metric_name']} | {'、'.join(u['candidates']) or '—'} |")
    else:
        ap("无")
    ap("")

    # 3. SQL 统计
    ap("## 3. SQL 统计")
    ap("")
    ap("| sql_role | 数量 |")
    ap("|---|---:|")
    for k in ("NUM_DTL", "DEN_DTL", "NUM_SUM", "DEN_SUM"):
        ap(f"| {k} | {role_counter.get(k, 0)} |")
    ap("")
    ap("| datasource_key | 数量 |")
    ap("|---|---:|")
    for k in sorted(ds_counter):
        ap(f"| {k} | {ds_counter[k]} |")
    ap("")
    ap("| emr_sys | 数量 |")
    ap("|---|---:|")
    for k in sorted(emr_counter):
        ap(f"| {k} | {emr_counter[k]} |")
    ap("")
    ap(f"DS_CONFLICT {ds_conflict} 条 / DS_UNKNOWN {ds_unknown} 条 / PERIOD_CONFLICT {period_conflict} 条 / SHAPE_GUESS {len(sink['shape_guess'])} 条")
    ap("")
    for tag, key in (("DS_CONFLICT", "ds_conflict"), ("DS_UNKNOWN", "ds_unknown"),
                     ("PERIOD_CONFLICT", "period_conflict")):
        rows = [s for s in sink["sqls"]
                if (s.get("ds_flag") == "DS_CONFLICT" and tag == "DS_CONFLICT")
                or (s.get("ds_flag") == "DS_UNKNOWN" and tag == "DS_UNKNOWN")
                or (s.get("period_flag") == "PERIOD_CONFLICT" and tag == "PERIOD_CONFLICT")]
        ap(f"### {tag} 明细（{len(rows)}）")
        ap("")
        if rows:
            ap("| source_ref | header |")
            ap("|---|---|")
            for s in rows:
                ap(f"| {s['source_ref']} | {s['header']} |")
        else:
            ap("无")
        ap("")
    ap(f"### SHAPE_GUESS 明细（{len(sink['shape_guess'])}）")
    ap("")
    if sink["shape_guess"]:
        ap("| source_ref | header |")
        ap("|---|---|")
        for g in sink["shape_guess"]:
            ap(f"| {g['source_ref']} | {g['header']} |")
    else:
        ap("无")
    ap("")

    # 4. 一标多科统计
    ap("## 4. 一标多科统计（按专业）")
    ap("")
    per_spec = defaultdict(lambda: {"dept": set(), "scope": 0})
    for s in sink["scopes"]:
        per_spec[(s["specialty_name"], s["source_type"])]["dept"].add(s["resp_dept_name"])
        per_spec[(s["specialty_name"], s["source_type"])]["scope"] += 1
    ap("| 专业 | 来源 | 科室数 | 实例数 |")
    ap("|---|---|---:|---:|")
    for (spec, stype), v in sorted(per_spec.items()):
        ap(f"| {spec[:36]} | {stype} | {len(v['dept'])} | {v['scope']} |")
    ap("")

    # 5. 异常行 / 重复实例 / 同期多 SQL
    ap("## 5. 异常与重复明细")
    ap("")
    ap(f"### 异常行（{len(sink['anomalies'])}）")
    ap("")
    if sink["anomalies"]:
        ap("| 类型 | source_ref | 说明 |")
        ap("|---|---|---|")
        for a in sink["anomalies"]:
            ap(f"| {a['kind']} | {a['source_ref']} | {a['detail']} |")
    else:
        ap("无")
    ap("")
    ap(f"### 重复实例（{len(sink['dup_scopes'])}）")
    ap("")
    if sink["dup_scopes"]:
        ap("| scope_key | 重复出现位置 |")
        ap("|---|---|")
        for d in sink["dup_scopes"]:
            ap(f"| {d['scope_key']} | {d['dup_ref']} |")
    else:
        ap("无")
    ap("")
    ap(f"### 同期多 SQL（{len(sink['multi_sql'])}）")
    ap("")
    if sink["multi_sql"]:
        ap("| sql_key | source_ref | header |")
        ap("|---|---|---|")
        for m in sink["multi_sql"]:
            ap(f"| {m['sql_key']} | {m['source_ref']} | {m['header']} |")
    else:
        ap("无")
    ap("")
    ap(f"### 第三章被跳过的 Sheet（{len(skipped_ch3)}）")
    ap("")
    if skipped_ch3:
        ap("| Sheet | 原因 |")
        ap("|---|---|")
        for s in skipped_ch3:
            ap(f"| {s['sheet']} | {s['reason']} |")
    else:
        ap("无")
    ap("")

    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))


if __name__ == "__main__":
    main()
