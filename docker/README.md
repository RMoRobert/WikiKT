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
docker compose -f docker-compose.prod.yml up -d
```

First start pulls the prebuilt app image from the GitHub Container Registry (see
[Pull a prebuilt image from GHCR](#pull-a-prebuilt-image-from-ghcr), including how to build from
source instead). Caddy then obtains the certificate and serves `https://wiki.example.com` (of course,
replace all examples with your real domain). Log in as `admin` with the password from `.env`.

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
If you switch to the GHCR image, also consider enabling
[one-click updates](#one-click-updates-optional-updater-container) — suggested there, though off by
default to keep this stack offline-friendly.

The **Upgrades**, **Backups**, and **Logs & health** sections below apply to this type of deployment too,
in general; just substitute `-f docker-compose.home.yml` for `-f docker-compose.prod.yml`.

## Pull a prebuilt image from GHCR

The [publish-image workflow](../.github/workflows/publish-image.yml) builds WikiKT on GitHub's runners
and pushes it to the GitHub Container Registry. **`docker-compose.prod.yml` uses this image by
default** (`image: ghcr.io/rmorobert/wikikt:latest`), so a small server never compiles anything;
`docker-compose.home.yml` defaults to `build: .` (compiling on the box) and carries the GHCR image as a
commented alternative. Building from source needs considerably more memory than running the app (see
[docs/install.md](../docs/install.md#building-elsewhere)).

To build from source with the prod file instead, comment out its `image:` line, uncomment `build: .`,
and start with `--build`:

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

To switch home to the registry image, do the reverse there: comment out `build: .`, uncomment the
`image: ghcr.io/...` line, and start *without* `--build`.

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
developers who clone or fork for their own use)* Ensure that your first workflow run did not create
the package as private if you need public access (e.g., for easy deployment). On GitHub, go to each
package (Profile/repo | **Packages** | `wikikt` — **and `wikikt-updater`** if you ship the `selfupdate`
profile, which `.env.example` enables by default) → **Package settings** | **Change visibility** |
**Public**. If you do keep them private instead, the server must authenticate before pulling:

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
these from "real" releases. Bumping `build.gradle.kts` in the same commit is still good practice, however,
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

`docker-compose.home.yml` defaults to `build: .`, which compiles the app on the target host (the prod
file pulls from GHCR by default). To build the image on your workstation instead and ship it to the
server -- no Docker registry, and no build toolchain (JDK/Gradle) needed on the box -- use
`docker save` and `docker load`:

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

**Update notifications:** a root admin can enable release checks under **Administration > Updates**
in the WikiKT UI. When enabled, opening that page (at most once a day) compares the running version
against the latest GitHub release and shows the upgrade steps, and the Administration dashboard grows
an "update available" link (re-checked at most weekly, in the background, so the dashboard never waits
on the network). It is opt-in, contacts only `api.github.com`, and sends nothing about your instance:
until an admin enables it, no request is ever made -- and the same page turns it back off. There is no
background poller; checks only happen while a root admin has the console open. The upgrade itself is
the manual step below.

If you run the **prebuilt image** (the prod default), upgrading is a pull:

```bash
docker compose -f docker-compose.prod.yml pull wikikt
docker compose -f docker-compose.prod.yml up -d
```

If you **build from source** (the home default), fetch the new code and rebuild:

```bash
git pull
docker compose -f docker-compose.home.yml build --pull wikikt
docker compose -f docker-compose.home.yml up -d
```

Either way: database, uploads, and certificates persist in their volumes, and schema migrations run
automatically at startup. Taking a backup first (**Administration > Storage and backup**) is always a good idea.

## One-click updates (optional updater container)

Both Compose files carry a `wikikt-updater` service (behind the `selfupdate` compose profile) that
turns the pull-and-restart above into an **Install update** button on **Administration > Updates**.
It is the **default for the production stack** — `.env.example` (and the quick-setup snippet in
docs/install.md) ships `COMPOSE_PROFILES=selfupdate`, which activates the profile on a plain
`up -d`. For `docker-compose.home.yml` it stays **off by default** (the home default builds from
source, which has nothing to pull) but is suggested once you switch to the GHCR image — uncomment
`COMPOSE_PROFILES=selfupdate` in your `.env` (see `.env.home.example`).

**Prefer an offline / no-socket deployment?** Remove (or leave commented) the `COMPOSE_PROFILES`
line in `.env` — the updater then never starts, nothing else changes, and the Updates page shows
copy-paste upgrade commands instead of the button. That line is the whole switch, in both
directions.

When a root admin clicks **Install update**, the updater:

1. takes a `pg_dump` of the database into the `wikikt_update_state` volume (aborts if this fails),
2. pulls whatever image the Compose file resolves to (never a version the app chooses),
3. compares guardrail labels between the pulled and running images, and **refuses** — touching
   nothing — if the release needs a Compose-file change or a stepwise upgrade,
4. restarts *only* the `wikikt` service (`up -d --no-deps`),
5. waits for the new container's healthcheck, and **rolls back to the previous image** if it never
   reports healthy — unless the new version already migrated the database schema, in which case it
   stops and tells you exactly which backup to restore (migrations are forward-only; rolling the
   image back would not roll the database back).

**Using H2 instead of PostgreSQL?** Step 1 only runs against a PostgreSQL service (it is a
`pg_dump`). On an H2 setup -- the alternative offered in `docker-compose.home.yml`, where the
database is a file inside the app volume rather than its own service -- the updater finds no such
service and **skips the pre-update backup**, then continues with the update. That matters most in
the one case you would want it: if the new version fails its health check *and* had already migrated
the schema, the updater deliberately does not roll back, so there is nothing to fall back to. **Take
a full backup yourself (Administration > Storage and backup > full export) before clicking Install update on an
H2 instance.** (PostgreSQL setups need no such care: a failed `pg_dump` aborts the update outright.)

**A note on trust (read before enabling):** The updater mounts `/var/run/docker.sock`, which is
root-equivalent on the host. That is exactly why it is a separate container: WikiKT itself never
gets the socket, so compromising the (likely Internet-facing) app does not compromise the host. The app can
only write a small "doorbell" file into a volume the updater reads; the updater takes nothing else
from it -- its target comes from its own environment and the Compose file, and every safety decision
is made from image labels via `docker inspect`. The two handshake volumes are mounted in opposite
directions (app can write requests but only read status), so a compromised app also cannot forge an
update's outcome. If holding the socket anywhere is unacceptable in your environment,
drop the `COMPOSE_PROFILES` line as described below; everything else on the Updates page still works.

### Enabling / disabling

Persistent, either stack — in `.env`:

```bash
COMPOSE_PROFILES=selfupdate   # present = updater runs; absent = it doesn't
```

then `docker compose -f <file> up -d` (when turning it *off*, add `--remove-orphans` so the stopped
updater container is cleaned up). One-off without touching `.env`:

```bash
docker compose -f docker-compose.prod.yml --profile selfupdate up -d
```

If the project lives at `/opt/wikikt` (the install location [docs/install.md](../docs/install.md)
recommends), that is all. Anywhere else, also set the ABSOLUTE host path of the directory holding
the compose file in `.env`:

```bash
WIKIKT_COMPOSE_DIR=/srv/wikikt   # example — must be exact
```

This must be exact (`docker compose` running inside the updater resolves relative bind-mount
sources against the HOST filesystem; the project is therefore mounted into the updater at its own
host path). An incorrect value will fail and the updater will report "Compose file(s) not visible,"
and this should be a safe failure that won't inadvertently act on a different stack.

The app detects the updater by its heartbeat file, so the Updates page reflects it within a few
seconds. For `docker-compose.home.yml` the same applies, but the `wikikt` service must first be
switched to the GHCR `image:` alternative -- a `build: .` service has nothing to pull. The updater
cannot update *itself*; refresh it if ever necessary with
`docker compose --profile selfupdate pull wikikt-updater && docker compose --profile selfupdate up -d`.

While an update runs, the page auto-refreshes and briefly shows a connection error when WikiKT
itself restarts. That is expected, as it reconnects to the new version and reports the outcome. Pre-update
database dumps live in the `wikikt_update_state` volume under `backups/` (last 3 kept). They are not
files on the host — the `wikikt` container has that volume mounted read-only at `/app/update/state`, so
stream the dump straight out of it to restore (`-T` on both execs, or a TTY corrupts the piped bytes):

```bash
# List the available dumps:
docker compose -f docker-compose.prod.yml exec -T wikikt ls -1 /app/update/state/backups
# Restore the one you want (replace <epoch> with its timestamp):
docker compose -f docker-compose.prod.yml exec -T wikikt cat /app/update/state/backups/pre-update-<epoch>.dump \
  | docker compose -f docker-compose.prod.yml exec -T postgres pg_restore -U wikikt -d wikikt --clean
```

### Manual test matrix (for updater changes)

The updater is deliberately not unit-tested shell; `shellcheck` runs in CI, `updater.sh --dry-run`
prints the exact commands a pending request would execute, and changes should be walked through this
matrix on a scratch VM: happy path; no-op when already current; health-gate failure with rollback;
health-gate failure with a schema change (must *not* roll back); `blocked` on a compose-revision bump;
malformed request ignored; replayed requestId ignored; updater killed mid-run (page shows "status
unknown" after ~15 min).

## Backups

Two methods, depending on your needs:

- **Application backups**: **Administration > Storage and backup** in WikiKT gives a ZIP file of either content
  only or complete site (including accounts, configuration, etc.). Git Sync in (at least) push mode is another
  option for a continuous off-site mirror of content.
- **Infrastructure backups**: snapshot the volumes, or execute a command like
  `docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U wikikt wikikt > backup.sql`.

## Logs & health

```bash
docker compose -f docker-compose.prod.yml logs -f wikikt
docker compose -f docker-compose.prod.yml ps   # healthchecks: postgres + wikikt report health
```

**Certificate problems?** `docker compose -f docker-compose.prod.yml logs caddy | grep -i cert` should
give clues, e.g., DNS not pointing at the server yet or ports 80/443 blocked by firewall.
