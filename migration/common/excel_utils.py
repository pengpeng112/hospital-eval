# -*- coding: utf-8 -*-
"""公共解析工具函数（TASK-01~03 共用，规则口径见 04_开发契约与编码规范.md）。

本模块只做纯文本/数值处理，不读文件、不连数据库。
规则来源统一引用《04_开发契约与编码规范.md》（下称 04）：
- SQL 注释剥离 04 §7.4；单元格分类 04 §7.3；SQL 角色与输出列 04 §7.5；
- 年份范围 04 §7.6；数据源路由 04 §7.7；免统判定 04 §7.8；
- 提取方式映射 04 §7.1 / §7.9；总表编号标准化与别名代号 04 §7.1；
- 专科表头映射与科室范围拆分 04 §7.2；指标名称标准化 04 §7.10；
- 中间 JSON 写盘格式 04 §6。
"""
import json
import os
import re

# ---------------------------------------------------------------------------
# 04 §7.4 注释剥离
# ---------------------------------------------------------------------------
def strip_sql_comments(text: str) -> str:
    """剥离 SQL 中的行注释（--）与块注释（/* */），尊重单引号字符串与双引号标识符。

    规则（04 §7.4）：逐字符扫描，维护 普通/字符串/标识符/行注释/块注释 五种状态；
    字符串内 ``''`` 是转义；行注释到换行为止但保留换行符；块注释整体替换为一个空格；
    字符串、标识符里的 ``--`` ``/*`` 原样保留。输出去首尾空白并去掉末尾的 ``;``。
    """
    if text is None:
        return ""
    s = str(text)
    out = []
    i, n = 0, len(s)
    NORMAL, STRING, IDENT, LINE, BLOCK = range(5)
    state = NORMAL
    while i < n:
        ch = s[i]
        nxt = s[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if ch == "'":
                state = STRING
                out.append(ch)
                i += 1
            elif ch == '"':
                state = IDENT
                out.append(ch)
                i += 1
            elif ch == "-" and nxt == "-":
                state = LINE
                i += 2
            elif ch == "/" and nxt == "*":
                state = BLOCK
                i += 2
            else:
                out.append(ch)
                i += 1
        elif state == STRING:
            if ch == "'":
                if nxt == "'":  # 字符串内 '' 转义，原样保留
                    out.append("''")
                    i += 2
                    continue
                state = NORMAL
            out.append(ch)
            i += 1
        elif state == IDENT:
            if ch == '"':
                state = NORMAL
            out.append(ch)
            i += 1
        elif state == LINE:
            if ch == "\n":  # 行注释结束，保留换行符
                out.append(ch)
                state = NORMAL
            i += 1
        else:  # BLOCK：整块丢弃，结束时替换为一个空格
            if ch == "*" and nxt == "/":
                out.append(" ")
                state = NORMAL
                i += 2
            else:
                i += 1
    result = "".join(out).strip()
    if result.endswith(";"):
        result = result[:-1]
    return result


# ---------------------------------------------------------------------------
# 04 §7.3 单元格内容分类
# ---------------------------------------------------------------------------
_NUM_TEXT_RE = re.compile(r"^-?\d+(\.\d+)?$")
_URL_RE = re.compile(r"^https?://")
_SQL_HEAD_RE = re.compile(r"^(SELECT|WITH)\b", re.IGNORECASE)


def classify_cell(value) -> str:
    """分类 Excel 单元格内容：EMPTY / NUMBER / SQL / COMMENT_ONLY / DIRTY / TEXT。

    规则（04 §7.3）：1) 空或纯空白 → EMPTY；2) 数字类型或匹配
    ``^-?\\d+(\\.\\d+)?$`` → NUMBER；3) 去注释后（允许前导空白和左括号）以
    SELECT 或 WITH 开头（不区分大小写）→ SQL；4) 去注释后为空 → COMMENT_ONLY；
    5) 匹配 ``^https?://`` 或去空白后等于 ``sql``（不区分大小写）→ DIRTY；
    6) 其他 → TEXT。
    """
    if value is None:
        return "EMPTY"
    if isinstance(value, bool):
        return "TEXT"
    if isinstance(value, (int, float)):
        return "NUMBER"
    s = str(value)
    stripped = s.strip()
    if not stripped:
        return "EMPTY"
    if _NUM_TEXT_RE.match(stripped):
        return "NUMBER"
    no_comment = strip_sql_comments(s)
    core = no_comment.lstrip(" \t\r\n(")
    if _SQL_HEAD_RE.match(core):
        return "SQL"
    if not no_comment:
        return "COMMENT_ONLY"
    if _URL_RE.match(stripped) or stripped.lower() == "sql":
        return "DIRTY"
    return "TEXT"


# ---------------------------------------------------------------------------
# 04 §7.5 SQL 结果形态 / 输出列
# ---------------------------------------------------------------------------
_DTL_ALIAS_RE = re.compile(r"AS\s+\"?(患者ID|住院号|PATIENT_ID)\"?", re.IGNORECASE)
_GROUP_BY_RE = re.compile(r"GROUP\s+BY", re.IGNORECASE)
_AGG_FUN_RE = re.compile(r"(COUNT|SUM)\s*\(", re.IGNORECASE)
_OUTPUT_ALIAS_RE = re.compile(r"\bAS\s+\"?([\u4e00-\u9fa5A-Za-z_0-9]+)\"?")


def detect_result_shape(sql_executable: str) -> tuple:
    """判定 SQL 结果形态，返回 ``("DTL"或"SUM", 是否猜测)``。

    规则（04 §7.5）：出现 ``AS 患者ID``、``AS 住院号``、``AS "患者ID"``、
    ``AS "住院号"``、``AS PATIENT_ID``（不区分大小写）任一 → DTL（非猜测）；
    否则出现 GROUP BY 且出现 COUNT( 或 SUM( → SUM（非猜测）；否则 → DTL
    并标记猜测（台账记 SHAPE_GUESS）。
    """
    s = str(sql_executable or "")
    if _DTL_ALIAS_RE.search(s):
        return ("DTL", False)
    if _GROUP_BY_RE.search(s) and _AGG_FUN_RE.search(s):
        return ("SUM", False)
    return ("DTL", True)


def extract_output_columns(sql: str) -> list:
    """提取 SQL 中 ``AS 别名`` 列表，去重保序（04 §7.5，正则照抄契约）。"""
    out, seen = [], set()
    for m in _OUTPUT_ALIAS_RE.finditer(str(sql or "")):
        alias = m.group(1)
        if alias and alias not in seen:
            seen.add(alias)
            out.append(alias)
    return out


# ---------------------------------------------------------------------------
# 04 §7.6 年份范围
# ---------------------------------------------------------------------------
_DATE_LITERAL_RES = (
    re.compile(r"\bDATE\s*'(\d{4})-(\d{2})-(\d{2})'", re.IGNORECASE),
    re.compile(r"\bTO_DATE\s*\(\s*'(\d{4})-(\d{2})-(\d{2})'", re.IGNORECASE),
)


def header_year_range(header: str) -> tuple:
    """由表头文字推年份范围 ``(year_from, year_to)``（04 §7.6）。

    含 ``2021-2024`` 或 ``2022-2024`` → 2022~2024；含 ``2025`` 且不含 ``-`` →
    2025~2025；含 ``2022-2025`` → 2022~2025；其余（含无年份表头）→ 2022~2025。
    """
    h = str(header or "")
    if "2021-2024" in h or "2022-2024" in h:
        return (2022, 2024)
    if "2025" in h and "-" not in h:
        return (2025, 2025)
    if "2022-2025" in h:
        return (2022, 2025)
    return (2022, 2025)


def literal_year_range(sql: str):
    """由 SQL 日期字面量推年份范围 ``(year_from, year_to)``，推不出返回 None。

    规则（04 §7.6）：提取所有 ``DATE 'YYYY-MM-DD'`` 与 ``TO_DATE('YYYY-MM-DD'``
    中的日期，最小为 lo、最大为 hi；``year_from`` = lo 是 1 月 1 日 ? lo 年 :
    lo 年 + 1；``year_to`` = hi 是 1 月 1 日 ? hi 年 - 1 : hi 年。
    """
    s = str(sql or "")
    dates = []
    for rex in _DATE_LITERAL_RES:
        for m in rex.finditer(s):
            dates.append((int(m.group(1)), int(m.group(2)), int(m.group(3))))
    if not dates:
        return None
    lo = min(dates)
    hi = max(dates)
    year_from = lo[0] if (lo[1], lo[2]) == (1, 1) else lo[0] + 1
    year_to = hi[0] - 1 if (hi[1], hi[2]) == (1, 1) else hi[0]
    return (year_from, year_to)


def final_year_range(header: str, sql: str) -> tuple:
    """综合表头与 SQL 字面量求最终年份范围，返回 ``(year_from, year_to, period_flag)``。

    规则（04 §7.6）：SQL 字面量推得出 → 用字面量结果；表头结果也存在且两者
    不一致 → ``period_flag = 'PERIOD_CONFLICT'``；推不出 → 用表头结果。
    最终 ``year_from = max(year_from, 2022)``。
    """
    lit = literal_year_range(sql)
    if lit is not None:
        hdr = header_year_range(header)
        flag = "PERIOD_CONFLICT" if lit != hdr else None
        return (max(lit[0], 2022), lit[1], flag)
    year_from, year_to = header_year_range(header)
    return (max(year_from, 2022), year_to, None)


# ---------------------------------------------------------------------------
# 04 §7.7 数据源路由
# ---------------------------------------------------------------------------
_DS_SCHEMAS = ("HIS", "HISUSER", "MEDREC", "ORDADM", "COMM", "MTL", "JHEMR", "LIS", "SM", "PACS")
_DS_GROUP_HIS = frozenset(("MEDREC", "ORDADM", "COMM", "HISUSER"))
_DS_GROUP_ODS = frozenset(("HIS", "MTL", "JHEMR", "LIS", "SM", "PACS"))
_SCHEMA_TABLE_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9_]*)\.([A-Za-z][A-Za-z0-9_]*)")


def route_datasource(sql: str) -> tuple:
    """按 SQL 中出现的模式名路由数据源，返回 ``(datasource_key, ds_flag, emr_sys)``。

    规则（04 §7.7）：提取所有 ``模式名.表名`` 的模式名（大写），只看
    HIS/HISUSER/MEDREC/ORDADM/COMM/MTL/JHEMR/LIS/SM/PACS；只出现
    MEDREC/ORDADM/COMM/HISUSER → HIS_RO；只出现 HIS/MTL/JHEMR/LIS/SM/PACS →
    ODS_RO；两组都有 → ODS_RO 且 ``ds_flag='DS_CONFLICT'``；都没有 → ODS_RO
    且 ``ds_flag='DS_UNKNOWN'``。``emr_sys``：SQL 同时含 ``MTL.`` 与
    ``JHEMR.`` → ``MTL+JHEMR``；只含其一 → 对应值；都不含 → ``NONE``。
    """
    s = str(sql or "")
    schemas = set()
    for m in _SCHEMA_TABLE_RE.finditer(s):
        sch = m.group(1).upper()
        if sch in _DS_SCHEMAS:
            schemas.add(sch)
    upper = s.upper()
    if "MTL." in upper and "JHEMR." in upper:
        emr_sys = "MTL+JHEMR"
    elif "MTL." in upper:
        emr_sys = "MTL"
    elif "JHEMR." in upper:
        emr_sys = "JHEMR"
    else:
        emr_sys = "NONE"
    hit_his = schemas & _DS_GROUP_HIS
    hit_ods = schemas & _DS_GROUP_ODS
    if hit_his and hit_ods:
        return ("ODS_RO", "DS_CONFLICT", emr_sys)
    if hit_his:
        return ("HIS_RO", None, emr_sys)
    if hit_ods:
        return ("ODS_RO", None, emr_sys)
    return ("ODS_RO", "DS_UNKNOWN", emr_sys)


# ---------------------------------------------------------------------------
# 04 §7.1 别名代号 / 编号标准化
# ---------------------------------------------------------------------------
_ALIAS_CODE_RE = re.compile(r"[A-Z]{2,6}(?:-[A-Z0-9]+)+")
_TRAILING_PAREN_RE = re.compile(r"\([^()]*\)$")


def extract_alias_code(name: str):
    """提取指标名称中的专科代号（如 ``NEU-EPI-01``），没有则返回 None。

    规则（04 §7.1）：去掉名称中所有空白后，取最后一个匹配
    ``[A-Z]{2,6}(-[A-Z0-9]+)+`` 的片段（例：``NEU-STK- 15A`` → ``NEU-STK-15A``）。
    """
    if name is None:
        return None
    compact = re.sub(r"\s+", "", str(name))
    matches = _ALIAS_CODE_RE.findall(compact)
    return matches[-1] if matches else None


def normalize_code(code: str) -> str:
    """标准化指标编号：去掉末尾 ``(22)``、``(ps)`` 这类括号后缀（04 §7.1）。

    连续的末尾括号组会被循环剥离（如 ``x(22)(ps)`` → ``x``）；仅去尾部、
    不改动编号中其他字符。
    """
    if code is None:
        return ""
    s = str(code).strip()
    while True:
        m = _TRAILING_PAREN_RE.search(s)
        if not m:
            break
        s = s[: m.start()].rstrip()
    return s


# ---------------------------------------------------------------------------
# 04 §7.2 科室范围拆分
# ---------------------------------------------------------------------------
_DEPT_RANGE_SPLIT_RE = re.compile(r"[、，,;；\r\n]+")


def split_dept_range(text: str) -> list:
    """按 ``、，,;；`` 和换行拆分科室范围，去空白、去空串（04 §7.2）。"""
    if text is None:
        return []
    parts = _DEPT_RANGE_SPLIT_RE.split(str(text))
    return [p.strip() for p in parts if p and p.strip()]


# ---------------------------------------------------------------------------
# 04 §7.1 / §7.9 提取方式映射
# ---------------------------------------------------------------------------
def map_extract_mode_from_source(text: str) -> tuple:
    """总表"数据来源"文本 → ``(extract_mode, is_applicable)``（04 §7.1）。

    ``医院系统提取+手工统计`` → HYBRID；``医院系统提取`` → SYS；
    ``手工统计`` → MANUAL；``省级系统提取`` → PROV；``不适用`` → MANUAL 且
    ``is_applicable=False``；全空 → MANUAL（适用）。采用包含匹配以兼容
    同义写法（实测总表仅出现契约所列 5 种文本）。
    """
    t = ("" if text is None else str(text)).strip()
    if not t:
        return ("MANUAL", True)
    if "不适用" in t:
        return ("MANUAL", False)
    if "省级" in t:
        # 必须先于"系统提取"判断："省级系统提取"包含子串"系统提取"
        return ("PROV", True)
    if "系统提取" in t and "手工" in t:
        return ("HYBRID", True)
    if "系统提取" in t:
        return ("SYS", True)
    if "手工" in t:
        return ("MANUAL", True)
    return ("MANUAL", True)


def map_extract_mode_from_raw(text: str, fallback: str) -> str:
    """专科表"提取方式"文本 → ``extract_mode``（04 §7.9）。

    含 ``手工`` 且含（``系统``/``信息``/``自动``）→ HYBRID；只含 ``手工``/
    ``人工`` → MANUAL；含 ``系统``/``信息``/``自动`` → SYS；为空或无法识别 →
    继承 ``fallback``（调用方传入指标字典的 ``extract_mode``）。
    """
    t = ("" if text is None else str(text)).strip()
    if not t:
        return fallback
    has_manual = ("手工" in t) or ("人工" in t)
    has_sys = ("系统" in t) or ("信息" in t) or ("自动" in t)
    if has_manual and has_sys:
        return "HYBRID"
    if has_manual:
        return "MANUAL"
    if has_sys:
        return "SYS"
    return fallback


# ---------------------------------------------------------------------------
# 04 §7.8 免统判定
# ---------------------------------------------------------------------------
_SKIP_KEYWORDS = (
    "请外科统计",
    "本科室无手术",
    "无手术",
    "未开展",
    "无需统计",
    "不需统计",
    "不统计",
    "本科室无",
)


def detect_skip(*texts) -> tuple:
    """免统判定：任一文本命中免统关键字 → ``(True, 命中的原文片段≤100字)``。

    规则（04 §7.8）：关键字为 请外科统计 / 本科室无手术 / 无手术 / 未开展 /
    无需统计 / 不需统计 / 不统计 / 本科室无（长词优先，避免"本科室无手术"
    被"本科室无"抢先命中）；全部未命中 → ``(False, None)``。
    """
    for t in texts:
        if t is None:
            continue
        s = str(t)
        for kw in _SKIP_KEYWORDS:
            idx = s.find(kw)
            if idx >= 0:
                frag = s[idx: idx + 100]
                return (True, frag[:100])
    return (False, None)


# ---------------------------------------------------------------------------
# 04 §7.10 指标名称标准化
# ---------------------------------------------------------------------------
_LEADING_NUM_RE = re.compile(r"^(?:\d+(?:\.\d+)*\.?(?![A-Za-z0-9])|[（(][^\)）]{1,12}[)）])")
_EN_CODE_IN_BRACKET_RE = re.compile(r"[（(][A-Za-z0-9\-/\.、 ]+[)）]")
_KEEP_CHARS_RE = re.compile(r"[^\u4e00-\u9fa5A-Za-z0-9]")


def normalize_metric_name(name: str) -> str:
    """标准化指标名称，用于跨表名称匹配（04 §7.10）。

    步骤：去所有空白；循环去开头编号（``1.``、``15.1``、``（一）``、``(1)``
    等；纯数字后紧跟字母时视为名称本身如 ``48h``，不剥）；去括号内的英文
    代号（括号内仅字母/数字/``-``/``/``/``.``/``、``/空格 的整体括号组）；
    最后只保留汉字、字母、数字。对两侧名称对称生效，保证匹配公平。
    """
    if not name:
        return ""
    s = re.sub(r"\s+", "", str(name))
    while True:
        m = _LEADING_NUM_RE.match(s)
        if not m:
            break
        s = s[m.end():]
    s = _EN_CODE_IN_BRACKET_RE.sub("", s)
    s = _KEEP_CHARS_RE.sub("", s)
    return s


# ---------------------------------------------------------------------------
# 04 §6 中间 JSON 写盘
# ---------------------------------------------------------------------------
def write_json(path, obj) -> None:
    """写中间 JSON：UTF-8、``ensure_ascii=False``、``indent=1``，自动建目录（04 §6）。"""
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
