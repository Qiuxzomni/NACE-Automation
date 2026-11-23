# Prompt for Hermes — install the app analytics service on the QNAP

Copy everything below the line and give it to Hermes.

---

You have remote access to my QNAP NAS on the home LAN. Install a small, self-hosted analytics service that counts **total installs** and **active users** for my Android apps (first app: NACE Automation, `app_id` = `nace`). Work carefully, change nothing unrelated, and report back at the end.

## Ground rules
- The NAS is at **192.168.50.111** or **192.168.50.222**. Check both, find out which one runs **Container Station** (Docker), and use that one. If both do, or neither does, **stop and ask me**.
- Don't open or forward any router ports.
- Don't expose the dashboard (port 8081) to the internet. Only the endpoint `/v1/ping` may be public.
- Never print secrets (tunnel token) in your report. You may show me the `APP_KEY`; it gets compiled into the app anyway.
- If any step fails, stop and tell me what failed and the exact error, instead of improvising a different design.

## Step 1 — Check the NAS
1. SSH in, confirm `docker` and `docker compose` (or `docker-compose`) work, and note their versions.
2. Note free RAM and free disk space on the volume that holds `/share/Container`.
3. Check that ports **8080** and **8081** are free (`netstat -tlnp` or `ss -tlnp`). If either is taken, ask me before you pick other ports.

## Step 2 — Create the project
Create `/share/Container/app-analytics/` and put the files below in it **exactly as given**. Then:

```bash
cd /share/Container/app-analytics
mkdir -p data && chown 10001 data
cp .env.example .env
sed -i "s/^APP_KEY=.*/APP_KEY=$(openssl rand -hex 16)/" .env
chmod 600 .env
```

### `Dockerfile`

````dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py dashboard.html ./
RUN useradd -r -u 10001 app && mkdir /data && chown app /data
USER app
````

### `requirements.txt`

````text
fastapi==0.115.6
uvicorn==0.34.0
````

### `docker-compose.yml`

````yaml
services:
  ingest:
    build: .
    command: uvicorn main:ingest_app --host 0.0.0.0 --port 8080 --no-access-log
    env_file: .env
    volumes: ["./data:/data"]
    ports: ["127.0.0.1:8080:8080"]   # public only through the tunnel
    mem_limit: 128m
    restart: unless-stopped

  dashboard:
    build: .
    command: uvicorn main:dash_app --host 0.0.0.0 --port 8081 --no-access-log
    env_file: .env
    volumes: ["./data:/data"]
    ports: ["8081:8081"]             # LAN / Tailscale only — never tunnel this
    mem_limit: 128m
    restart: unless-stopped

  # Option A only (Cloudflare Tunnel). Remove this service if using Tailscale Funnel.
  cloudflared:
    image: cloudflare/cloudflared:2024.12.2
    command: tunnel --no-autoupdate run
    environment: ["TUNNEL_TOKEN=${CF_TUNNEL_TOKEN}"]
    depends_on: [ingest]
    mem_limit: 64m
    restart: unless-stopped
````

### `.env.example`

````bash
APP_KEY=replace-with-openssl-rand-hex-16
DB_PATH=/data/analytics.db
MAX_NEW_PER_HOUR=500
CF_TUNNEL_TOKEN=
````

### `main.py`

````python
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
````

### `dashboard.html`

````html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>App Analytics</title>
<style>
  :root { --bg:#f6f7f9; --card:#fff; --text:#16181d; --muted:#6b7280; --line:#e5e7eb; --accent:#2563eb; --accent2:#10b981; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#0f1115; --card:#181b21; --text:#e8eaed; --muted:#9aa0a6; --line:#2a2e36; --accent:#60a5fa; --accent2:#34d399; }
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:15px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
  main { max-width:1080px; margin:0 auto; padding:24px 16px 48px; }
  header { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom:20px; }
  h1 { font-size:20px; margin:0; font-weight:650; }
  select { background:var(--card); color:var(--text); border:1px solid var(--line); border-radius:8px; padding:6px 10px; font:inherit; }
  .tiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:12px; margin-bottom:12px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:16px; }
  .label { color:var(--muted); font-size:13px; }
  .value { font-size:32px; font-weight:700; margin-top:4px; font-variant-numeric:tabular-nums; }
  .grid { display:grid; grid-template-columns:2fr 1fr; gap:12px; }
  @media (max-width:760px) { .grid { grid-template-columns:1fr; } }
  h2 { font-size:14px; margin:0 0 12px; font-weight:600; }
  .chart { width:100%; height:240px; display:block; overflow:visible; }
  .legend { display:flex; gap:16px; font-size:12px; color:var(--muted); margin-bottom:8px; }
  .legend i { display:inline-block; width:10px; height:10px; border-radius:2px; margin-right:6px; vertical-align:-1px; }
  .axis { display:flex; justify-content:space-between; font-size:12px; color:var(--muted); margin-top:6px; }
  .bars div { display:flex; justify-content:space-between; font-size:13px; padding:6px 0; border-bottom:1px solid var(--line); }
  .bars div:last-child { border-bottom:0; }
  .muted { color:var(--muted); }
</style>
</head>
<body>
<main>
  <header>
    <h1>App Analytics</h1>
    <select id="app"></select>
  </header>
  <section class="tiles">
    <div class="card"><div class="label">Total installs</div><div class="value" id="total">–</div></div>
    <div class="card"><div class="label">Active today</div><div class="value" id="active1">–</div></div>
    <div class="card"><div class="label">Active 7 days</div><div class="value" id="active7">–</div></div>
    <div class="card"><div class="label">Active 30 days</div><div class="value" id="active30">–</div></div>
  </section>
  <section class="grid">
    <div class="card"><h2>Last 30 days</h2><div class="legend"><span><i style="background:var(--accent)"></i>Daily active</span><span><i style="background:var(--accent2)"></i>New installs</span></div><svg id="trend" class="chart" viewBox="0 0 640 260" preserveAspectRatio="none"></svg><div class="axis"><span id="x0"></span><span id="x1"></span></div></div>
    <div>
      <div class="card" style="margin-bottom:12px"><h2>Versions <span class="muted">(active 30d)</span></h2><div class="bars" id="versions"></div></div>
      <div class="card"><h2>Languages <span class="muted">(active 30d)</span></h2><div class="bars" id="langs"></div></div>
    </div>
  </section>
</main>
<script>
const rows = (el, items, key) => {
  el.innerHTML = items.length ? items.map(i => `<div><span>${i[key] ?? "?"}</span><b>${i.n}</b></div>`).join("")
                              : `<div class="muted">No data yet</div>`;
};
async function load(app) {
  const s = await (await fetch(`/api/stats?app_id=${encodeURIComponent(app)}`)).json();
  for (const k of ["total","active1","active7","active30"]) document.getElementById(k).textContent = s[k].toLocaleString();
  rows(document.getElementById("versions"), s.versions, "version");
  rows(document.getElementById("langs"), s.langs, "lang");
  const svg = document.getElementById("trend"), W = 640, H = 250, n = s.labels.length;
  const max = Math.max(1, ...s.dau, ...s.new), y = v => H - v / max * (H - 10), bw = W / n;
  let g = "";
  for (let i = 1; i <= 4; i++) g += `<line x1="0" x2="${W}" y1="${y(max*i/4)}" y2="${y(max*i/4)}" stroke="var(--line)" vector-effect="non-scaling-stroke"/>`;
  s.new.forEach((v, i) => { if (v) g += `<rect x="${i*bw+bw*.2}" y="${y(v)}" width="${bw*.6}" height="${H-y(v)}" rx="2" fill="var(--accent2)"><title>${s.labels[i]}: ${v} new</title></rect>`; });
  g += `<polyline fill="none" stroke="var(--accent)" stroke-width="2.5" stroke-linejoin="round" vector-effect="non-scaling-stroke" points="${s.dau.map((v,i)=>`${i*bw+bw/2},${y(v)}`).join(" ")}"/>`;
  g += `<text x="0" y="${y(max)-4}" font-size="11" fill="var(--muted)">${max}</text>`;
  svg.innerHTML = g;
  document.getElementById("x0").textContent = s.labels[0];
  document.getElementById("x1").textContent = s.labels[n-1];
}
(async () => {
  const apps = await (await fetch("/api/apps")).json();
  const sel = document.getElementById("app");
  sel.innerHTML = apps.length ? apps.map(a => `<option>${a}</option>`).join("") : "<option>nace</option>";
  sel.onchange = () => load(sel.value);
  load(sel.value);
})();
</script>
</body>
</html>
````

## Step 3 — Public endpoint (choose one, ask me which)

**Option A — Cloudflare Tunnel** (needs a domain on my Cloudflare account):
1. Ask me to create a tunnel in the Cloudflare Zero Trust dashboard and paste you the **tunnel token**. Put it in `.env` as `CF_TUNNEL_TOKEN=`.
2. In that tunnel, add a public hostname such as `stats.<mydomain>`. Set **Path** to `^/v1/ping$` and **Service** to `http://ingest:8080`.
3. Ask me to add a Cloudflare **rate-limiting rule** on that hostname, for example 30 requests per minute per IP.

**Option B — Tailscale Funnel** (no domain needed; Tailscale must already be installed on the NAS):
1. Delete the `cloudflared` service from `docker-compose.yml`, and the `CF_TUNNEL_TOKEN` line from `.env`.
2. Run `tailscale funnel --bg --set-path /v1/ping http://127.0.0.1:8080/v1/ping`. If the installed Tailscale version uses different syntax, check `tailscale funnel --help`, but expose **only** that path.
3. The public URL will be `https://<nas-name>.<tailnet>.ts.net/v1/ping`.

## Step 4 — Start and test
```bash
cd /share/Container/app-analytics
docker compose up -d --build
docker compose ps
```

Then run these tests and report each HTTP status code:

```bash
KEY=$(grep ^APP_KEY .env | cut -d= -f2)
# 1. Local ping -> expect 204
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:8080/v1/ping \
  -H "Content-Type: application/json" -H "X-App-Key: $KEY" \
  -d '{"app_id":"test","install_id":"00000000-0000-4000-8000-000000000000","version":"0.0.1","sdk":34,"lang":"en"}'
# 2. Wrong key -> expect 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:8080/v1/ping \
  -H "Content-Type: application/json" -H "X-App-Key: wrong" \
  -d '{"app_id":"test","install_id":"00000000-0000-4000-8000-000000000000","version":"0.0.1","sdk":34,"lang":"en"}'
# 3. Dashboard on LAN -> expect 200
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8081/
```

4. Repeat test 1 against the **public URL** and expect 204.
5. Request `<public URL without /v1/ping>/` and `.../api/stats?app_id=test` from outside. Both must **not** return dashboard data; anything except a 200 with data is fine.
6. Delete the test row:

```bash
docker compose exec ingest python -c "import sqlite3;c=sqlite3.connect('/data/analytics.db');c.execute(\"DELETE FROM installs WHERE app_id='test'\");c.execute(\"DELETE FROM pings WHERE app_id='test'\");c.commit()"
```

## Step 5 — Survive reboots and keep backups
- Make sure the containers come back after a NAS reboot. Set `restart: unless-stopped` (already in the file) and enable Docker autostart in Container Station.
- Add `/share/Container/app-analytics/data` to an existing **Hybrid Backup Sync** job if there is one. Otherwise tell me none exists.

## Step 6 — Report back to me
- Which NAS IP you used, and the Docker and Compose versions.
- The **public ping URL**.
- The **APP_KEY**.
- The dashboard URL on the LAN (`http://<nas-ip>:8081`) and through Tailscale (if installed).
- Results of every test in Step 4.
- RAM used by the containers (`docker stats --no-stream`).
- Anything you skipped or changed, and why.
