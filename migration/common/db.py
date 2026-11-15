# -*- coding: utf-8 -*-
"""TASK-04 公共数据库连接与幂等 upsert 工具（04 §4.6 / TASK-04 步骤 4）。

连接参数一律来自 deploy/.env（python-dotenv），任何异常输出不得包含密码。
"""
import os
import sys

from dotenv import load_dotenv
import psycopg
from psycopg.types.json import Json

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
load_dotenv(os.path.join(ROOT, "deploy", ".env"))

DB_PARAMS = {
    "host": "127.0.0.1",
    "port": os.getenv("EVAL_PG_PORT", "15432"),
    "dbname": os.getenv("POSTGRES_DB", "hospital_eval"),
    "user": os.getenv("POSTGRES_USER", "eval_admin"),
    "password": os.getenv("POSTGRES_PASSWORD", ""),
}

_json_tables = {"orig_values_json", "extra_atoms_json", "atom_values_json", "detail_json"}


def get_conn():
    """建立 PostgreSQL 连接（参数来自 deploy/.env，缺密码直接报错退出）。"""
    if not DB_PARAMS["password"]:
        print("ERROR: deploy/.env 缺少 POSTGRES_PASSWORD")
        sys.exit(2)
    try:
        return psycopg.connect(**DB_PARAMS, connect_timeout=10)
    except psycopg.OperationalError as e:
        # 只打印异常摘要，绝不打印连接串/密码
        print(f"ERROR: 数据库连接失败: {type(e).__name__}")
        sys.exit(2)


def upsert_many(conn, table, rows, conflict_cols, update_cols):
    """批量 INSERT ... ON CONFLICT ... DO UPDATE（TASK-04 步骤 4）。

    rows: list[dict]；JSONB 列（*json* 结尾）自动用 psycopg Json 包装。
    返回写入行数。
    """
    if not rows:
        return 0
    cols = list(rows[0].keys())
    placeholders = []
    for c in cols:
        placeholders.append("%s::jsonb" if c.endswith("_json") else "%s")
    updates = ", ".join(f"{c} = EXCLUDED.{c}" for c in update_cols) or "id = id"
    sql = (
        f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join(placeholders)}) "
        f"ON CONFLICT ({', '.join(conflict_cols)}) DO UPDATE SET {updates}"
    )
    data = []
    for r in rows:
        row = []
        for c in cols:
            v = r.get(c)
            if c.endswith("_json"):
                v = Json(v)
            row.append(v)
        data.append(tuple(row))
    with conn.cursor() as cur:
        cur.executemany(sql, data)
    return len(data)
