# -*- coding: utf-8 -*-
"""TASK-03：正式 SQL 目录交叉比对、历史基线提取与《预检台账》。

产出：
- migration/dist/sqls.json            仅回填 formal_file_match 与 meta.counts.formal_*（其余字段不动）
- migration/dist/baseline_values.json 总表历史值 + 专科表年度分子/分母
- docs/reports/存量指标与SQL清洗预检台账.md / .xlsx

规则：04 §6.4/§6.5/§7.3/§7.4；正式 SQL 目录只做比对、绝不覆盖 Excel（TASK-03 禁止事项）。
不读取 统计完成文件 下 明细/汇总结果 目录的任何 .csv（禁止事项）。
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from migration.common.excel_utils import (  # noqa: E402
    classify_cell,
    detect_result_shape,
    extract_alias_code,
    strip_sql_comments,
    write_json,
)

BASE = r"E:\三甲复审\1、三甲复审统计指标"
FORMAL_ROOT = Path(BASE) / "统计完成文件"
MEDICAL_PATH = os.path.join(BASE, "医疗部数据提取.xlsx")
TASK02_REPORT = os.path.join(ROOT, "docs", "reports", "TASK02_专科表解析报告.md")

DIST_DIR = os.path.join(ROOT, "migration", "dist")
SQLS_JSON = os.path.join(DIST_DIR, "sqls.json")
SCOPES_JSON = os.path.join(DIST_DIR, "dept_scopes.json")
MASTER_JSON = os.path.join(DIST_DIR, "master_rows.json")
BASELINE_JSON = os.path.join(DIST_DIR, "baseline_values.json")
REPORT_MD = os.path.join(ROOT, "docs", "reports", "存量指标与SQL清洗预检台账.md")
REPORT_XLSX = os.path.join(ROOT, "docs", "reports", "存量指标与SQL清洗预检台账.xlsx")

CN_NUM_RE = re.compile(r"^([一二三四五六七八九十百零]+)")
COORD_RE = re.compile(r"__([^_]+)__R(\d+)C(\d+)\.sql$")
YEAR_COL_RE = re.compile(r"^(19|20)\d{2}(分子|分母)$")
STAT_YEAR_RE = re.compile(r"^(19|20)\d{2}年统计数据$")
NUM_PART_RE = re.compile(r"分子[:：]\s*(\d+(?:\.\d+)?)")
DEN_PART_RE = re.compile(r"分母[:：]\s*(\d+(?:\.\d+)?)")
MULTI_KEY_RE = re.compile(r"\|(\d+)$")

YEAR_KEYS = ("2022", "2023", "2024", "2025")


def cell_text(v):
    if v is None:
        return None
    s = str(v).strip()
    return s if s else None


def cn_num(text):
    if not text:
        return None
    m = CN_NUM_RE.match(str(text).strip())
    return m.group(1) if m else None


def norm_sql(text):
    """比对用规范化文本：去注释后所有空白压成单个空格并转大写（TASK-03 步骤 1）。"""
    s = strip_sql_comments(str(text or ""))
    return re.sub(r"\s+", " ", s).strip().upper()


def read_sql_file(path):
    """utf-8-sig 优先、失败再 gbk；都失败返回 None（记入无法解码清单）。"""
    raw = Path(path).read_bytes()
    for enc in ("utf-8-sig", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return None


def scan_formal_files():
    """步骤 1：递归扫描路径中包含 \\正式SQL\\ 的 .sql 文件。"""
    files = []
    for dirpath, _dirnames, filenames in os.walk(FORMAL_ROOT):
        parts = Path(dirpath).parts
        if "正式SQL" not in parts:
            continue
        rel_dir = Path(dirpath).relative_to(FORMAL_ROOT).as_posix()
        for fn in filenames:
            if not fn.lower().endswith(".sql"):
                continue
            files.append({
                "path": str(Path(dirpath) / fn),
                "rel": f"{rel_dir}/{fn}" if rel_dir else fn,
                "name": fn,
            })
    return files


def parse_coord(name):
    """从坐标型文件名解析 (中文序号, Sheet名, 行, 列)，非坐标型返回 None。"""
    m = COORD_RE.search(name)
    if not m:
        return None
    sheet, row, col = m.group(1), int(m.group(2)), int(m.group(3))
    head = name[: m.start()]
    return (cn_num(head), sheet, row, col)


def ref_parts(source_ref):
    """source_ref = 文件名!Sheet!R行 或 文件名!Sheet!R行C列 → (文件名, Sheet, 行, 列或None)。"""
    file_part, rest = source_ref.split("!", 1)
    sheet_part, rc = rest.split("!", 1)
    m = re.match(r"R(\d+)(?:C(\d+))?$", rc)
    if m:
        row = int(m.group(1))
        col = int(m.group(2)) if m.group(2) else None
    else:
        row, col = None, None
    return (file_part, sheet_part, row, col)


def match_formal_files(sql_records, scopes_by_key, formal_files):
    """步骤 2：坐标匹配 + 内容匹配；返回 (未匹配文件按专业目录分组, 无法解码清单)。"""
    coord_pool = defaultdict(list)
    norm_index = defaultdict(list)      # norm → [file]（未被坐标占用的内容匹配池）
    alias_pool = defaultdict(list)      # (alias, NUM/DEN) → [(文件内科室, file)]
    undecodable = []
    for f in formal_files:
        text = read_sql_file(f["path"])
        if text is None:
            undecodable.append(f["rel"])
            continue
        f["norm"] = norm_sql(text)
        coord = parse_coord(f["name"])
        # 所有可解码文件都进内容匹配池（契约：内容匹配在"未被坐标匹配占用"的文件中找）；
        # 专科代号+科室 候选索引只对内容型文件名建立
        norm_index[f["norm"]].append(f)
        if coord:
            coord_pool[coord].append(f)
        else:
            alias = extract_alias_code(f["name"])
            if alias:
                role = None
                dept = None
                for p in f["name"][: f["name"].rfind(".")].split("_")[1:]:
                    if "分子" in p and role is None:
                        role = "NUM"
                    elif "分母" in p and role is None:
                        role = "DEN"
                    elif p and not p.isdigit() and dept is None:
                        dept = p
                if role:
                    alias_pool[(alias, role)].append((dept, f))
    used = set()

    for rec in sql_records:
        file_part, sheet_part, row, col = ref_parts(rec["source_ref"])
        rec_norm = norm_sql(rec["sql_executable"])
        match = {"status": "NOT_FOUND", "file": None}
        pick = None
        # ---- 坐标匹配（中文序号 + Sheet + R行C列；Sheet 名中空格在文件名中序列化为 _）----
        if row is not None:
            cands = coord_pool.get((cn_num(file_part), sheet_part.replace(" ", "_"), row, col), [])
            if cands:
                same = [f for f in cands if f["norm"] == rec_norm]
                if same:
                    pick = sorted(same, key=lambda f: f["rel"])[0]
                    match = {"status": "SAME", "file": pick["rel"]}
                else:
                    pick = sorted(cands, key=lambda f: f["rel"])[0]
                    match = {"status": "DIFF", "file": pick["rel"]}
        # ---- 内容匹配一：norm 完全相同 ----
        if pick is None:
            same = [f for f in norm_index.get(rec_norm, []) if f["rel"] not in used]
            if same:
                pick = sorted(same, key=lambda f: f["rel"])[0]
                match = {"status": "SAME", "file": pick["rel"]}
        # ---- 内容匹配二：专科代号 + 分子/分母 + 科室 候选（norm 不同 → DIFF）----
        if pick is None:
            scope = scopes_by_key.get(rec["scope_key"]) or {}
            alias = scope.get("alias_code")
            role = "NUM" if "分子" in rec.get("header", "") else ("DEN" if "分母" in rec.get("header", "") else None)
            dept = scope.get("resp_dept_name")
            cands = []
            if alias and role:
                for fdept, f in alias_pool.get((alias, role), []):
                    if f["rel"] in used:
                        continue
                    if not fdept or (dept and (fdept in dept or dept in fdept)):
                        cands.append(f)
            if cands:
                pick = sorted(cands, key=lambda f: f["rel"])[0]
                match = {"status": "DIFF", "file": pick["rel"]}
        if pick is not None:
            used.add(pick["rel"])
        rec["formal_file_match"] = match

    unmatched_files = [f for f in formal_files if f["rel"] not in used and f.get("norm") is not None]
    by_dir = Counter(f["rel"].split("/")[0] for f in unmatched_files)
    return by_dir, undecodable


def build_master_values(master_rows):
    """步骤 3.1：总表 2022-2025 历史值；num_value 去 % 后能转数字则转，否则 null。"""
    out = []
    for r in master_rows:
        for yk in YEAR_KEYS:
            v = r["values"].get(yk) or {}
            data = v.get("data")
            if not data:
                continue
            cleaned = str(data).replace("%", "").strip()
            try:
                num_value = float(cleaned)
            except ValueError:
                num_value = None
            out.append({
                "row_no": r["row_no"],
                "code": r["code"],
                "row_type": r["row_type"],
                "metric_code": r["metric_code"],
                "atom_role": r["atom_role"],
                "year": int(yk),
                "data": data,
                "num_value": num_value,
                "source": v.get("source"),
            })
    return out


def build_scope_values(scopes):
    """步骤 3.2/3.3：ROOT_12 表头恰为 20XX分子/分母 的 NUMBER 单元格；医疗部 20XX年统计数据。

    行 → scope_key 映射用 dept_scopes.json 的 source_ref 行号（与 TASK-02 行判定一致）。
    """
    import openpyxl

    ref_to_scope = {}
    for s in scopes:
        try:
            file_part, sheet_part, row, _c = ref_parts(s["source_ref"])
        except ValueError:
            continue
        if row is not None:
            ref_to_scope[(file_part, sheet_part, row)] = s

    merged = {}

    def put(scope_key, year, num, den, source_ref):
        e = merged.setdefault((scope_key, year), {"scope_key": scope_key, "year": year,
                                                  "num": None, "den": None, "source_ref": source_ref})
        if num is not None and e["num"] is None:
            e["num"] = num
        if den is not None and e["den"] is None:
            e["den"] = den
        if num is not None:
            e["source_ref"] = source_ref

    # ---- ROOT_12：表头恰为 20XX分子/20XX分母（不含 SQL、不含 -）且单元格为 NUMBER ----
    by_file = defaultdict(list)
    for s in scopes:
        if s["source_type"] == "ROOT_12":
            by_file[s["source_ref"].split("!")[0]].append(s)
    for file_name in sorted(by_file):
        path = os.path.join(BASE, file_name)
        if not os.path.isfile(path):
            continue
        wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
        ws = wb.worksheets[0]
        header_cells = {}
        for row in ws.iter_rows(min_row=2, max_row=2, max_col=min(ws.max_column or 30, 60)):
            for cell in row:
                v = cell_text(cell.value)
                if v:
                    header_cells[cell.column] = v
        year_cols = {}
        for col, h in header_cells.items():
            m = YEAR_COL_RE.match(h)
            if m:
                year_cols[col] = (int(h[:4]), m.group(2))
        if not year_cols:
            wb.close()
            continue
        for row_no, row in enumerate(ws.iter_rows(min_row=3, max_row=ws.max_row, max_col=max(year_cols)), start=3):
            scope = ref_to_scope.get((file_name, ws.title, row_no))
            if scope is None:
                continue
            for col, (year, role) in sorted(year_cols.items()):
                if col > len(row):
                    continue
                raw = row[col - 1].value
                if raw is None or classify_cell(raw) != "NUMBER":
                    continue
                val = float(raw) if not isinstance(raw, str) else float(str(raw).strip())
                cell_ref = f"{file_name}!{ws.title}!R{row_no}C{col}"
                put(scope["scope_key"], year,
                    val if role == "分子" else None,
                    val if role == "分母" else None,
                    cell_ref)
        wb.close()

    # ---- 医疗部：20XX年统计数据 列（文本"分子:..；分母:.."解析；纯数字当作分子）----
    med_rows = {}
    for s in scopes:
        if s["source_type"] == "MEDICAL_DEPT":
            try:
                _f, sheet_part, row, _c = ref_parts(s["source_ref"])
            except ValueError:
                continue
            if row is not None:
                med_rows[(sheet_part, row)] = s
    if med_rows and os.path.isfile(MEDICAL_PATH):
        wb = openpyxl.load_workbook(MEDICAL_PATH, read_only=True, data_only=True)
        ws = wb.worksheets[0]
        stat_cols = {}
        for row in ws.iter_rows(min_row=2, max_row=2, max_col=min(ws.max_column or 25, 40)):
            for cell in row:
                v = cell_text(cell.value)
                if v and STAT_YEAR_RE.match(v):
                    stat_cols[cell.column] = int(v[:4])
        if stat_cols:
            for row_no, row in enumerate(ws.iter_rows(min_row=3, max_row=ws.max_row, max_col=max(stat_cols)), start=3):
                scope = med_rows.get((ws.title, row_no))
                if scope is None:
                    continue
                for col, year in sorted(stat_cols.items()):
                    if col > len(row):
                        continue
                    raw = row[col - 1].value
                    if raw is None or not str(raw).strip():
                        continue
                    cell_ref = f"{os.path.basename(MEDICAL_PATH)}!{ws.title}!R{row_no}C{col}"
                    text = str(raw).strip()
                    if classify_cell(raw) == "NUMBER":
                        val = float(raw) if not isinstance(raw, str) else float(text)
                        put(scope["scope_key"], year, val, None, cell_ref)
                    else:
                        mn = NUM_PART_RE.search(text)
                        md = DEN_PART_RE.search(text)
                        num = float(mn.group(1)) if mn else None
                        den = float(md.group(1)) if md else None
                        if num is not None or den is not None:
                            put(scope["scope_key"], year, num, den, cell_ref)
        wb.close()

    return [merged[k] for k in sorted(merged, key=lambda k: (k[0], k[1]))]


def parse_task02_issue_tables():
    """从 TASK-02 报告 §5 解析异常行与重复实例两张表（同期多SQL由 sqls.json 重算）。"""
    try:
        text = open(TASK02_REPORT, encoding="utf-8").read()
    except OSError:
        return [], []
    anomalies, dups = [], []

    def table_rows(section_title):
        seg = text.split(section_title, 1)
        if len(seg) < 2:
            return []
        body = seg[1].split("###", 1)[0]
        rows = []
        for line in body.splitlines():
            line = line.strip()
            if not (line.startswith("|") and line.endswith("|")):
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if cells and cells[0] in ("类型", "scope_key", "sql_key", "source_ref", "---", ":---"):
                continue
            if set("".join(cells)) <= set("-: "):
                continue
            rows.append(cells)
        return rows

    m = re.search(r"### 异常行（\d+）", text)
    if m:
        for cells in table_rows(m.group(0)):
            if len(cells) >= 3:
                anomalies.append({"kind": cells[0], "source_ref": cells[1], "detail": cells[2]})
    m = re.search(r"### 重复实例（\d+）", text)
    if m:
        for cells in table_rows(m.group(0)):
            if len(cells) >= 2:
                dups.append({"scope_key": cells[0], "dup_ref": cells[1]})
    return anomalies, dups


def recompute_shape_guess(sql_records):
    """SHAPE_GUESS 重算：与 TASK-02 同一函数（detect_result_shape），猜测标记 = (DTL, True)。"""
    refs = []
    for r in sql_records:
        shape, guess = detect_result_shape(r["sql_executable"])
        if guess:
            refs.append(r["source_ref"])
    return set(refs)


def recompute_multi_sql(sql_records):
    """同期多 SQL 重算：sql_key 末尾带 |2 及以上后缀的记录。"""
    out = []
    for r in sql_records:
        m = MULTI_KEY_RE.search(r["sql_key"])
        if m and int(m.group(1)) >= 2:
            out.append({"sql_key": r["sql_key"], "source_ref": r["source_ref"], "header": r["header"]})
    return out


def collect_issue_lists(scope_doc, sql_doc, shape_refs, anomalies, dups, multi_sql):
    """步骤 4.2 的六类人工清单。"""
    scopes = scope_doc["scopes"]
    sql_records = sql_doc["sqls"]
    scopes_by_key = {s["scope_key"]: s for s in scopes}
    unmatched_by_ref = {u["source_ref"]: u for u in scope_doc["unmatched"]}

    def scope_of(rec):
        sc = scopes_by_key.get(rec["scope_key"]) or {}
        return sc.get("excel_metric_name", ""), sc.get("resp_dept_name", "")

    issues = {}
    issues["未匹配指标"] = [
        {"source_ref": s["source_ref"], "指标名": s["excel_metric_name"],
         "科室": s["resp_dept_name"],
         "问题说明": "候选：" + ("、".join(unmatched_by_ref[s["source_ref"]]["candidates"]) or "—")}
        for s in scopes if s["match_method"] == "MANUAL_NEEDED"]
    issues["DS标记"] = [
        {"source_ref": r["source_ref"], "指标名": scope_of(r)[0], "科室": scope_of(r)[1],
         "问题说明": f"{r['ds_flag']}（表头 {r['header']}）"}
        for r in sql_records if r["ds_flag"] in ("DS_CONFLICT", "DS_UNKNOWN")]
    issues["期间冲突"] = [
        {"source_ref": r["source_ref"], "指标名": scope_of(r)[0], "科室": scope_of(r)[1],
         "问题说明": f"表头 {r['header']} 推出 {r['year_from']}-{r['year_to']}，与 SQL 字面量不一致"}
        for r in sql_records if r["period_flag"] == "PERIOD_CONFLICT"]
    issues["形态猜测"] = [
        {"source_ref": r["source_ref"], "指标名": scope_of(r)[0], "科室": scope_of(r)[1],
         "问题说明": f"SHAPE_GUESS（表头 {r['header']}，默认按 DTL 处理）"}
        for r in sql_records if r["source_ref"] in shape_refs]
    issues["比对DIFF"] = [
        {"source_ref": r["source_ref"], "指标名": scope_of(r)[0], "科室": scope_of(r)[1],
         "问题说明": "与正式SQL文件内容不同：" + str(r["formal_file_match"]["file"])}
        for r in sql_records if r["formal_file_match"]["status"] == "DIFF"]
    rows = [{"source_ref": a.get("source_ref", ""), "指标名": "", "科室": "",
             "问题说明": f"异常行 {a.get('kind', '')}：{a.get('detail', '')}"} for a in anomalies]
    rows += [{"source_ref": d.get("dup_ref", ""), "指标名": "", "科室": "",
              "问题说明": f"重复实例，scope_key={d.get('scope_key', '')}（保留首个）"} for d in dups]
    rows += [{"source_ref": m2.get("source_ref", ""), "指标名": "", "科室": "",
              "问题说明": f"同期多SQL：{m2.get('sql_key', '')}（表头 {m2.get('header', '')}）"} for m2 in multi_sql]
    issues["异常与重复"] = rows
    return issues


ISSUE_ORDER = ("未匹配指标", "DS标记", "期间冲突", "形态猜测", "比对DIFF", "异常与重复")


def write_ledger_md(path, scope_doc, sql_doc, master_doc, baseline_doc,
                    status_counter, unmatched_by_dir, undecodable, issues):
    L = []
    ap = L.append
    scopes = scope_doc["scopes"]
    type_counter = Counter(s["source_type"] for s in scopes)
    role_counter = Counter(r["sql_role"] for r in sql_doc["sqls"])
    skip_count = sum(1 for s in scopes if s["skip_flag"])
    master_counts = master_doc["meta"]["counts"]

    ap("# 存量指标与SQL清洗预检台账")
    ap("")
    ap(f"- 生成时间：{datetime.now().replace(microsecond=0).isoformat()}")
    ap("- 生成脚本：`migration/00c_crosscheck_and_report.py`（TASK-01~02 产物 + 正式SQL目录只读比对，Excel 永远为准）")
    ap("")
    ap("## 1. 总览")
    ap("")
    ap("| 项 | 数量 |")
    ap("|---|---:|")
    ap(f"| 总表 CHAPTER / METRIC / ATOM | {master_counts.get('CHAPTER', 0)} / {master_counts.get('METRIC', 0)} / {master_counts.get('ATOM', 0)} |")
    for k in ("ROOT_12", "CH3_SHEET", "CH3_FUNC", "MEDICAL_DEPT", "SINGLE_DISEASE"):
        if type_counter.get(k):
            ap(f"| 科室实例 {k} | {type_counter[k]} |")
    ap(f"| 科室实例 合计 | {len(scopes)} |")
    for k in ("NUM_DTL", "DEN_DTL", "NUM_SUM", "DEN_SUM"):
        ap(f"| SQL {k} | {role_counter.get(k, 0)} |")
    ap(f"| SQL 合计 | {len(sql_doc['sqls'])} |")
    ap(f"| 免统实例 | {skip_count} |")
    ap(f"| 未匹配指标（MANUAL_NEEDED） | {scope_doc['meta']['counts'].get('manual_needed', 0)} |")
    ap(f"| 正式SQL目录文件 | {sql_doc['meta']['counts'].get('formal_dir_files', 0)} |")
    ap(f"| 比对 SAME / DIFF / NOT_FOUND | {status_counter.get('SAME', 0)} / {status_counter.get('DIFF', 0)} / {status_counter.get('NOT_FOUND', 0)} |")
    ap(f"| 目录未被匹配文件（疑似辅助/明细导出，不入库） | {sum(unmatched_by_dir.values())} |")
    ap(f"| 无法解码文件 | {len(undecodable)} |")
    ap("")

    ap("## 2. 需要人工处理的清单")
    ap("")
    for i, name in enumerate(ISSUE_ORDER, start=1):
        rows = issues.get(name, [])
        ap(f"### 2.{i} {name}（{len(rows)} 条）")
        ap("")
        if rows:
            ap("| source_ref | 指标名 | 科室 | 问题说明 |")
            ap("|---|---|---|---|")
            for r in rows:
                ap(f"| {r['source_ref']} | {str(r['指标名'])[:38]} | {r['科室']} | {str(r['问题说明'])[:90]} |")
        else:
            ap("无")
        ap("")

    ap("## 3. 各专业明细")
    ap("")
    scope_to_spec = {s["scope_key"]: f"{s['specialty_name']}（{s['source_type']}）" for s in scopes}
    per = {}
    for s in scopes:
        key = f"{s['specialty_name']}（{s['source_type']}）"
        e = per.setdefault(key, {"metrics": set(), "scope": 0, "sql": 0, "skip": 0,
                                 "SAME": 0, "DIFF": 0, "NOT_FOUND": 0})
        e["scope"] += 1
        if s["metric_code"]:
            e["metrics"].add(s["metric_code"])
        if s["skip_flag"]:
            e["skip"] += 1
    for r in sql_doc["sqls"]:
        key = scope_to_spec.get(r["scope_key"])
        if key and key in per:
            per[key]["sql"] += 1
            per[key][r["formal_file_match"]["status"]] += 1
    ap("| 专业（来源） | 指标数 | 实例数 | SQL数 | SAME | DIFF | NOT_FOUND | 免统 |")
    ap("|---|---:|---:|---:|---:|---:|---:|---:|")
    for key in sorted(per):
        e = per[key]
        ap(f"| {key[:44]} | {len(e['metrics'])} | {e['scope']} | {e['sql']} | "
           f"{e['SAME']} | {e['DIFF']} | {e['NOT_FOUND']} | {e['skip']} |")
    ap("")

    ap("## 4. 历史基线覆盖情况")
    ap("")
    mv_2025 = {m["metric_code"] for m in baseline_doc["master_values"]
               if m["year"] == 2025 and m["metric_code"]}
    sv = baseline_doc["scope_values"]
    ap(f"- 有 2025 年总表数据的指标数：{len(mv_2025)}")
    ap(f"- 总表历史值条目（2022-2025，data 非空）：{len(baseline_doc['master_values'])}")
    ap(f"- 有专科表年度数字的实例数：{len({v['scope_key'] for v in sv})}（合并条目 {len(sv)}）")
    ap("")
    ap("## 5. 附：正式SQL目录未被匹配文件分组（不入库，仅报告）")
    ap("")
    ap("| 专业目录 | 文件数 |")
    ap("|---|---:|")
    for k in sorted(unmatched_by_dir):
        ap(f"| {k} | {unmatched_by_dir[k]} |")
    if undecodable:
        ap("")
        ap("### 无法解码文件")
        ap("")
        for u in undecodable:
            ap(f"- {u}")
    ap("")
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))


def _fill_sheet(ws, rows, headers):
    ws.append(headers)
    for r in rows:
        ws.append([r.get(h, "") for h in headers])
    from openpyxl.styles import Font

    for cell in ws[1]:
        cell.font = Font(bold=True)
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions


def write_ledger_xlsx(path, scope_doc, sql_doc, master_doc, status_counter,
                      unmatched_by_dir, undecodable, issues):
    import openpyxl

    scopes = scope_doc["scopes"]
    wb = openpyxl.Workbook()

    master_counts = master_doc["meta"]["counts"]
    tcounter = Counter(s["source_type"] for s in scopes)
    rcounter = Counter(r["sql_role"] for r in sql_doc["sqls"])
    rows = []
    for k in ("CHAPTER", "METRIC", "ATOM", "TOTAL"):
        rows.append({"名称": f"总表 {k}", "数量": master_counts.get(k, 0)})
    for k in ("ROOT_12", "CH3_SHEET", "CH3_FUNC", "MEDICAL_DEPT", "SINGLE_DISEASE"):
        rows.append({"名称": f"科室实例 {k}", "数量": tcounter.get(k, 0)})
    rows.append({"名称": "科室实例 合计", "数量": len(scopes)})
    for k in ("NUM_DTL", "DEN_DTL", "NUM_SUM", "DEN_SUM"):
        rows.append({"名称": f"SQL {k}", "数量": rcounter.get(k, 0)})
    rows.append({"名称": "SQL 合计", "数量": len(sql_doc["sqls"])})
    rows.append({"名称": "免统实例", "数量": sum(1 for s in scopes if s["skip_flag"])})
    rows.append({"名称": "未匹配指标", "数量": scope_doc["meta"]["counts"].get("manual_needed", 0)})
    for k in ("SAME", "DIFF", "NOT_FOUND"):
        rows.append({"名称": f"比对 {k}", "数量": status_counter.get(k, 0)})
    rows.append({"名称": "正式SQL目录文件", "数量": sql_doc["meta"]["counts"].get("formal_dir_files", 0)})
    rows.append({"名称": "目录未被匹配文件", "数量": sql_doc["meta"]["counts"].get("formal_dir_unmatched", 0)})
    rows.append({"名称": "无法解码文件", "数量": len(undecodable)})
    ws = wb.active
    ws.title = "总览"
    _fill_sheet(ws, rows, ["名称", "数量"])

    ws = wb.create_sheet("科室实例")
    scope_rows = [{
        "scope_key": s["scope_key"], "metric_code": s["metric_code"], "match_method": s["match_method"],
        "excel_metric_name": s["excel_metric_name"], "specialty_name": s["specialty_name"],
        "source_type": s["source_type"], "source_ref": s["source_ref"],
        "resp_dept_name": s["resp_dept_name"], "his_dept_names": "、".join(s["his_dept_names"]),
        "extract_mode": s["extract_mode"], "skip_flag": s["skip_flag"], "skip_reason": s["skip_reason"],
        "font_color": s["font_color"], "liaison_name": s["liaison_name"],
    } for s in scopes]
    _fill_sheet(ws, scope_rows, list(scope_rows[0].keys()))

    ws = wb.create_sheet("SQL清单")
    sql_rows = [{
        "sql_key": r["sql_key"], "sql_role": r["sql_role"], "year_from": r["year_from"],
        "year_to": r["year_to"], "period_flag": r["period_flag"], "datasource_key": r["datasource_key"],
        "ds_flag": r["ds_flag"], "emr_sys": r["emr_sys"], "header": r["header"],
        "source_ref": r["source_ref"], "match_status": r["formal_file_match"]["status"],
        "formal_file": r["formal_file_match"]["file"],
    } for r in sql_doc["sqls"]]
    _fill_sheet(ws, sql_rows, list(sql_rows[0].keys()))

    for name in ISSUE_ORDER:
        ws = wb.create_sheet(name)
        rows = issues.get(name, [])
        headers = list(rows[0].keys()) if rows else ["source_ref", "指标名", "科室", "问题说明"]
        _fill_sheet(ws, rows, headers)

    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    wb.save(path)


def main():
    with open(SQLS_JSON, encoding="utf-8") as f:
        sql_doc = json.load(f)
    with open(SCOPES_JSON, encoding="utf-8") as f:
        scope_doc = json.load(f)
    with open(MASTER_JSON, encoding="utf-8") as f:
        master_doc = json.load(f)

    scopes = scope_doc["scopes"]
    scopes_by_key = {s["scope_key"]: s for s in scopes}
    sql_records = sql_doc["sqls"]

    # ---- 步骤 1+2：正式 SQL 目录只读比对 ----
    formal_files = scan_formal_files()
    unmatched_by_dir, undecodable = match_formal_files(sql_records, scopes_by_key, formal_files)
    status_counter = Counter(r["formal_file_match"]["status"] for r in sql_records)
    # 只允许改动 formal_file_match 与 meta.counts.formal_*（其余字段原样保留）
    sql_doc["meta"]["counts"]["formal_same"] = status_counter.get("SAME", 0)
    sql_doc["meta"]["counts"]["formal_diff"] = status_counter.get("DIFF", 0)
    sql_doc["meta"]["counts"]["formal_not_found"] = status_counter.get("NOT_FOUND", 0)
    sql_doc["meta"]["counts"]["formal_dir_files"] = len(formal_files)
    sql_doc["meta"]["counts"]["formal_dir_unmatched"] = sum(unmatched_by_dir.values())
    write_json(SQLS_JSON, sql_doc)

    # ---- 步骤 3：历史基线 ----
    master_values = build_master_values(master_doc["rows"])
    scope_values = build_scope_values(scopes)
    baseline_doc = {
        "meta": {
            "generated_at": datetime.now().replace(microsecond=0).isoformat(),
            "generator": "00c_crosscheck_and_report.py",
            "source_files": ["migration/dist/master_rows.json", "migration/dist/dept_scopes.json",
                             os.path.basename(MEDICAL_PATH)],
            "counts": {
                "master_values": len(master_values),
                "scope_values": len(scope_values),
            },
        },
        "master_values": master_values,
        "scope_values": scope_values,
    }
    write_json(BASELINE_JSON, baseline_doc)

    # ---- 步骤 4：预检台账 ----
    shape_refs = recompute_shape_guess(sql_records)
    multi_sql = recompute_multi_sql(sql_records)
    anomalies, dups = parse_task02_issue_tables()
    issues = collect_issue_lists(scope_doc, sql_doc, shape_refs, anomalies, dups, multi_sql)
    write_ledger_md(REPORT_MD, scope_doc, sql_doc, master_doc, baseline_doc,
                    status_counter, unmatched_by_dir, undecodable, issues)
    write_ledger_xlsx(REPORT_XLSX, scope_doc, sql_doc, master_doc, status_counter,
                      unmatched_by_dir, undecodable, issues)

    print(f"formal files={len(formal_files)} SAME={status_counter.get('SAME', 0)} "
          f"DIFF={status_counter.get('DIFF', 0)} NOT_FOUND={status_counter.get('NOT_FOUND', 0)}")
    print(f"dir unmatched files={sum(unmatched_by_dir.values())} undecodable={len(undecodable)}")
    print(f"baseline master_values={len(master_values)} scope_values={len(scope_values)}")
    print("issues: " + " ".join(f"{n}={len(issues.get(n, []))}" for n in ISSUE_ORDER))


if __name__ == "__main__":
    main()
