# Installing WikiKT

The recommended installation path uses **Docker Compose** running the app, PostgreSQL, and Caddy
(automatic HTTPS). This should deploy the same in any supporrted environment, including a Linux VM
(or bare metal) with Docker works, a DigitalOcean Droplet, an Amazon EC2 instance,
a Google Compute Engine VM, or a home server (although non-Internet-facing servers may prefer a simpler option;
see other Docker Compose example files for more). This guide covers the cloud options first (the only provider-specific parts are in
steps 1 and 2) then covers running without Docker.

Minimum size: **1 vCPU / 1 GB RAM** works for small wikis; 2 GB is suggested for most deployments for the
JVM plus PostgreSQL. Disk requirements vary based on wiki size, and note that size grows with uploads and revision
history if enabled.

## Option A — Cloud VM with Docker Compose (recommended)

### 1. Create the VM

- **DigitalOcean**: Create a Droplet with Ubuntu LTS, Basic plan (1–2 GB). Under *Networking*,
  allow inbound **22, 80, 443** (Cloud Firewall or leave the default open Droplet).
- **Amazon EC2**: Launch an instance with Ubuntu LTS, `t3.small` (or `t3.micro` to start). In the
  security group, allow inbound **22 (your IP), 80, 443 (anywhere)**.
- **Google Compute Engine**: Create a VM with Ubuntu LTS, `e2-small`. Tick *Allow HTTP/HTTPS
  traffic* (or add firewall rules for 80/443).

### 2. Point DNS at it

Create an `A` record for your wiki's hostname and point it at the VM's public IP:

```
A   wiki.example.com   203.0.113.10
```

Do this before first start, as Caddy needs the name to resolve to obtain the HTTPS certificate.

### 3. Install Docker

SSH in and run Docker's official convenience script (or manually instal):

```bash
curl -fsSL https://get.docker.com | sudo sh
```

### 4. Obtain WikiKT and configure

```bash
git clone <your-wikikt-repo-url> wikikt && cd wikikt
cat > .env <<EOF
WIKIKT_DOMAIN=wiki.example.com
POSTGRES_PASSWORD=$(openssl rand -hex 24)
WIKIKT_SESSION_ENCRYPTION_KEY=$(openssl rand -hex 16)
WIKIKT_SESSION_SIGN_KEY=$(openssl rand -hex 32)
WIKIKT_ADMIN_PASSWORD=<choose-a-strong-admin-password>
EOF
chmod 600 .env
```

### 5. Start

```bash
sudo docker compose -f docker-compose.prod.yml up -d --build
```

The first build takes a few minutes. Then open `https://wiki.example.com` and log in as `admin`
with the password from `.env`. See [docker/README.md](../docker/README.md) for upgrades, logs,
and backup practice.

### Notes per provider

- All three providers also offer managed PostgreSQL (DO Managed Databases, RDS, Cloud SQL). To
  use one instead of the bundled container: remove the `postgres` service from the Compose file
  and set `WIKIKT_DATABASE_R2DBC_URL`, `WIKIKT_DATABASE_USERNAME`, and `WIKIKT_DATABASE_PASSWORD`
  to the appropriate values for the managed instance.
- Snapshots/AMIs of the VM (plus `pg_dump`) make good infrastructure backups, or **Administration |
  Backup** in WikiKT covers the application layer, and the Git Sync (push mode or bidirectional) option
  in Administration settings can keep an off-site content mirror.

## Option B — Self-hosted without Docker

Requirements: **JDK 21** and **git** (git is necessary only if using Git Sync option in Administration settings).

```bash
./gradlew shadowJar
java -jar build/libs/wikikt-all.jar
```

That runs on H2 at `http://0.0.0.0:8080` with data under `./data/`. For production set the
environment first (same variables as Docker):

```bash
export WIKIKT_ENV=production
export WIKIKT_ADMIN_PASSWORD=...
export WIKIKT_SESSION_ENCRYPTION_KEY=$(openssl rand -hex 16)
export WIKIKT_SESSION_SIGN_KEY=$(openssl rand -hex 32)
export WIKIKT_MFA_KEY=$(openssl rand -hex 32)
# Optional PostgreSQL instead of H2:
export WIKIKT_DATABASE_TYPE=postgres
export WIKIKT_DATABASE_R2DBC_URL=r2dbc:postgresql://localhost:5432/wikikt
export WIKIKT_DATABASE_USERNAME=wikikt
export WIKIKT_DATABASE_PASSWORD=...
# When served behind nginx/Caddy with TLS:
export WIKIKT_TRUST_PROXY=true
export WIKIKT_SESSION_SECURE_COOKIE=true
# Canonical public URL used to build links in outbound email (password reset, welcome):
export WIKIKT_PUBLIC_URL=https://wiki.example.com
```

A minimal systemd unit:

```ini
[Unit]
Description=WikiKT
After=network.target postgresql.service

[Service]
User=wikikt
WorkingDirectory=/opt/wikikt
EnvironmentFile=/opt/wikikt/wikikt.env
ExecStart=/usr/bin/java -jar /opt/wikikt/wikikt-all.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Add TLS with any reverse proxy (Caddy config in [docker/Caddyfile](../docker/Caddyfile)
works standalone too -- replace `wikikt:8080` with `localhost:8080`).

## Multiple sites on one instance

One WikiKT instance can host several independent sites (each with its own pages, assets, navigation,
fragments, and settings; users and groups are shared). A request is routed to a site by its hostname,
and one site is the catch-all that serves any host no other site claims.

1. **Create the sites.** As an admin, go to **Administration → Sites → New site**. Give each site a name
   and the hostname it answers to (e.g. `wiki.example.com`). Leave the catch-all flag on exactly one
   site — it handles bare IPs, health checks, and any domain you haven't mapped. Use **Manage** (or the
   switcher at the top of the admin sidebar) to choose which site the admin pages act on.

2. **Point DNS at the instance.** Add an `A`/`AAAA` (or `CNAME`) record for every hostname → the same
   server. All of them terminate at one WikiKT process.

3. **Make sure the proxy forwards the original `Host` header.** WikiKT resolves the site from the request
   host, so the domain the visitor typed must reach the app:
   - **Caddy and nginx forward `Host` by default**, so you have nothing to do. The bundled
     [docker/Caddyfile](../docker/Caddyfile) works; add each hostname to its site address (or use a wildcard)
     so Caddy provisions TLS for it.
   - If your proxy instead rewrites `Host` and sends the real host in `X-Forwarded-Host`, set
     **`WIKIKT_TRUST_PROXY=true`** so WikiKT reads the forwarded host. Only enable this when the app is
     actually behind a trusted proxy — it also makes the login rate-limit trust `X-Forwarded-For`, which a
     directly-exposed server must not do.

Example nginx that preserves the host (one server block can serve every site since routing happens in
WikiKT):

```nginx
server {
    server_name docs.example.com wiki.example.com;   # all sites' hostnames
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;                  # required: original host reaches WikiKT
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

To back up every site at once, use the **full** backup (Administration | Storage and backup); the
**content** backup covers only the site you're currently managing. Deleting a site removes all of its
content — see the confirmation prompt.

## Environment variable reference

| Variable | Purpose |
|----------|---------|
| `WIKIKT_ENV` | `development` (default) or `production`. Production refuses insecure defaults. |
| `WIKIKT_ADMIN_PASSWORD` | Initial `admin` password (default `changeme` is fatal in production). |
| `WIKIKT_SESSION_ENCRYPTION_KEY` | Session cookie encryption key — hex, `openssl rand -hex 16`. |
| `WIKIKT_SESSION_SIGN_KEY` | Session cookie signing key — hex, `openssl rand -hex 32`. |
| `WIKIKT_MFA_KEY` | AES key (hex, `openssl rand -hex 32`) encrypting stored two-factor (TOTP) secrets at rest. A dedicated key, separate from the session keys; required (fatal if unset) in production. |
| `WIKIKT_SESSION_SECURE_COOKIE` | `true` when served over HTTPS. |
| `WIKIKT_SESSION_MAX_AGE_SECONDS` | Session lifetime (default 1296000 = 15 days). |
| `WIKIKT_TRUST_PROXY` | `true` ONLY behind a reverse proxy (honors `X-Forwarded-*`). |
| `WIKIKT_PUBLIC_URL` | Canonical base URL for links in outbound email (password reset, welcome), e.g. `https://wiki.example.com`. Set on any internet-facing install so email links use your real host rather than a client-supplied `Host` header. |
| `WIKIKT_DATABASE_TYPE` | `h2` (default) or `postgres`. |
| `WIKIKT_DATABASE_R2DBC_URL` | Connection URL, e.g. `r2dbc:postgresql://host:5432/wikikt`. |
| `WIKIKT_DATABASE_USERNAME` / `WIKIKT_DATABASE_PASSWORD` | Database credentials. |
| `WIKIKT_DATABASE_POOL_MAX_SIZE` | Max pooled connections the app will hold (default `10`). Keep it under the database server's own limit — PostgreSQL defaults to `max_connections: 100`. Raise it for a busy site; if several app instances share one database, budget the sum. |
| `WIKIKT_DATABASE_POOL_INITIAL_SIZE` | Connections opened at startup (default `2`). |
| `WIKIKT_DATABASE_POOL_MAX_IDLE_TIME` | Seconds an idle connection is kept before being closed (default `1800`). |
| `WIKIKT_DATABASE_POOL_MAX_LIFE_TIME` | Seconds before a connection is recycled regardless of use (default `3600`). |
| `WIKIKT_DATABASE_POOL_MAX_ACQUIRE_TIME` | Seconds to wait for a free connection when the pool is saturated before failing the request (default `10`) — fail fast rather than hang. |
| `WIKIKT_ASSET_STORAGE_DIR` | Upload storage dir (default `./data/uploads`). |
| `WIKIKT_GIT_SYNC_DIR` | Git-sync working clone dir (default `./data/git-sync`). |
| `WIKIKT_UI_ASSET_SOURCE` | `local` (default, bundled) or `cdn` for front-end assets. |

## Two-factor authentication (2FA)

WikiKT supports app-based two-factor authentication (TOTP — Google Authenticator, Aegis, 1Password, etc.).
Any user can enable it under **Account → Settings → Security**: scan the QR code (or enter the setup key
manually), confirm a 6-digit code, and save the one-time **recovery codes** shown once. After that, signing
in requires the password *and* a current code (or a recovery code).

**Recommended:** have every administrator enable 2FA. The `admin` account's username is well-known and it
holds full control, so a second factor is the highest-value hardening for an internet-facing instance.
There is currently no setting to *force* 2FA on a group — it is opt-in per user — so treat it as an
onboarding step for admins.

**Requirements & operations:**

- Set `WIKIKT_MFA_KEY` (a random 32-byte hex value) — it encrypts stored TOTP secrets and is required in
  production. Keep it backed up alongside your session keys; losing it makes existing 2FA enrolments
  unreadable (users would re-enrol).
- **Lost device recovery:** a user with their recovery codes can sign in with one. If they've lost both
  the device and the codes, an administrator can clear their 2FA from **Administration → Users → (edit
  user) → Reset two-factor authentication**, after which the user signs in with just their password and
  re-enrols. Resetting a *root* administrator's 2FA requires a root administrator.
- 2FA protects interactive login only; API keys are separate long-lived credentials (revoke a leaked key).
