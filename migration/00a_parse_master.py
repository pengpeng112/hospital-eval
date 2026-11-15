# -*- coding: utf-8 -*-
"""TASK-01：总表三层结构解析。

读取《数据指标导出调整版.xlsx》（sheet1，4,718 数据行，三层父子结构：
章节 410 / 指标 1,485 / 基础指标 2,823），产出：
- migration/dist/master_rows.json   （每行原样结构，含 values 四年数据/来源）
- migration/dist/metric_dict.json   （1,485 条指标字典，对应 eval_metric_dict）
- docs/reports/TASK01_总表解析报告.md（解析台账）

解析规则：04 §6.1/§6.2/§7.1；对源 Excel 只读打开，不做任何写操作。
"""
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime

# 保证从任意工作目录运行时都能 import migration.common.*（项目根入 sys.path）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from migration.common.excel_utils import (  # noqa: E402
    extract_alias_code,
    map_extract_mode_from_source,
    normalize_code,
    write_json,
)

# 源文件路径（04 §7.1：总表固定路径，只读打开）
SRC = r"E:\三甲复审\1、三甲复审统计指标\数据指标导出调整版.xlsx"
SHEET_NAME = "sheet1"
DATA_START_ROW = 3          # 第 1、2 行是表头，数据从第 3 行开始
MAX_COL = 11                # A~K：编号/名称/类型 + 4×(数据,数据来源)
YEAR_KEYS = ("2022", "2023", "2024", "2025")

DIST_DIR = os.path.join(ROOT, "migration", "dist")
MASTER_JSON = os.path.join(DIST_DIR, "master_rows.json")
DICT_JSON = os.path.join(DIST_DIR, "metric_dict.json")
REPORT_MD = os.path.join(ROOT, "docs", "reports", "TASK01_总表解析报告.md")

# Excel"类型"列 → row_type
ROW_TYPE_MAP = {"章节": "CHAPTER", "指标": "METRIC", "基础指标": "ATOM"}
# 单年数据中的定性取值（04 §7.1：1 个 ATOM 且任一年数据是 是/否/有/无 → 定性指标）
QUALITATIVE_VALUES = ("是", "否", "有", "无")


def cell_text(v):
    """单元格 → 文本或 None；数字统一转字符串（84.0 保持 "84.0"），None/空白 → None。"""
    if v is None:
        return None
    if isinstance(v, str):
        s = v.strip()
        return s if s else None
    if isinstance(v, (int, float)):
        return str(v)
    return str(v).strip() or None


def main():
    if not os.path.isfile(SRC):
        print(f"ERROR: 源文件不存在: {SRC}")
        sys.exit(2)

    import openpyxl

    wb = openpyxl.load_workbook(SRC, read_only=True, data_only=True)
    if SHEET_NAME not in wb.sheetnames:
        print(f"ERROR: 源文件中不存在工作表 {SHEET_NAME!r}，实际 sheets={wb.sheetnames}")
        wb.close()
        sys.exit(2)
    ws = wb[SHEET_NAME]

    rows_out = []              # master_rows.json 的 rows
    metrics = []               # 指标记录（顺序 = Excel 行序）
    metric_by_code = {}
    atom_groups = defaultdict(list)   # metric 原始编号 → [{"code","name","row"}]
    chapter_stack = []         # 已出现的 CHAPTER 行（用于向上找父章节）
    last_metric = None         # 向上最近的 METRIC 记录

    type_counts = Counter()
    skipped_empty = 0
    anomalies = []             # 台账：异常行（类型未知 / 无父指标原子等）
    duplicate_counter = Counter()

    for row_no, row in enumerate(
        ws.iter_rows(min_row=DATA_START_ROW, max_col=MAX_COL, values_only=True),
        start=DATA_START_ROW,
    ):
        code_raw = row[0]
        code = "" if code_raw is None else str(code_raw).strip()
        if not code:
            skipped_empty += 1
            continue
        type_raw = "" if row[2] is None else str(row[2]).strip()
        row_type = ROW_TYPE_MAP.get(type_raw)
        if row_type is None:
            anomalies.append({"row_no": row_no, "kind": "UNKNOWN_TYPE", "detail": f"类型列值={type_raw!r}"})
            continue
        name = cell_text(row[1]) or ""

        norm = normalize_code(code)
        values = {}
        for i, yk in enumerate(YEAR_KEYS):
            values[yk] = {
                "data": cell_text(row[3 + 2 * i]),
                "source": cell_text(row[4 + 2 * i]),
            }

        row_rec = {
            "row_no": row_no,
            "code": code,
            "name": name,
            "row_type": row_type,
            "parent_code": None,
            "level": None,
            "metric_code": None,
            "atom_role": None,
            "values": values,
        }

        if row_type in ("CHAPTER", "METRIC"):
            # 父章节 = 向上最近的 CHAPTER 行，且其标准化编号是本行标准化编号的前缀 + "."（04 §7.1）
            parent = None
            for ch in reversed(chapter_stack):
                if norm.startswith(ch["norm"] + "."):
                    parent = ch
                    break
            row_rec["level"] = norm.count(".") + 1
            if parent is not None:
                row_rec["parent_code"] = parent["code"]
            else:
                # 顶层章节（如根节点 25.2"指标"、第一/二/三部分）无父章节属正常，
                # 但仍记入台账供人工核对
                anomalies.append({"row_no": row_no, "kind": "NO_PARENT_CHAPTER",
                                  "detail": f"{row_type} {code} {name}"})
            if row_type == "CHAPTER":
                chapter_stack.append({"norm": norm, "code": code, "name": name})
            else:
                row_rec["metric_code"] = code
                metric_rec = {
                    "code": code,
                    "norm": norm,
                    "name": name,
                    "row_no": row_no,
                    "level": row_rec["level"],
                    "parent_code": row_rec["parent_code"],
                    "values": values,
                    "ancestors": [ch for ch in chapter_stack if norm.startswith(ch["norm"] + ".")],
                    "atoms": [],
                    "num_atom_code": None,
                    "den_atom_code": None,
                    "extra_atoms": [],
                }
                metrics.append(metric_rec)
                metric_by_code[code] = metric_rec
                duplicate_counter[code] += 1
                last_metric = metric_rec
        else:  # ATOM
            if last_metric is None:
                anomalies.append({"row_no": row_no, "kind": "ATOM_WITHOUT_METRIC",
                                  "detail": f"基础指标 {code} 前无任何指标行"})
            else:
                row_rec["level"] = last_metric["level"] + 1  # ATOM level = 所属指标 level + 1
                row_rec["parent_code"] = last_metric["code"]  # = metric_code
                row_rec["metric_code"] = last_metric["code"]
                atom_groups[last_metric["code"]].append(
                    {"code": code, "name": name, "row": row_rec}
                )
                last_metric["atoms"].append({"code": code, "name": name})

        rows_out.append(row_rec)

    wb.close()

    # ---- 原子角色判定（04 §7.1：2 个→NUM/DEN；1 个→SINGLE；≥3 个→EXTRA；0 个→空并记台账）----
    for m in metrics:
        atoms = atom_groups.get(m["code"], [])
        n = len(atoms)
        if n == 2:
            atoms[0]["row"]["atom_role"] = "NUM"
            atoms[1]["row"]["atom_role"] = "DEN"
            m["num_atom_code"] = atoms[0]["code"]
            m["den_atom_code"] = atoms[1]["code"]
        elif n == 1:
            atoms[0]["row"]["atom_role"] = "SINGLE"
            m["num_atom_code"] = atoms[0]["code"]
        elif n >= 3:
            for a in atoms:
                a["row"]["atom_role"] = "EXTRA"
            m["extra_atoms"] = [{"code": a["code"], "name": a["name"]} for a in atoms]
        else:
            anomalies.append({"row_no": m["row_no"], "kind": "METRIC_WITHOUT_ATOM",
                              "detail": f"{m['code']} {m['name']}"})

    # ---- 生成指标字典（04 §6.2）----
    dict_entries = []
    alias_count = 0
    extract_mode_dist = Counter()
    not_applicable = 0
    for m in metrics:
        alias = extract_alias_code(m["name"])
        if alias:
            alias_count += 1
        norm = m["norm"]
        if norm.startswith("25.2.1"):
            part = "PART1"
        elif norm.startswith("25.2.2"):
            part = "PART2"
        elif norm.startswith("25.2.3"):
            part = "PART3"
        else:
            part = "PART1"
        # chapter_path：根到父章节名称用 / 连接，不含根节点"指标"（04 §7.1）
        chain = [ch for ch in m["ancestors"] if ch["name"] != "指标"]
        chapter_code = chain[-1]["code"] if chain else None
        chapter_path = "/".join(ch["name"] for ch in chain)
        # extract_mode：2025 来源，为空依次回退 2024/2023/2022（04 §7.1）
        picked_source = ""
        for yk in ("2025", "2024", "2023", "2022"):
            v = m["values"][yk]["source"]
            if v:
                picked_source = v
                break
        extract_mode, is_applicable = map_extract_mode_from_source(picked_source)
        extract_mode_dist[extract_mode] += 1
        if not is_applicable:
            not_applicable += 1
        # metric_attr：≥2 个 ATOM → 定量；仅 1 个 ATOM 且任一年数据是 是/否/有/无 → 定性；其余 → 定量
        n_atoms = len(m["atoms"])
        if n_atoms >= 2:
            attr = "定量指标"
        elif n_atoms == 1:
            attr = "定性指标"
            for yk in YEAR_KEYS:
                d = m["values"][yk]["data"]
                if d in QUALITATIVE_VALUES:
                    break
            else:
                attr = "定量指标"
        else:
            attr = "定量指标"
        dict_entries.append({
            "metric_code": m["code"],
            "alias_code": alias,
            "master_row_no": m["row_no"],
            "metric_name": m["name"],
            "chapter_part": part,
            "chapter_code": chapter_code,
            "chapter_path": chapter_path,
            "num_atom_code": m["num_atom_code"],
            "den_atom_code": m["den_atom_code"],
            "extra_atoms": m["extra_atoms"],
            "metric_attr": attr,
            "extract_mode": extract_mode,
            "is_applicable": is_applicable,
            "source_text_2025": m["values"]["2025"]["source"],
        })

    type_counts = Counter(r["row_type"] for r in rows_out)
    total = len(rows_out)
    duplicates = sorted(c for c, n in duplicate_counter.items() if n > 1)

    generated_at = datetime.now().replace(microsecond=0).isoformat()
    master_doc = {
        "meta": {
            "generated_at": generated_at,
            "generator": "00a_parse_master.py",
            "source_files": [SRC],
            "counts": {
                "CHAPTER": type_counts.get("CHAPTER", 0),
                "METRIC": type_counts.get("METRIC", 0),
                "ATOM": type_counts.get("ATOM", 0),
                "TOTAL": total,
                "skipped_empty": skipped_empty,
            },
            "duplicate_codes": duplicates,
            "anomaly_count": len(anomalies),
        },
        "rows": rows_out,
    }
    atoms_n = Counter(len(m["atoms"]) for m in metrics)
    dict_doc = {
        "meta": {
            "generated_at": generated_at,
            "generator": "00a_parse_master.py",
            "source_files": [SRC],
            "counts": {
                "metrics": len(metrics),
                "with_alias_code": alias_count,
                "atoms_2": atoms_n.get(2, 0),
                "atoms_1": atoms_n.get(1, 0),
                "atoms_3plus": sum(v for k, v in atoms_n.items() if isinstance(k, int) and k >= 3),
                "atoms_0": atoms_n.get(0, 0),
            },
            "duplicate_codes": duplicates,
            "extract_mode_distribution": dict(extract_mode_dist),
            "not_applicable": not_applicable,
        },
        "metrics": dict_entries,
    }
    write_json(MASTER_JSON, master_doc)
    write_json(DICT_JSON, dict_doc)
    write_report(REPORT_MD, generated_at, type_counts, total, skipped_empty, metrics, atoms_n,
                 extract_mode_dist, not_applicable, alias_count, duplicates, anomalies)

    print(f"OK CHAPTER={type_counts.get('CHAPTER', 0)} METRIC={type_counts.get('METRIC', 0)} "
          f"ATOM={type_counts.get('ATOM', 0)} TOTAL={total}")


def write_report(path, generated_at, type_counts, total, skipped_empty, metrics, atoms_n,
                 extract_mode_dist, not_applicable, alias_count, duplicates, anomalies):
    """生成解析台账报告（TASK-01 步骤 3 第 7 条要求的全部内容）。"""
    lines = []
    ap = lines.append
    ap("# TASK-01 总表解析报告")
    ap("")
    ap(f"- 生成时间：{generated_at}")
    ap(f"- 源文件：`{SRC}`（sheet1，只读打开，数据自第 3 行）")
    ap(f"- 生成脚本：`migration/00a_parse_master.py`")
    ap("")
    ap("## 1. 各类型行数")
    ap("")
    ap("| 类型 | 行数 |")
    ap("|---|---:|")
    ap(f"| CHAPTER（章节） | {type_counts.get('CHAPTER', 0)} |")
    ap(f"| METRIC（指标） | {type_counts.get('METRIC', 0)} |")
    ap(f"| ATOM（基础指标） | {type_counts.get('ATOM', 0)} |")
    ap(f"| **TOTAL** | **{total}** |")
    ap(f"| 空编号跳过行 | {skipped_empty} |")
    ap("")
    ap("## 2. ATOM 个数分布（按指标）")
    ap("")
    ap("| 每条指标的 ATOM 个数 | 指标数 |")
    ap("|---|---:|")
    ap(f"| 0 个 | {atoms_n.get(0, 0)} |")
    ap(f"| 1 个 | {atoms_n.get(1, 0)} |")
    ap(f"| 2 个 | {atoms_n.get(2, 0)} |")
    ap(f"| ≥3 个 | {sum(v for k, v in atoms_n.items() if isinstance(k, int) and k >= 3)} |")
    ap("")
    ap("### 2.1 ATOM ≥3 的指标清单（全部按 EXTRA 处理，num/den 置空）")
    ap("")
    multi = [m for m in metrics if len(m["atoms"]) >= 3]
    if multi:
        ap("| 指标编号 | 指标名称 | ATOM 数 | 原子编号 |")
        ap("|---|---|---:|---|")
        for m in multi:
            codes = "、".join(a["code"] for a in m["atoms"])
            ap(f"| {m['code']} | {m['name']} | {len(m['atoms'])} | {codes} |")
    else:
        ap("无")
    ap("")
    ap("### 2.2 ATOM =0 的指标清单（num/den 均为空，待人工确认）")
    ap("")
    zero = [m for m in metrics if len(m["atoms"]) == 0]
    if zero:
        ap("| 指标编号 | 指标名称 |")
        ap("|---|---|")
        for m in zero:
            ap(f"| {m['code']} | {m['name']} |")
    else:
        ap("无")
    ap("")
    ap("## 3. extract_mode 分布与不适用数量")
    ap("")
    ap("| extract_mode | 指标数 |")
    ap("|---|---:|")
    for k in sorted(extract_mode_dist):
        ap(f"| {k} | {extract_mode_dist[k]} |")
    ap(f"| **is_applicable=false（不适用）** | **{not_applicable}** |")
    ap("")
    ap("## 4. alias_code 非空数量")
    ap("")
    ap(f"alias_code 非空的指标 {alias_count} 条 / 共 {len(metrics)} 条指标。")
    ap("")
    ap("## 5. 重复 metric_code")
    ap("")
    if duplicates:
        ap("| 重复编号 | 出现次数 |")
        ap("|---|---:|")
        cnt = Counter(m["code"] for m in metrics)
        for c in duplicates:
            ap(f"| {c} | {cnt[c]} |")
        ap("")
        ap("重复编号未丢弃，全部保留在 rows 与 metrics 中，已记入两个 JSON 的 `meta.duplicate_codes`。")
    else:
        ap("无")
    ap("")
    ap("## 6. 找不到父章节的行")
    ap("")
    no_parent = [a for a in anomalies if a["kind"] == "NO_PARENT_CHAPTER"]
    if no_parent:
        ap("| Excel 行号 | 类型 | 编号 | 名称 | 说明 |")
        ap("|---:|---|---|---|---|")
        for a in no_parent:
            t, c, n = a["detail"].split(" ", 2)
            ap(f"| {a['row_no']} | {t} | {c} | {n} | 顶层章节无父章节（符合三层结构预期） |")
    else:
        ap("无")
    ap("")
    ap("## 7. 其他异常行")
    ap("")
    others = [a for a in anomalies if a["kind"] not in ("NO_PARENT_CHAPTER",)]
    if others:
        ap("| Excel 行号 | 异常类型 | 说明 |")
        ap("|---:|---|---|")
        for a in others:
            ap(f"| {a['row_no']} | {a['kind']} | {a['detail']} |")
    else:
        ap("无")
    ap("")
    write_text(path, "\n".join(lines))


def write_text(path, text):
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    main()
