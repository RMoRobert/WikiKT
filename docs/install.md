# Installing WikiKT

The recommended installation path uses **Docker Compose** running the app, PostgreSQL, and Caddy
(automatic HTTPS). This should deploy the same in any supported environment, including a Linux
VM (or bare metal) with Docker, a DigitalOcean Droplet, an Amazon EC2 instance, a Google Compute
Engine VM, or a home server (although non-Internet-facing servers may prefer a simpler option:
[`docker-compose.home.yml`](../docker-compose.home.yml) is the same stack without Caddy, for a LAN or
your own reverse proxy -- see [docker/README.md](../docker/README.md#home-behind-your-own-reverse-proxy)).
This guide covers the cloud options first (the only provider-specific parts are in steps 1 and 2) then
covers running without Docker.

**Sizing:**

*To run*: **1 vCPU / 1 GB RAM** works for small wikis; 2 GB is suggested for most deployments for the
JVM plus PostgreSQL. Disk requirements vary based on wiki size, and note that size grows with uploads and
revision history if enabled.

*To build (optional)*: the recommended prod Compose file pulls a **prebuilt image** from the GitHub
Container Registry, so the server never compiles anything and the *run* sizing above is all you need.
Building from source instead (`up -d --build`, the default in `docker-compose.home.yml`) requires
notably more resources: it compiles the Kotlin sources and assembles a ~35 MB self-contained JAR on
the server, running two JVMs (Gradle plus the Kotlin compiler), and wants **~4 GB RAM**, or 2 GB with
swap. Shared-core instance types (`e2-small`, `t3.micro`) may also run slowly if burst credits are all
consumed. See [Building elsewhere](#building-elsewhere) below for the options.

## Option A: Cloud VM with Docker Compose (recommended)

### 1. Create the VM

- **DigitalOcean**: Create a Droplet with Ubuntu LTS, Basic plan (1–2 GB). Under *Networking*,
  allow inbound **22, 80, 443** (Cloud Firewall or leave the default open Droplet).
- **Amazon EC2**: Launch an instance with Ubuntu LTS, `t3.small` to `c5.large` suggested (although
  `t3.micro` may work for small deployments). In the security group, allow 
   inbound **22 (your IP), 80, 443 (anywhere)**.
- **Google Compute Engine**: Create a VM with Ubuntu LTS, `e2-small` or higher recommended. 
   Check *Allow HTTP/HTTPS traffic* (or add firewall rules for 80/443).

The commands in this guide assume a **Debian-family image (Ubuntu LTS or Debian)**, which every
provider above offers and defaults you into `sudo` rights: DigitalOcean signs you in as `root`,
EC2's Ubuntu images as `ubuntu` (passwordless sudo), and GCE grants your login user sudo. Other
families (Amazon Linux, RHEL) can work but differ in package tooling and SELinux defaults and are
not covered here.

### 2. Configure DNS

Create a DNS record to point at your VM. In most cases, this means creating an `A` record for
your wiki's hostname and pointing it at the VM's public IP (or a `CNAME` record if your provider
offers a stable public hostname but not a static public IP). For example:

```
A   wiki.example.com   203.0.113.10
```

If you plan to use multiple sites on the same instance, be sure to create a DNS record for each (e.g.,
`site1.example.com` and `site2.example.com`).

Do this before first start, as Caddy needs the name to resolve to obtain the HTTPS certificate.

### 3. Install Docker

SSH into your VM and run Docker's official convenience script to install (or install manually if preferred):

```bash
curl -fsSL https://get.docker.com | sudo sh
```

### 4. Download WikiKT and configure

If you are using a custom fork or clone, replace `https://github.com/RMoRobert/WikiKT.git` below with the URL of
your fork (or switch to desired branch).

NOTE: `/opt/wikikt` below is the recommended install location (which does need `sudo`, but the 
`$(id -un)` line in the script below -- effectively "my username" -- hands ownership of this folder
to you, and the trailing colon sets your group). You *can* install in any directory, but some optional
features default to this path, notably the one-click updater and the `systemd` example in Option B.
If you must use a different location (e.g., no sudo available in your environment),
set `WIKIKT_COMPOSE_DIR` in `.env` (unless you plan to perform manual installation and updates only).

The Docker stack is configured by a `.env` file sitting next to the Compose file (or however you prefer to set
the same environment variables). The repo ships [`.env.example`](../.env.example) as an annotated template of the
supported variables, which you may copy (`cp .env.example .env`) and fill in as desired. Alternatively, pasting
the below into the shell will create this file with appropriate values in one step for you:

```bash
sudo git clone https://github.com/RMoRobert/WikiKT.git /opt/wikikt
sudo chown -R "$(id -un):" /opt/wikikt
cd /opt/wikikt
cat > .env <<EOF
WIKIKT_DOMAIN=wiki.example.com
POSTGRES_PASSWORD=$(openssl rand -hex 24)
WIKIKT_SESSION_ENCRYPTION_KEY=$(openssl rand -hex 16)
WIKIKT_SESSION_SIGN_KEY=$(openssl rand -hex 32)
WIKIKT_MFA_KEY=$(openssl rand -hex 32)
WIKIKT_ADMIN_PASSWORD=$(openssl rand -base64 18)
COMPOSE_PROFILES=selfupdate
EOF
chmod 600 .env
grep WIKIKT_ADMIN_PASSWORD .env   # generated admin password -- SAVE THIS for first login!
```

The `COMPOSE_PROFILES=selfupdate` line enables the updater container that powers one-click updates
from **Administration > Updates** (with a pre-update database dump, a health check on the new
version, and automatic rollback) -- the default for this stack. It holds the Docker socket, so if
you prefer a no-socket or offline-leaning deployment, simply omit that line; everything else works
the same and the Updates page shows manual upgrade commands instead. Details and trust note:
[One-click updates](../docker/README.md#one-click-updates-optional-updater-container).

The admin password (and required keys) are generated randomly and then printed to screen at the end, which **you must
note for your first login**. Change to something of your own choosing in the WikiKIT web UI
under (account menu) → **Profile → Security** (or set explicitly in environment if preferred beforehand;
single-quote if set this or any secret manually to avoid `$` interpolation or similar problems).

If using your own version control, be sure to exclude your real `.env` file from it given the secrets
it contains (the official repo's `.gitignore` already excludes it).

Anything from the [environment variable reference](#environment-variable-reference) below can go in the
same file, not just the variables the template lists -- the Compose file passes `.env` through to the app.

The exception is the handful of settings the Compose file pins itself (production mode, the proxy/cookie
posture, and the database wiring): those deliberately win over `.env`, so a stray `WIKIKT_ENV=development`
there cannot quietly downgrade a production deployment. To change one of those, edit the `wikikt` service's
`environment:` block in `docker-compose.prod.yml`.

If hosting multiple sites on the same instance, add a line listing the extra hostnames, space-separated
(the primary `WIKIKT_DOMAIN` does not need to be repeated here), as mentioned above:

```bash
WIKIKT_EXTRA_DOMAINS="site1.example.com site2.example.com"
```

Quotes here are optional (Compose strips them). Note that this variable configures **Caddy**,
not WikiKT: it is the list of hostnames Caddy obtains certificates for and routes, which is separate from
creating the sites themselves. See [Multiple sites on one instance](#multiple-sites-on-one-instance) for
the rest of what that needs.

### 5. Start

While still in `/opt/wikikt`, run:

```bash
sudo docker compose -f docker-compose.prod.yml up -d
```

First start pulls the prebuilt app image from the GitHub Container Registry (to compile from source on
the server instead, see [Building elsewhere](#building-elsewhere), then start with `--build`). Then
open `https://wiki.example.com` and log in as `admin` with the password from `.env`. See
[docker/README.md](../docker/README.md) for upgrades, logs, and backup practice.

### Updating

WikiKT can tell you when a new release is out: as a root admin, open **Administration > Updates** and
enable update checks (opt-in; at most one anonymous request a day to `api.github.com`, nothing about
your instance is sent). Once enabled, the Administration dashboard also shows an "update available"
link when there is one, re-checked at most weekly in the background. Nothing is requested until you
enable it, there is no background poller, and the same page turns it off again -- so an offline or
air-gapped install simply leaves it disabled.

With the `.env` from step 4 (`COMPOSE_PROFILES=selfupdate`), installing an update is then the
**Install update** button on that same page — it takes a database backup, pulls, restarts, verifies
the new version is healthy, and rolls back automatically if not. Without the updater (the line
omitted), installing is a manual pull and restart:

```bash
sudo docker compose -f docker-compose.prod.yml pull wikikt
sudo docker compose -f docker-compose.prod.yml up -d
```

If you build from source, `git pull` then rebuild with `--build` instead -- see the
[Upgrades](../docker/README.md#upgrades) section of docker/README.md. Volumes (database, uploads,
certificates) persist, and schema migrations run automatically at startup; taking a backup first
(**Administration > Storage and backup**) is always a good idea.

The updater is its own container holding the Docker socket (the app never gets it) — the trust
model, how to disable it, and how to add it to a `docker-compose.home.yml` deployment running the
GHCR image are all in
[One-click updates](../docker/README.md#one-click-updates-optional-updater-container).

### Building elsewhere

*(Only relevant if you build from source rather than pulling the prebuilt image.)*
On a VM sized for *running* WikiKT, `--build` may appear to hang partway through
`RUN ./gradlew --no-daemon shadowJar`, typically around `shadowJar` itself, which is the memory-hungriest
step. It is usually still making progress, just very slowly. If build appears stuck, you can run something
like this to confirm which limit you are hitting:

```bash
free -h; vmstat 1 5; sudo dmesg -T | grep -iE 'oom|killed process' | tail
```

Nonzero `si`/`so` in `vmstat` means it is swapping, a high `st` column means the hypervisor is throttling
you, and any `dmesg` OOM line means a JVM was killed outright. Cloud images generally ship with **no swap**,
which turns a memory shortfall into a stall rather than a clean failure. Any of these fixes work:

- **Add swap**: the least invasive fix on a 2 GB instance:
  ```bash
  sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  ```
  Add to `/etc/fstab` to survive a reboot.
- **Cap the build's memory** so the two JVMs stop over-committing. Before building, append to
  `gradle.properties`:
  ```bash
  printf 'org.gradle.jvmargs=-Xmx1200m\nkotlin.compiler.execution.strategy=in-process\norg.gradle.workers.max=1\n' >> gradle.properties
  ```
  `in-process` is the important part: it compiles Kotlin inside the Gradle JVM instead of starting a second one.
- **Resize the VM for the build only**, then scale back down: stop the instance, change the machine type
  (e.g. to one with 4 GB and a full vCPU), start it, build, and reverse it afterwards.
- **Don't build on the server at all**: the most reliable option for a small instance, in either of two
  forms. Pulling the ready-made image from the GitHub Container Registry is what
  `docker-compose.prod.yml` already does by default -- so if you switched it to `build: .`, you can switch
  it back (see [Pull a prebuilt image from GHCR](../docker/README.md#pull-a-prebuilt-image-from-ghcr));
  or, if you build your own fork, build on a workstation and copy the image over with `docker save`/`docker
  load` (see [Run from a prebuilt image](../docker/README.md#run-from-a-prebuilt-image-no-registry)).
  Neither needs a JDK, Gradle, or build memory on the VM.

### Notes per provider

- All three providers also offer managed PostgreSQL (DO Managed Databases, RDS, Cloud SQL). To
  use one instead of the bundled container: remove the `postgres` service from the Compose file
  and set `WIKIKT_DATABASE_R2DBC_URL`, `WIKIKT_DATABASE_USERNAME`, and `WIKIKT_DATABASE_PASSWORD`
  to the appropriate values for the managed instance.
- Snapshots/AMIs of the VM (plus `pg_dump`) make good infrastructure backups, or **Administration |
  Storage and backup** in WikiKT covers the application layer, and the Git Sync (push mode or bidirectional) option
  in Administration settings can keep an off-site content mirror.

## Option B: Self-hosted without Docker

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

There is no `.env` file in this mode; the variables above are plain environment variables. Under
systemd, put the same `KEY=value` lines in the unit's `EnvironmentFile` (below) rather than exporting
them by hand, and `chmod 600` that file since it holds your secrets. The full list of what you can set
is in the [environment variable reference](#environment-variable-reference).

A minimal systemd unit example:

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

1. **Create the sites.** As an admin, go to **Administration | Sites | New site**. Give each site a name
   and the hostname it answers to (e.g. `wiki.example.com`). Leave the catch-all flag on exactly one
   site -- it handles bare IPs, health checks, and any domain you haven't mapped. Use **Manage** (or the
   switcher at the top of the admin sidebar) to choose which site the admin pages act on.

   **Give the first site a hostname too.** A new install starts with one site ("Main site") that is the
   catch-all with its hostname left blank -- it serves every request, so nothing forces you to fill that
   field in. Set it to the domain that site actually answers to. Naming it changes nothing about routing
   (every unclaimed host still falls through to the catch-all), but the admin console only follows you to
   another site's address when the address you're on belongs to a site: with the field blank the site
   switcher silently stays on the current domain instead of moving to the one you picked.

2. **Point DNS at the instance.** Add an `A`/`AAAA` (or `CNAME`) record for every hostname → the same
   server. All of them terminate at one WikiKT process.

3. **Make sure the proxy forwards the original `Host` header.** WikiKT resolves the site from the request
   host, so the domain the visitor typed must reach the app:
   - **Caddy and nginx forward `Host` by default**, so you have nothing to do. On the bundled Compose
     stack, list the extra hostnames in `WIKIKT_EXTRA_DOMAINS` in your `.env` and re-run
     `docker compose -f docker-compose.prod.yml up -d` so Caddy provisions TLS for each one — adding a
     site in the admin console alone leaves it without a certificate. (Using the
     [Caddyfile](../docker/Caddyfile) standalone, add each hostname to its site address instead.)
   - If your proxy instead rewrites `Host` and sends the real host in `X-Forwarded-Host`, set
     **`WIKIKT_TRUST_PROXY=true`** so WikiKT reads the forwarded host. Only enable this when the app is
     actually behind a trusted proxy -- it also makes the login rate-limit trust `X-Forwarded-For`, which a
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

To back up every site at once, use the *full* backup (Administration | Storage and backup); the
*content* backup covers only the site you're currently managing. Deleting a site removes all of its
content, as noted in the confirmation prompt.

## Environment variable reference

These are WikiKT's own settings: each one overrides the matching `wikikt.*` key in `application.yaml`, and
each works in any deployment style: Docker `.env`, a systemd `EnvironmentFile`, or plain `export`.

| Variable | Purpose                                                                                                                                                                                                                                           |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `WIKIKT_ENV` | `development` (default) or `production`. Production refuses insecure defaults and is recommended for such deployments.                                                                                                                            |
| `WIKIKT_ADMIN_PASSWORD` | Initial `admin` password (development default is `changeme`, fatal error in production).                                                                                                                                                          |
| `WIKIKT_MIN_PASSWORD_LENGTH` | Minimum length for user-chosen passwords (self-registration, reset, self-service change, and admin-set). Default `5`; raise it (e.g. `12`+) on a public-facing deployment. Capped at 72 (the bcrypt byte limit). |
| `WIKIKT_SESSION_ENCRYPTION_KEY` | Session cookie encryption key - hex, e.g., `openssl rand -hex 16`.                                                                                                                                                                                |
| `WIKIKT_SESSION_SIGN_KEY` | Session cookie signing key - hex, e.g., `openssl rand -hex 32`.                                                                                                                                                                                   |
| `WIKIKT_MFA_KEY` | AES key (hex, e.g., `openssl rand -hex 32`) encrypting stored two-factor (TOTP) secrets at rest. Required (fatal if unset) in production.                                                                                                         |
| `WIKIKT_SESSION_SECURE_COOKIE` | `true` when served over HTTPS.                                                                                                                                                                                                                    |
| `WIKIKT_SESSION_MAX_AGE_SECONDS` | Session lifetime (default 604800 = 7 days).                                                                                                                                                                                                     |
| `WIKIKT_TRUST_PROXY` | `true` Only behind a reverse proxy (honors `X-Forwarded-*`).                                                                                                                                                                                      |
| `WIKIKT_PUBLIC_URL` | Canonical base URL for links in outbound email (password reset, welcome), e.g. `https://wiki.example.com`. Set on any internet-facing install so email links use your real host rather than a client-supplied `Host` header.                      |
| `WIKIKT_DATABASE_TYPE` | `h2` (default) or `postgres` (recommended for production).                                                                                                                                                                                        |
| `WIKIKT_DATABASE_R2DBC_URL` | Connection URL, e.g. `r2dbc:postgresql://host:5432/wikikt`.                                                                                                                                                                                       |
| `WIKIKT_DATABASE_USERNAME` / `WIKIKT_DATABASE_PASSWORD` | Database credentials                                                                                                                                                                                                                              |
| `WIKIKT_DATABASE_POOL_MAX_SIZE` | Max pooled connections the app will hold (default `10`). Keep it under the database server's own limit (Postgres defaults to `max_connections: 100`; raise it for a busy site, and if several app instances share one database, budget the sum). |
| `WIKIKT_DATABASE_POOL_INITIAL_SIZE` | Connections opened at startup (default `2`).                                                                                                                                                                                                      |
| `WIKIKT_DATABASE_POOL_MAX_IDLE_TIME` | Seconds an idle connection is kept before being closed (default `1800`).                                                                                                                                                                          |
| `WIKIKT_DATABASE_POOL_MAX_LIFE_TIME` | Seconds before a connection is recycled regardless of use (default `3600`).                                                                                                                                                                       |
| `WIKIKT_DATABASE_POOL_MAX_ACQUIRE_TIME` | Seconds to wait for a free connection when the pool is saturated before failing the request (default `10` -- to fail fast rather than hang).                                                                                                      |
| `WIKIKT_ASSET_STORAGE_DIR` | Upload storage dir (default `./data/uploads`).                                                                                                                                                                                                    |
| `WIKIKT_GIT_SYNC_DIR` | Git-sync working clone dir (default `./data/git-sync`) for Git Sync feature in Wiki admin settings for content/assets                                                                                                                             |
| `WIKIKT_UI_ASSET_SOURCE` | `cdn` (default) or `local`; sources for Bootstrap and highlight.js. See [Asset delivery](#asset-delivery).                                                                                                                                        |
| `WIKIKT_UI_ICON_FONT_SOURCE` | `cdn` (default) or `local`; sources for Material Design Icons webfont.                                                                                                                                                                        |
| `WIKIKT_UI_EMOJI_FONT_SOURCE` | `cdn` (default) or `local`; source for emoji webfont.                                                                                                                                                                                          |
| `WIKIKT_UI_MERMAID_SOURCE` | `cdn` (default) or `local`; source for the Mermaid diagram library (```mermaid fences).                                                                                                                                                          |
| `WIKIKT_UPDATE_REQUEST_DIR` / `WIKIKT_UPDATE_STATE_DIR` | Directories of the self-update handshake with the optional updater container. Already set by the Compose files; leave alone unless building a custom stack. Unset (both) = the one-click update feature is absent.                                |

### Compose-only variables (not WikiKT settings)

A few more `WIKIKT_*` names appear in `.env`, and despite the prefix they are **not** application settings --
they have no `application.yaml` key, and WikiKT itself never reads them. They exist only in the
Compose files, which use them to configure Caddy and the optional updater:

| Variable | Purpose |
|----------|---------|
| `WIKIKT_DOMAIN` | The primary hostname Caddy serves and obtains a Let's Encrypt certificate for. Also supplies the default for `WIKIKT_PUBLIC_URL` (`https://$WIKIKT_DOMAIN`), which you can still override by setting `WIKIKT_PUBLIC_URL` explicitly. |
| `WIKIKT_EXTRA_DOMAINS` | Additional hostnames for the multi-site feature, space-separated (quotes optional), e.g. `docs.example.com team.example.com`. Caddy obtains a certificate for each and routes them all to WikiKT. Leave unset if you serve a single hostname. |
| `WIKIKT_COMPOSE_DIR` | Only for the opt-in `selfupdate` profile: the absolute host path of the directory holding the Compose file, mounted into the updater container at that same path. Defaults to `/opt/wikikt`, the clone location in step 4, so most installs never set it. See [One-click updates](../docker/README.md#one-click-updates-optional-updater-container). |

The distinction matters for multi-site setups: these two tell **Caddy** which names to hold certificates
for, while the sites themselves are created in **Administration | Sites**, which is what WikiKT matches
the request `Host` against. Both are required, and neither implies the other -- see
[Multiple sites on one instance](#multiple-sites-on-one-instance). They also only apply to the bundled
Caddy stack; [`docker-compose.home.yml`](../docker-compose.home.yml) has no Caddy, so there certificates
and hostname routing are your own proxy's job and neither variable does anything.

## Asset delivery

WikiKT's front-end libraries and webfonts load from public CDNs by default. Most are also
bundled in the JAR, so you can serve them yourself if you prefer a fully local/self-served setup.

Four settings, one for each general category of assets, control this. Each accepts `cdn` (default)
or `local`; the default (none or invalid value specified) results in `cdn`.

| Setting (yaml) | Environment variable | Covers | Size | CDN host | Bundled at |
|---|---|---|---|---|---|
| `wikikt.ui.assetSource` | `WIKIKT_UI_ASSET_SOURCE` | Bootstrap, highlight.js | ~440 KB | `cdn.jsdelivr.net` | `/static/vendor/` |
| `wikikt.ui.iconFontSource` | `WIKIKT_UI_ICON_FONT_SOURCE` | Material Design Icons | ~750 KB | `cdn.jsdelivr.net` | `/static/vendor/mdi/` |
| `wikikt.ui.emojiFontSource` | `WIKIKT_UI_EMOJI_FONT_SOURCE` | Noto Color Emoji | ~2 MB | `fonts.googleapis.com` | `/static/vendor/noto-emoji/` |
| `wikikt.ui.mermaidSource` | `WIKIKT_UI_MERMAID_SOURCE` | Mermaid (diagrams) | ~3.5 MB | `cdn.jsdelivr.net` | `/static/vendor/mermaid/` |

They are separate settings rather than one overarching setting because the sizes differ by an order
of magnitude, and so do the consequences of a blocked CDN: missing *icons* might leave unexpected gaps
in the UI (though otherwise functioning), while a missing emoji font degrades gracefully to the OS defaults.
Mermaid is the largest of the lot but also the only one fetched *lazily* -- a page without a
```mermaid diagram on it never requests it, and a blocked CDN just leaves the diagram showing as its
source code block.

The current state of all four is shown read-only under **Administration | Settings | Appearance |
Asset delivery**. (Read-only because it can only be configured at deployment for instance, not per
site after deployment.)

With Docker, add those four lines to your `.env` (see [`.env.example`](../.env.example)) and re-run
`docker compose up -d`. In a yaml config file, set the `wikikt.ui.*` keys instead.

> **Note on fonts:** As of now, even with all set to `local`, the default (body and heading) fonts
> chosen under **Appearance | Typography** still load from Google Fonts. Pick the **System UI** preset
> to use system font instead (supported on most modern browsers and no download  needed), specify custom
> font using font or font family (e.g., sans-serif) that is widely available, or supply your own font
> through **Appearance | Custom CSS** pointing at a font file you've uploaded as an asset.

## Two-factor authentication (2FA)

WikiKT supports app-based two-factor authentication (TOTP -- Google Authenticator, Aegis, 1Password, etc.).
Any user can enable it under **Profile → Security** (from your account menu): scan the QR code (or enter the setup key
manually), confirm a 6-digit code, and save the one-time recovery codes shown once. After that, signing
in requires the password *and* a current code (or a recovery code).

It is suggested that every admin account have 2FA enabled for extra security, but any user can enable 2FA
if enabled for the site.

**Requirements & operations:**

- Set `WIKIKT_MFA_KEY` (a random 32-byte hex value): encrypts stored TOTP secrets and is required in
  production. Keep it backed up alongside your session keys; losing it makes existing 2FA enrolments
  unreadable (users would need to re-enroll).
- **Lost device recovery:** a user with their recovery codes can sign in with one. If they've lost both
  the device and the codes, an administrator can clear their 2FA from **Administration | Users | (edit
  user) | Reset two-factor authentication**, after which the user signs in with just their password and
  re-enrols. Resetting a *root* administrator's 2FA requires a root administrator.
- 2FA protects interactive login only; API keys are separate long-lived credentials (you should instead revoke a key
  if suspected of being compromised).
