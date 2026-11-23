import os
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

DB_PATH = os.environ.get("DB_PATH", "/data/analytics.db")
APP_KEY = os.environ.get("APP_KEY", "")
MAX_NEW_PER_HOUR = int(os.environ.get("MAX_NEW_PER_HOUR", "500"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS installs (
  app_id TEXT NOT NULL, install_id TEXT NOT NULL,
  first_seen TEXT NOT NULL, last_seen TEXT NOT NULL,
  version TEXT, sdk INTEGER, lang TEXT,
  PRIMARY KEY (app_id, install_id));
CREATE TABLE IF NOT EXISTS pings (
  app_id TEXT NOT NULL, install_id TEXT NOT NULL, day TEXT NOT NULL,
  PRIMARY KEY (app_id, install_id, day));
CREATE INDEX IF NOT EXISTS pings_day ON pings (app_id, day);
"""


def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.row_factory = sqlite3.Row
    return conn


Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
with db() as c:
    c.executescript(SCHEMA)


# ---------- ingest (the only part exposed publicly) ----------
class Ping(BaseModel):
    app_id: str = Field(pattern=r"^[a-z0-9._-]{1,40}$")
    install_id: str = Field(pattern=r"^[0-9a-fA-F-]{32,36}$")
    version: str = Field(max_length=20, pattern=r"^[0-9A-Za-z.+-]+$")
    sdk: int = Field(ge=1, le=99)
    lang: str = Field(max_length=10, pattern=r"^[A-Za-z-]+$")


ingest_app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
_new_window = {"hour": 0, "count": 0}


@ingest_app.post("/v1/ping", status_code=204)
def ping(p: Ping, x_app_key: str = Header(default="")):
    if not APP_KEY or x_app_key != APP_KEY:
        raise HTTPException(401)
    now = datetime.now(timezone.utc)
    ts, day = now.isoformat(timespec="seconds"), now.date().isoformat()
    with db() as c:
        exists = c.execute("SELECT 1 FROM installs WHERE app_id=? AND install_id=?",
                           (p.app_id, p.install_id)).fetchone()
        if not exists:
            hour = int(time.time() // 3600)
            if _new_window["hour"] != hour:
                _new_window.update(hour=hour, count=0)
            if _new_window["count"] >= MAX_NEW_PER_HOUR:
                raise HTTPException(429)
            _new_window["count"] += 1
        c.execute("""INSERT INTO installs VALUES (?,?,?,?,?,?,?)
                     ON CONFLICT(app_id, install_id) DO UPDATE SET
                     last_seen=excluded.last_seen, version=excluded.version,
                     sdk=excluded.sdk, lang=excluded.lang""",
                  (p.app_id, p.install_id, ts, ts, p.version, p.sdk, p.lang))
        c.execute("INSERT OR IGNORE INTO pings VALUES (?,?,?)", (p.app_id, p.install_id, day))


@ingest_app.get("/healthz")
def healthz():
    return {"ok": True}


# ---------- dashboard (LAN / Tailscale only, never tunneled) ----------
dash_app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


@dash_app.get("/api/apps")
def apps():
    with db() as c:
        return [r[0] for r in c.execute("SELECT DISTINCT app_id FROM installs ORDER BY 1")]


@dash_app.get("/api/stats")
def stats(app_id: str, days: int = 30):
    today = datetime.now(timezone.utc).date()
    start = (today - timedelta(days=days - 1)).isoformat()

    def active(n):
        since = (today - timedelta(days=n - 1)).isoformat()
        return c.execute("SELECT COUNT(DISTINCT install_id) FROM pings WHERE app_id=? AND day>=?",
                         (app_id, since)).fetchone()[0]

    with db() as c:
        total = c.execute("SELECT COUNT(*) FROM installs WHERE app_id=?", (app_id,)).fetchone()[0]
        new = dict(c.execute("""SELECT substr(first_seen,1,10) d, COUNT(*) FROM installs
                                WHERE app_id=? AND d>=? GROUP BY d""", (app_id, start)).fetchall())
        dau = dict(c.execute("""SELECT day, COUNT(*) FROM pings WHERE app_id=? AND day>=?
                                GROUP BY day""", (app_id, start)).fetchall())
        since30 = (today - timedelta(days=29)).isoformat()
        versions = [dict(r) for r in c.execute(
            """SELECT version, COUNT(*) n FROM installs WHERE app_id=? AND last_seen>=?
               GROUP BY version ORDER BY n DESC LIMIT 10""", (app_id, since30))]
        langs = [dict(r) for r in c.execute(
            """SELECT lang, COUNT(*) n FROM installs WHERE app_id=? AND last_seen>=?
               GROUP BY lang ORDER BY n DESC""", (app_id, since30))]
        a1, a7, a30 = active(1), active(7), active(30)
    labels = [(today - timedelta(days=days - 1 - i)).isoformat() for i in range(days)]
    return {"total": total, "active1": a1, "active7": a7, "active30": a30,
            "labels": labels, "new": [new.get(d, 0) for d in labels],
            "dau": [dau.get(d, 0) for d in labels], "versions": versions, "langs": langs}


@dash_app.get("/", response_class=HTMLResponse)
def index():
    return (Path(__file__).parent / "dashboard.html").read_text()
