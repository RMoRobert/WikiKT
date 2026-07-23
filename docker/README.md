# WikiKT Docker deployment

The recommended production stack lives in [`docker-compose.prod.yml`](../docker-compose.prod.yml):
**WikiKT + PostgreSQL + Caddy** (automatic HTTPS via Let's Encrypt). The image is built from the
repo's [`Dockerfile`](../Dockerfile) and also includes the `git` binary, so the optiona Git Sync feature in
WikiKT works out of box (otherwise it is not required).
For deployment examples for common cloud environments (DigitalOcean, EC2, GCE) and non-Docker
installs, see [docs/install.md](../docs/install.md).

The [`compose.yaml`](../compose.yaml) file is suggested only for local development/debugging with the local
development PostgreSQL started by `./gradlew databaseInstance` (convenient to use from IDE, unlikely
to be useful in real deployments).

## Configure

Create a `.env` file next to `docker-compose.prod.yml` — `cp .env.example .env` gives you the
template below with every option and its comments, ready to fill in:

```bash
# Your primary domain (required). Caddy will obtain HTTPS certificate
WIKIKT_DOMAIN=wiki.example.com

# Optional: extra site hostnames for the multi-site feature (space-separated). See "Multiple sites" below.
# WIKIKT_EXTRA_DOMAINS=docs.example.com team.example.com

# PostgreSQL password (also used by app's connection)
POSTGRES_PASSWORD=<strong-password>

# Session keys: hex strings, both required in production (dev will warn, prod will fail without):
WIKIKT_SESSION_ENCRYPTION_KEY=<openssl rand -hex 16>
WIKIKT_SESSION_SIGN_KEY=<openssl rand -hex 32>

# MFA key: encrypts stored two-factor (TOTP) secrets at rest; also required in production.
WIKIKT_MFA_KEY=<openssl rand -hex 32>

# Admin password (dev will warn, but prod cannot be or start with "changeme")
WIKIKT_ADMIN_PASSWORD=<admin-password>
```

Generate the keys:

```bash
echo "WIKIKT_SESSION_ENCRYPTION_KEY=$(openssl rand -hex 16)"
echo "WIKIKT_SESSION_SIGN_KEY=$(openssl rand -hex 32)"
echo "WIKIKT_MFA_KEY=$(openssl rand -hex 32)"
```

### Asset delivery (optional)

Front-end libraries and webfonts load from public CDNs by default. All of them are also bundled in the
image, so you can serve them from your own container instead — for an air-gapped network, a strict
egress policy, or to avoid sending visitor IP addresses to jsDelivr and Google. Add to `.env`:

```bash
WIKIKT_UI_ASSET_SOURCE=local        # Bootstrap, highlight.js (~440KB)
WIKIKT_UI_ICON_FONT_SOURCE=local    # Material Design Icons (~750KB)
WIKIKT_UI_EMOJI_FONT_SOURCE=local   # Noto Color Emoji (~2MB)
```

Set all three — they are independent. One more request remains after that: the body/heading font
chosen in **Admin → Settings → Appearance** still comes from Google Fonts unless you pick the
**System UI** preset. The effective state of all three is shown on that same page, and the full
rationale is in [docs/install.md](../docs/install.md#asset-delivery).

## DNS

Point your domain at the server before first start (Caddy needs it to pass the ACME challenge) with a DNS
configuration resembling:

```
A   wiki.example.com   <your-server-ip>
```

Add one such record for **every** hostname you serve — the primary `WIKIKT_DOMAIN` and each name in
`WIKIKT_EXTRA_DOMAINS` — since Caddy validates and issues a certificate per hostname.

## Start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

First build compiles the app, which may take a few minutes. Caddy then obtains the certificate and serves
`https://wiki.example.com`. Log in as `admin` with the password from `.env`.

### Services

- **postgres** (internal): PostgreSQL 18, data in the `postgres_data` volume
- **wikikt** (internal :8080): the app; uploads + git-sync clone in the `wikikt_data` volume
- **caddy** (public :80/:443): reverse proxy + automatic HTTPS

The app runs with `WIKIKT_ENV=production` (insecure defaults are fatal, not warnings, in production mode),
`WIKIKT_TRUST_PROXY=true` (Caddy fronts it), and secure session cookies. It also derives
`WIKIKT_PUBLIC_URL` from `WIKIKT_DOMAIN`, so password-reset and welcome emails link to your real host
(instead of a client-supplied `Host` header).

## Multiple sites (subdomains)

WikiKT can host multiple sites on one instance, each answering to its own hostname (a subdomain like
`docs.example.com`, or a separate domain), with one site as the catch-all fallback. The site is chosen
per request from the `Host` header. To serve extra hostnames over HTTPS, list them in
`WIKIKT_EXTRA_DOMAINS` (space-separated) in `.env`:

```bash
WIKIKT_DOMAIN=wiki.example.com
WIKIKT_EXTRA_DOMAINS=docs.example.com team.example.com
```

Caddy then obtains a Let's Encrypt certificate for each name and routes them all to WikiKT, with WikiKT
choosing the appropriate site.

**A second site's hostname must be set in two places:**

1. **Here (Caddy)**: add it to `WIKIKT_EXTRA_DOMAINS`, then `docker compose -f docker-compose.prod.yml up -d`
   to recreate the caddy container if already deployed (or set before first deployment) -- Caddy can't learn a new
   hostname from the running app alone, so adding one in the admin console alone leaves it without HTTPS.
2. **In WikiKT**: in **Administration > Sites**, create the site with the same hostname.

Be sure to also add a DNS record per hostname pointing at the server (Caddy issues one cert per name).

Note: `WIKIKT_PUBLIC_URL` is a single value (the primary domain). It only affects the host in outbound-email
links (password reset, welcome). Because accounts are shared across all sites, this should not be an issue, but
be aware that some users may notice the domain is different from the site they are working in and instead is
the primary site domain.

## Home (behind your own reverse proxy)

If you already run a reverse proxy (nginx, a router/firewall appliance, etc.) or
just want WikiKT on your LAN, use [`docker-compose.home.yml`](../docker-compose.home.yml) instead. This includes
the same app plus PostgreSQL, *without Caddy*, with the app published on host port 8080 for your proxy
(or a browser) to reach. You may change the external port as necessary.

```bash
cp .env.home.example .env && chmod 600 .env   # then fill it in
docker compose -f docker-compose.home.yml up -d --build
```

It uses PostgreSQL with production mode/TLS active and carries commented examples of swaps you can make
for other configurations if desired:

- **H2 instead of PostgreSQL**: one less container; the database becomes a file in the app volume.
- **Plain HTTP on a trusted LAN**: production requires a Secure session cookie, so it refuses to
  boot without TLS in front; the block switches to development mode to allow plain HTTP.

TLS becomes your responsibility -- .e.g, point your proxy at `http://<host>:8080` and keep
`WIKIKT_SESSION_SECURE_COOKIE=true` + `WIKIKT_TRUST_PROXY=true` (default values in file).
A self-signed cert can work, although not suggested for other than local, personal use or testing;
it keeps you in production mode without needing a public domain. To ship a prebuilt
image rather than build on the box, see the `docker save | docker load` comment in the Compose file.

The **Upgrades**, **Backups**, and **Logs & health** sections below apply to this type of deployment too,
in general; just substitute `-f docker-compose.home.yml` for `-f docker-compose.prod.yml`.

## Run from a prebuilt image (no registry)

Both compose files default to `build: .`, which compiles the app **on the target host**. To instead
build the image once on your workstation and ship it to the server — no Docker registry, and no build
toolchain (JDK/Gradle) needed on the box — use `docker save` / `docker load`:

```bash
# 1. Build on your workstation (repo root). The Dockerfile is multi-stage, so this runs the
#    Gradle shadowJar build inside the image (you don't run Gradle yourself). It builds for the
#    host's architecture; on an x86_64 host building for an x86_64 server, this is all you need.
docker build -t wikikt:latest .
#    If your build host and server have different architectures (e.g. an Apple Silicon Mac
#    building for an x86_64 server), pin the target so it doesn't build for your current arch:
#    docker buildx build --platform linux/amd64 -t wikikt:latest --load .

# 2. Save it, copy it over, and load it on the server. For all-in-one step, try (or see alteratives):
docker save wikikt:latest | gzip | ssh user@server 'gunzip | docker load'

#    …or via a file:  docker save wikikt:latest | gzip > wikikt.tar.gz
#                or:  docker save -o wikikit-image.tar wikikt:latest
#    then copy to server (e.g., scp) and, on the server:  gunzip -c wikikt.tar.gz | docker load

# 3. Confirm it landed:
ssh user@server 'docker images wikikt'
```

Then point the compose file at that image instead of building: comment out `build: .` and use
`image: wikikt:latest` (the [`docker-compose.home.yml`](../docker-compose.home.yml) already carries
this as a comment; the same swap works in `docker-compose.prod.yml`). Start *without* `--build`:

```bash
docker compose -f docker-compose.home.yml up -d   # no --build → uses the loaded image
```

To upgrade later, repeat steps 1–2 to load a newer `wikikt:latest`, then run `up -d` again.

## Upgrades

```bash
docker compose -f docker-compose.prod.yml build --pull wikikt
docker compose -f docker-compose.prod.yml up -d
```

Database, uploads, and certificates persist in their volumes. Schema migrations run automatically
at startup.

## Backups

Two complementary layers:

- **Application backups**: **Administration > Backup** in WikiKIT gives a ZIP file of either content
  only or complete site (including accounts, configuration, etc.). Git Sync in (at least) push mode is another
- option for a continuous off-site mirror of content.
- **Infrastructure backups**: snapshot the volumes, or execute a command like
  `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U wikikt wikikt > backup.sql`.

## Logs & health

```bash
docker compose -f docker-compose.prod.yml logs -f wikikt
docker compose -f docker-compose.prod.yml ps   # healthchecks: postgres + wikikt report health
```

**Certificate problems?** `docker compose -f docker-compose.prod.yml logs caddy | grep -i cert` should
give clues, e.g., DNS not pointing at the server yet or ports 80/443 blocked by firewall.
