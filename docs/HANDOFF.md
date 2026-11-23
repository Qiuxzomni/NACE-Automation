# Handoff: NACE install & active-user analytics on the QNAP

## What I asked for
1. Track **total installs** and **active installs** for my Android app NACE Automation (and later Ditto Link, Mekka Downloadr and my other apps). Keep it simple but beautiful.
2. Self-host it on my **QNAP NAS** (192.168.50.111 or 192.168.50.222): FastAPI + SQLite in Docker, a public endpoint for the phones, and a dashboard I can open only on my LAN / Tailscale.
3. Remember my QNAP addresses.
4. Get it installed on the QNAP.

## Decisions made
- Apps send **one heartbeat per day**, from the background (WorkManager), not on app-open. NACE runs in the background, so counting opens would undercount users.
- One endpoint: `POST /v1/ping` with `app_id`, a random `install_id` (UUID made on first launch), `version`, `sdk` and `lang`, plus an `X-App-Key` header.
  - Total installs = distinct IDs ever seen. Active = IDs seen in the last 1, 7 or 30 days.
- No retry queue: if a ping fails, the next day's ping covers it.
- Privacy: never send phone numbers, credentials, balances, trades or pairs. IPs are not stored. Add an "anonymous usage stats" on/off switch in Settings and a line in the EULA.
- Only `/v1/ping` is public, through **Cloudflare Tunnel or Tailscale Funnel** (I still have to pick one). The dashboard (port 8081) is never exposed. No router ports are opened.

## What's done
Everything is on branch `claude/zen-goldberg-w40go0` of `Qiuxzomni/NACE-Automation` (pushed):
- `CLAUDE.md`: QNAP addresses and project notes.
- `analytics-server/`: the service. `main.py` (ingest + dashboard), `dashboard.html`, `Dockerfile`, `docker-compose.yml`, `.env.example`.
  - Tested in Docker: valid ping 204, wrong key 401, dashboard 200, stats not reachable on the public port. About 31 MB RAM per container.
- `docs/hermes-qnap-analytics-setup.md`: the step-by-step install prompt with all code inline. It was written for my Hermes agent, but any agent with SSH to the NAS can follow it.

## Where we stopped
- **Nothing is installed on the QNAP yet.**
- The cloud session couldn't reach my PC. My Remote Control folders (`E:\AI`, `E:\AI\Skills\qnap-ssh-bridge`) appear but are "not available to cloud sessions", probably because the Windows Claude app needs updating.
- So the install continues in a **local** session on my PC, in `E:\AI\Skills\qnap-ssh-bridge`. That folder has the QNAP SSH bridge (`CLAUDE.md`, `nas_ssh.cmd`, which needs Python 3.13 for paramiko) and the global skill `qnap-nas-ssh`.

## Next steps
1. Fetch branch `claude/zen-goldberg-w40go0` of `Qiuxzomni/NACE-Automation`, or just read `docs/hermes-qnap-analytics-setup.md` from it.
2. Using the SSH bridge, follow that doc step by step:
   - Find which IP runs Container Station, and ask me if it's unclear.
   - Deploy to `/share/Container/app-analytics`.
   - **Ask me: Cloudflare Tunnel or Tailscale Funnel?**
   - Run the tests, set up reboot survival and backup.
   - Report the public ping URL and the `APP_KEY`.
3. After that: add the daily ping to the NACE Android app. That needs the app's source repo, which isn't in `NACE-Automation`; that repo only has the README.
