# WikiKT Docker deployment

The recommended production stack lives in [`docker-compose.prod.yml`](../docker-compose.prod.yml):
**WikiKT + PostgreSQL + Caddy** (automatic HTTPS via Let's Encrypt). The image is built
from the repo's [`Dockerfile`](../Dockerfile) and also includes the `git` binary, so the optional Git
Sync feature in WikiKT works out of box (otherwise it is not required).
For deployment examples for common cloud environments (DigitalOcean, EC2, GCE) and non-Docker
installs, see [docs/install.md](../docs/install.md).

The [`compose.yaml`](../compose.yaml) file is suggested only for local development/debugging with the local
development PostgreSQL started by `./gradlew databaseInstance` (convenient to use from IDE, unlikely
to be useful in real deployments).

## Configure

All configuration comes from a `.env` file next to `docker-compose.prod.yml`. Copy the annotated
template and fill it in:

```bash
cp .env.example .env && chmod 600 .env
```

[`.env.example`](../.env.example) is the reference for this stack: it lists every variable the Compose
file reads, each with a comment on what it does and whether it is required. At minimum, you must set
`WIKIKT_DOMAIN`, `POSTGRES_PASSWORD`, `WIKIKT_ADMIN_PASSWORD`, and the three key values if running the app
in production mode (as the example file does by default and will refuse to start with an unset session/MFA key or
the default `changeme` password). Generate the keys with:

```bash
echo "WIKIKT_SESSION_ENCRYPTION_KEY=$(openssl rand -hex 16)"
echo "WIKIKT_SESSION_SIGN_KEY=$(openssl rand -hex 32)"
echo "WIKIKT_MFA_KEY=$(openssl rand -hex 32)"
```

`.env.example` covers the variables most deployments need, but it is not a closed set: the `wikikt` service
reads the whole file via `env_file`, so anything in the
[environment variable reference](../docs/install.md#environment-variable-reference) — connection-pool
sizing, session lifetime, storage paths, the optional
[asset delivery](../docs/install.md#asset-delivery) switches — can simply be added to `.env`.

The one exception is the settings the Compose file pins in its own `environment:` block: production mode,
`WIKIKT_TRUST_PROXY`/secure cookies, and the database wiring. Those take precedence over `.env` by
design, so nothing dropped in that file can silently weaken the deployment's security posture. Change them by
editing `docker-compose.prod.yml` directly.

Two variables in `.env` are not WikiKT settings at all: `WIKIKT_DOMAIN` and `WIKIKT_EXTRA_DOMAINS` are read-only
for Docker Compose to configure Caddy (see [DNS](#dns) and [Multiple sites](#multiple-sites-subdomains) below).

## DNS

Point every hostname you serve -- the primary `WIKIKT_DOMAIN` and each name in `WIKIKT_EXTRA_DOMAINS` -- at
the server *before* first start, since Caddy validates and issues a certificate per hostname:

```
A   wiki.example.com   <your-server-ip>
```

## Start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

First build compiles the app, which may take a few minutes. Caddy then obtains the certificate and serves
`https://wiki.example.com` (of course, replace all examples with your real domain).
Log in as `admin` with the password from `.env`.

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
per request from the `Host` header. What follows is the Caddy/Compose side of that; the feature itself
(creating sites, the catch-all, backups, other proxies) is covered in
[docs/install.md](../docs/install.md#multiple-sites-on-one-instance).

To serve extra hostnames over HTTPS, list them in `WIKIKT_EXTRA_DOMAINS` (space-separated) in `.env`:

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

## Pull a prebuilt image from GHCR

Both Compose files default to `build: .`, which compiles WikiKT on the target/host. That needs
considerably more memory than running it (see [docs/install.md](../docs/install.md#building-elsewhere)).
The [publish-image workflow](../.github/workflows/publish-image.yml) builds on GitHub's runners and pushes
to the GitHub Container Registry, so a small server can pull a finished image instead:

```bash
docker pull ghcr.io/rmorobert/wikikt:latest
```

To use it, comment out `build: .` in the Compose file, uncomment the `image:` line next to it, and start
*without* `--build`:

```bash
docker compose -f docker-compose.prod.yml up -d
```

Available tags:

| Tag | What it is                                                               |
|---|--------------------------------------------------------------------------|
| `latest` | The newest released version -- recommended for most use cases            |
| `1.2.3`, `1.2`, `1` | A specific release, pinned as loose/tight as you like                    |
| `main` | Tip of the default branch; newer than any release and likely less tested |
| `sha-abc1234` | An exact commit, for reproducing or rolling back to a known build        |

Upgrading becomes a pull rather than a rebuild:

```bash
docker compose -f docker-compose.prod.yml pull wikikt && docker compose -f docker-compose.prod.yml up -d
```

**First-time setup (repo owner):** *(this is done in the official repo already, so this note is for
developers who clone or fork for their own use)* The first workflow run creates the package as **private**, so pulls
from a server will fail with "denied" or "manifest unknown" until it is published. On GitHub go to the
package (Profile/repo | **Packages** | `wikikt`) → **Package settings** | **Change visibility** |
**Public**. To deliberately keep it private instead, the server must authenticate before pulling:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u <github-username> --password-stdin
```

using a personal access token with `read:packages`. Also link the package to the repository on that same
settings page so it inherits the repo's README and permissions.

### Publishing a release

The version shown in **Administration** in the WikiKT UI (and recorded in backup archives) comes
from `version` in `build.gradle.kts`, baked into `wikikt.properties` at build time. Released *images* are
stamped from the git tag instead, i.e., a published image always reports its own tag. The workflow hands
the tag to the Dockerfile's `WIKIKT_VERSION` build arg, which becomes `-PwikiktVersion` for Gradle. Branch
builds pass nothing and keep the `-SNAPSHOT` version from `build.gradle.kts`, correctly distinguishing
these from "real" relases. Bumping `build.gradle.kts` in the same commit is still good practice, however,
so "manual" builds from source at that tag report the corresponding version accurately, too.

**Steps:**

1. Set `version = "0.2.0"` in `build.gradle.kts`, commit, and push.
2. Create a GitHub **Release** with a new tag `v0.2.0` — this creates and pushes the tag, which triggers
   the workflow. (Equivalently: `git tag v0.2.0 && git push origin v0.2.0`.)
3. The workflow publishes `0.2.0`, `0.2`, `0`, and `latest`, all reporting version `0.2.0`.

Keep the `v` prefix on the tag, as the workflow looks for `v*`, then `docker/metadata-action` strips it (so
`v0.2.0` ultimately produces image tag `0.2.0`). If you create a Release from a tag that already exists (not
recommended), no push event fires, but you can run the workflow manually from the **Actions** tab in that
case.

To build a stamped image yourself without the workflow, pass the same build argument:

```bash
docker build --build-arg WIKIKT_VERSION=0.2.0 -t wikikt:0.2.0 .
```

## Run from a prebuilt image (no registry)

Both Compose files default to `build: .`, which compiles the app on the target host. To build
the image on your workstation instead and ship it to the server -- no Docker registry, and no build
toolchain (JDK/Gradle) needed on the box -- use `docker save` and `docker load`:

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
#                or:  docker save -o wikikt-image.tar wikikt:latest
#    then copy to server (e.g., scp) and, on the server:  gunzip -c wikikt.tar.gz | docker load

# 3. Confirm it landed:
ssh user@server 'docker images wikikt'
```

Then point the Compose file at that image instead of building: comment out `build: .` and use
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

Two methods, depending on your needs:

- **Application backups**: **Administration > Backup** in WikiKT gives a ZIP file of either content
  only or complete site (including accounts, configuration, etc.). Git Sync in (at least) push mode is another
  option for a continuous off-site mirror of content.
- **Infrastructure backups**: snapshot the volumes, or execute a command like
  `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U wikikt wikikt > backup.sql`.

## Logs & health

```bash
docker compose -f docker-compose.prod.yml logs -f wikikt
docker compose -f docker-compose.prod.yml ps   # healthchecks: postgres + wikikt report health
```

**Certificate problems?** `docker compose -f docker-compose.prod.yml logs caddy | grep -i cert` should
give clues, e.g., DNS not pointing at the server yet or ports 80/443 blocked by firewall.
