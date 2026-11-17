# -*- coding: utf-8 -*-
"""
TASK-18 步骤 1：真实数据分批跑批（经系统跑批引擎 /api/batch/run）。
- 从 PostgreSQL 查询 ROOT_12 来源（12 个重点专业文件、非免统、有启用 SQL）的科室实例，按专业分组；
- 每组切批 ≤ 30 个实例，years=[2022,2023,2024,2025]，versionTag="TASK-18 真实数据首跑-<专业>"；
- 每 30 秒轮询批次状态；单批超 2 小时记 TIMEOUT 并继续下一批；
- 批次记录追加写 migration/.tmp/run_log.json；支持 --only 专业简称 与 --resume；
- 工作时间（08:00–18:00）不跑 心血管/肿瘤/神经 三个大专业（含 CLOB 文书，SQL 多）。
安全红线：只经后端跑批引擎执行 SELECT；本脚本不直连 Oracle、零写入。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import psycopg

ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = ROOT / "deploy" / ".env"
RUN_LOG = ROOT / "migration" / ".tmp" / "run_log.json"
BASE = "http://127.0.0.1:48080"

# 执行顺序（小专业在前）；大专业仅 18:00 后执行
SMALL_ORDER = ["病案", "康复", "营养", "麻醉", "感染", "疼痛", "脑损伤", "重症", "呼吸"]
BIG_ORDER = ["心血管", "肿瘤", "神经"]
BIG_SET = set(BIG_ORDER)

# 12 个重点专业文件（source_ref 首段，含 .xlsx）→ 专业简称
SPECIALTY_FILES = {
    "九、病案管理质量控制指标（2021 年版）.xlsx": "病案",
    "十二、康复医学专业医疗质量控制指标（2022 年版）.xlsx": "康复",
    "十三、临床营养专业医疗质量控制指标（2022 年版）.xlsx": "营养",
    "十四、麻醉专业医疗质量控制指标（2022 年版）.xlsx": "麻醉",
    "十六、感染性疾病专业医疗质量控制指标（2023 年版）.xlsx": "感染",
    "十八、疼痛专业医疗质量控制指标（2023 年版）.xlsx": "疼痛",
    "二十一、脑损伤评价医疗质量控制指标（2024 年版）.xlsx": "脑损伤",
    "二十六、重症医学专业医疗质量控制指标（2024 年版）.xlsx": "重症",
    "三、呼吸内科专业医疗质量控制指标（2019 年版）.xlsx": "呼吸",
    "十、心血管系统疾病相关专业医疗质量控制指标（2021 年版）.xlsx": "心血管",
    "十五、肿瘤专业质量控制指标（2023 年版）.xlsx": "肿瘤",
    "五、神经系统疾病医疗质量控制指标（2020 年版）.xlsx": "神经",
}

YEARS = [2022, 2023, 2024, 2025]
BATCH_SIZE = 30
POLL_SECONDS = 30
BATCH_TIMEOUT_SECONDS = 2 * 3600


def env_value(name: str) -> str:
    """按名取 deploy/.env 变量值（不打印）。"""
    for line in ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
        if line.strip().startswith(f"{name}="):
            v = line.split("=", 1)[1].strip()
            if v:
                return v
    raise SystemExit(f"deploy/.env 缺少变量 {name}")


def load_run_log() -> list:
    RUN_LOG.parent.mkdir(parents=True, exist_ok=True)
    if RUN_LOG.exists():
        return json.loads(RUN_LOG.read_text(encoding="utf-8"))
    return []


def append_run_log(record: dict) -> None:
    log = load_run_log()
    log.append(record)
    RUN_LOG.write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")


def fetch_groups() -> dict:
    """从 PostgreSQL 查询 ROOT_12 有启用 SQL 的非免统实例，按专业简称分组（实例 id 升序）。"""
    placeholders = ", ".join(["%s"] * len(SPECIALTY_FILES))
    sql = f"""
        SELECT split_part(s.source_ref, '!', 1) AS fname, s.id
        FROM eval_metric_dept_scope s
        WHERE s.skip_flag = false
          AND s.source_ref IS NOT NULL
          AND split_part(s.source_ref, '!', 1) IN ({placeholders})
          AND EXISTS (SELECT 1 FROM eval_metric_sql q WHERE q.scope_id = s.id AND q.is_active = true)
        ORDER BY s.id
    """
    conn = psycopg.connect(
        host="127.0.0.1", port=env_value("EVAL_PG_PORT"),
        user=env_value("POSTGRES_USER"), password=env_value("POSTGRES_PASSWORD"),
        dbname=env_value("POSTGRES_DB"))
    groups = {}
    try:
        with conn.cursor() as cur:
            cur.execute(sql, list(SPECIALTY_FILES.keys()))
            for fname, sid in cur.fetchall():
                groups.setdefault(SPECIALTY_FILES[fname], []).append(int(sid))
    finally:
        conn.close()
    return groups


def api(method: str, path: str, token: str = None, body: dict = None):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def poll_batch(token: str, batch_id: int):
    """每 30 秒轮询；返回 (status, stats, elapsedSec)。status ∈ SUCCESS/PARTIAL_FAIL/TIMEOUT。"""
    started = time.time()
    while True:
        body = api("GET", f"/api/batch/{batch_id}", token)["data"]
        batch = body.get("batch") or {}
        stats = body.get("taskStats") or {}
        pending = (stats.get("PENDING") or 0) + (stats.get("RUNNING") or 0)
        total = sum((stats.get(k) or 0) for k in ("PENDING", "RUNNING", "SUCCESS", "FAILED", "SKIPPED"))
        elapsed = int(time.time() - started)
        done = total > 0 and pending == 0
        print(f"    批次 {batch_id} 轮询: 成功={stats.get('SUCCESS')} 失败={stats.get('FAILED')} "
              f"跳过={stats.get('SKIPPED')} 待跑={pending} 已用 {elapsed}s", flush=True)
        if done:
            failed = stats.get("FAILED") or 0
            return ("PARTIAL_FAIL" if failed > 0 else "SUCCESS"), stats, elapsed
        if elapsed > BATCH_TIMEOUT_SECONDS:
            return "TIMEOUT", stats, elapsed
        time.sleep(POLL_SECONDS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", help="只跑指定专业简称（如 病案）")
    parser.add_argument("--resume", action="store_true", help="跳过 run_log.json 中已 SUCCESS 的批次")
    args = parser.parse_args()

    now = datetime.now()
    in_work_hours = 8 <= now.hour < 18
    print(f"当前时间 {now:%Y-%m-%d %H:%M:%S}，工作时间={in_work_hours}（08:00–18:00 不跑 {BIG_ORDER}）")

    pwd = env_value("EVAL_INIT_ADMIN_PASSWORD")
    login = api("POST", "/api/auth/login", body={"username": "admin", "password": pwd})
    assert login["code"] == 0, "admin 登录失败"
    token = login["data"]["token"]
    print("admin 登录成功（密码不打印）")

    groups = fetch_groups()
    order = SMALL_ORDER + (BIG_ORDER if not in_work_hours else [])
    if args.only:
        order = [s for s in order if s == args.only]
        if not order and args.only in BIG_SET and in_work_hours:
            raise SystemExit(f"{args.only} 属大专业，工作时间（08:00–18:00）禁止执行；请在 18:00 后运行")

    log = load_run_log()
    done_keys = {(r["specialty"], r["batchIndex"]) for r in log if r.get("status") == "SUCCESS"}

    for specialty in order:
        ids = groups.get(specialty)
        if not ids:
            print(f"[{specialty}] 无 ROOT_12 启用 SQL 实例，跳过")
            continue
        batches = [ids[i:i + BATCH_SIZE] for i in range(0, len(ids), BATCH_SIZE)]
        print(f"[{specialty}] 实例 {len(ids)} 个 → {len(batches)} 批", flush=True)
        for bi, chunk in enumerate(batches):
            if args.resume and (specialty, bi) in done_keys:
                print(f"  批 {bi} 已 SUCCESS，--resume 跳过")
                continue
            started = datetime.now()
            run = api("POST", "/api/batch/run", token, body={
                "scopeIds": chunk, "years": YEARS,
                "versionTag": f"TASK-18 真实数据首跑-{specialty}"})
            if run["code"] != 0:
                print(f"  批 {bi} 发起失败: {run.get('msg')}")
                append_run_log({"specialty": specialty, "batchIndex": bi, "status": "LAUNCH_FAILED",
                                "msg": run.get("msg"), "startedAt": started.isoformat()})
                continue
            batch_id = run["data"]["batchId"]
            batch_no = run["data"].get("batchNo")
            print(f"  批 {bi}: batchId={batch_id} 实例 {len(chunk)} 个，开始轮询…", flush=True)
            status, stats, elapsed = poll_batch(token, batch_id)
            append_run_log({
                "specialty": specialty, "batchIndex": bi, "batchId": batch_id, "batchNo": batch_no,
                "scopeIds": chunk, "scopeCount": len(chunk), "years": YEARS,
                "stats": stats, "elapsedSec": elapsed, "status": status,
                "startedAt": started.isoformat(),
                "endedAt": datetime.now().isoformat(),
                "versionTag": f"TASK-18 真实数据首跑-{specialty}",
            })
            print(f"  批 {bi} 结束: {status} 用时 {elapsed}s（已写 run_log.json）", flush=True)

    print("本轮跑批流程结束")


if __name__ == "__main__":
    main()
