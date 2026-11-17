# -*- coding: utf-8 -*-
"""
TASK-18 步骤 2：真实跑批结果 vs 存量基线 交叉对账。
对 run_log.json 中每个跑过的科室实例 × 年份（2022~2025）：
- 新值：该实例该年当期行（TASK-18 批次产出的 is_current 行）分子、分母；
- 基线 A：BATCH_BASELINE_IMPORT 同实例同年的科室行（专科表年度数字）；
- 基线 B：同指标同年全院行（总表）——仅当该指标只有 1 个非免统科室时才可比。
分子、分母分别判定：完全相等=一致；差异≤2% 或绝对差≤2=轻微差异；更大=显著差异；
基线不存在=无基线；新值为空=未产出（附失败子任务错误信息）。
结果写 migration/.tmp/compare_result.json（供报告脚本消费），并打印汇总。
"""
import json
import sys
from pathlib import Path

import psycopg

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "deploy" / ".env"
RUN_LOG = ROOT / "migration" / ".tmp" / "run_log.json"
OUT_FILE = ROOT / "migration" / ".tmp" / "compare_result.json"

YEARS = [2022, 2023, 2024, 2025]
BASELINE_BATCH_NO = "BATCH_BASELINE_IMPORT"


def env_value(name: str) -> str:
    for line in ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
        if line.strip().startswith(f"{name}="):
            v = line.split("=", 1)[1].strip()
            if v:
                return v
    raise SystemExit(f"deploy/.env 缺少变量 {name}")


def connect():
    return psycopg.connect(
        host="127.0.0.1", port=env_value("EVAL_PG_PORT"),
        user=env_value("POSTGRES_USER"), password=env_value("POSTGRES_PASSWORD"),
        dbname=env_value("POSTGRES_DB"))


def compare_value(new, base):
    """单值判定：一致 / 轻微差异 / 显著差异 / 无基线。"""
    if base is None:
        return "无基线"
    if new is None:
        return "未产出"
    if new == base:
        return "一致"
    diff_abs = abs(new - base)
    base_abs = abs(base)
    diff_pct = diff_abs / base_abs if base_abs != 0 else None
    if (diff_pct is not None and diff_pct <= 0.02) or diff_abs <= 2:
        return "轻微差异"
    return "显著差异"


def worst(a, b):
    """行级判定取字段判定的较差者。"""
    order = {"一致": 0, "无基线": 1, "轻微差异": 2, "显著差异": 3, "未产出": 3}
    return a if order.get(a, 0) >= order.get(b, 0) else b


def main():
    if not RUN_LOG.exists():
        raise SystemExit("未找到 migration/.tmp/run_log.json——请先运行 05_run_real_batches.py")
    log = json.loads(RUN_LOG.read_text(encoding="utf-8"))
    scope_specialty = {}
    for r in log:
        for sid in r.get("scopeIds") or []:
            scope_specialty.setdefault(sid, r["specialty"])
    if not scope_specialty:
        raise SystemExit("run_log.json 无任何批次记录")

    conn = connect()
    cur = conn.cursor()
    cur.execute("SELECT id FROM eval_exec_batch WHERE batch_no = %s", (BASELINE_BATCH_NO,))
    row = cur.fetchone()
    baseline_batch_id = row[0] if row else None

    # 单非免统科室指标集合（基线 B 可比）
    cur.execute("""
        SELECT metric_id, count(*) FROM eval_metric_dept_scope
        WHERE skip_flag = false GROUP BY metric_id HAVING count(*) = 1""")
    single_scope_metrics = {r[0] for r in cur.fetchall()}

    items = []
    summary = {}  # specialty -> {verdict: n}
    for sid in sorted(scope_specialty):
        cur.execute("SELECT metric_id, resp_dept_name FROM eval_metric_dept_scope WHERE id = %s", (sid,))
        mrow = cur.fetchone()
        if not mrow:
            continue
        metric_id, dept_name = mrow
        cur.execute("SELECT metric_code, metric_name, unit, is_applicable FROM eval_metric_dict WHERE id = %s", (metric_id,))
        drow = cur.fetchone()
        metric_code, metric_name, unit, applicable = drow
        use_baseline_b = metric_id in single_scope_metrics

        # 失败子任务错误（TASK-18 批次、该指标该实例、最近一条 FAILED）
        cur.execute("""
            SELECT t.error_msg FROM eval_exec_task t
            JOIN eval_exec_batch b ON b.id = t.batch_id
            WHERE b.version_tag LIKE 'TASK-18 真实数据首跑-%%'
              AND t.metric_id = %s AND t.scope_id = %s AND t.status = 'FAILED'
            ORDER BY t.id DESC LIMIT 1""", (metric_id, sid))
        erow = cur.fetchone()
        fail_msg = (erow[0] or "")[:200] if erow else None

        for year in YEARS:
            period = str(year)
            # 新值：当期行
            cur.execute("""
                SELECT numerator_val, denominator_val FROM eval_metric_result
                WHERE metric_id=%s AND scope_id=%s AND stat_period_type='YEAR'
                  AND stat_period=%s AND is_current=true
                ORDER BY id DESC LIMIT 1""", (metric_id, sid, period))
            nrow = cur.fetchone()
            new_num = float(nrow[0]) if nrow and nrow[0] is not None else None
            new_den = float(nrow[1]) if nrow and nrow[1] is not None else None

            # 基线 A：基线批次同实例同年
            base_a_num = base_a_den = None
            if baseline_batch_id:
                cur.execute("""
                    SELECT numerator_val, denominator_val FROM eval_metric_result
                    WHERE metric_id=%s AND scope_id=%s AND stat_period_type='YEAR'
                      AND stat_period=%s AND batch_id=%s
                    ORDER BY id DESC LIMIT 1""", (metric_id, sid, period, baseline_batch_id))
                arow = cur.fetchone()
                if arow:
                    base_a_num = float(arow[0]) if arow[0] is not None else None
                    base_a_den = float(arow[1]) if arow[1] is not None else None

            # 基线 B：全院行（仅单科室指标）
            base_b_num = base_b_den = None
            if use_baseline_b:
                cur.execute("""
                    SELECT numerator_val, denominator_val FROM eval_metric_result
                    WHERE metric_id=%s AND scope_id=0 AND stat_period_type='YEAR'
                      AND stat_period=%s AND is_current=true
                    ORDER BY id DESC LIMIT 1""", (metric_id, period))
                brow = cur.fetchone()
                if brow:
                    base_b_num = float(brow[0]) if brow[0] is not None else None
                    base_b_den = float(brow[1]) if brow[1] is not None else None

            verdicts = {}
            for field, nv, av, bv in (("num", new_num, base_a_num, base_b_num),
                                      ("den", new_den, base_a_den, base_b_den)):
                if av is not None:
                    verdicts[field] = compare_value(nv, av)
                elif bv is not None:
                    verdicts[field] = compare_value(nv, bv)
                else:
                    verdicts[field] = "无基线"
            if (new_num is None and new_den is None) and fail_msg:
                verdicts["num"] = verdicts["den"] = "未产出"

            row_verdict = worst(verdicts["num"], verdicts["den"])
            specialty = scope_specialty[sid]
            summary.setdefault(specialty, {})
            summary[specialty][row_verdict] = summary[specialty].get(row_verdict, 0) + 1
            if row_verdict == "显著差异":
                items.append({
                    "specialty": specialty, "metricId": metric_id, "metricCode": metric_code,
                    "metricName": metric_name, "deptName": dept_name, "year": year,
                    "newNum": new_num, "newDen": new_den,
                    "baseANum": base_a_num, "baseADen": base_a_den,
                    "baseBNum": base_b_num, "baseBDen": base_b_den,
                    "verdictNum": verdicts["num"], "verdictDen": verdicts["den"],
                    "failMsg": fail_msg, "unit": unit, "applicable": applicable,
                })

    conn.close()
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps({
        "generatedAt": __import__("datetime").datetime.now().isoformat(),
        "summary": summary, "significant": items,
    }, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"对账完成，结果已写 {OUT_FILE.name}")
    print(f"{'专业':<6}{'一致':>6}{'轻微':>6}{'显著':>6}{'无基线':>7}{'未产出':>7}{'一致率':>9}")
    for specialty, counts in sorted(summary.items()):
        total_n = sum(counts.values())
        ok = counts.get("一致", 0)
        rate = round(ok * 100.0 / total_n, 1) if total_n else 0.0
        print(f"{specialty:<6}{counts.get('一致', 0):>6}{counts.get('轻微差异', 0):>6}"
              f"{counts.get('显著差异', 0):>6}{counts.get('无基线', 0):>7}{counts.get('未产出', 0):>7}{rate:>8}%")
    sig_total = sum(c.get("显著差异", 0) for c in summary.values())
    print(f"显著差异合计 {sig_total} 条（明细见 JSON 的 significant 数组）")


if __name__ == "__main__":
    main()
