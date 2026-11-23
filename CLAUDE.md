# NACE Automation — project notes

## Infrastructure
- **QNAP NAS** on the home LAN: `192.168.50.111` and `192.168.50.222`.
  (Owner gave these as "111 and 222"; confirm which one hosts Container Station before deploying.)
- Remote changes on the QNAP are carried out by the owner's **Hermes** agent, which has remote access.
  Write instructions for Hermes rather than assuming direct access from a Claude session.

## Analytics
- Self-hosted install/active-user analytics lives in `analytics-server/` (FastAPI + SQLite, Docker).
- Setup instructions for Hermes: `docs/hermes-qnap-analytics-setup.md`.
- Apps send one daily heartbeat: `POST /v1/ping` with `app_id`, random `install_id`, `version`, `sdk`, `lang`.
  Never send phone numbers, credentials, balances, trades or pairs. IPs are not stored.
- Only `/v1/ping` is public (via tunnel). The dashboard (port 8081) stays on the LAN / Tailscale.
