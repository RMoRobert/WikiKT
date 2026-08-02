# WikiKT

WikiKT is a self-hosted wiki/documentation server written in
Kotlin and built using [Ktor](https://ktor.io), Exposed, and other Kotlin-friendly
technologies as described in the [Stack](#stack) section below.

Pages are authored in Markdown or HTML (Markdown preferred) and optionally
offer revision history. Pages and assets (images) are stored in file-system-like paths (not
actually a filesystem, just an assigned path that appears as one in the UI and URLs). Other
features include users and groups with different permissions allowed for each (plus guests),
full-text search, scheduled publishing, and reusable content fragments (transclusion).

## Why WikiKT?

While evaluating several documentation or wiki-type solutions, none quite met my needs,
though many came close. WikiKT aims to fill that gap for me. It may not offer everything
*you* need, and while somewhat flexible, certain architectural decisions were made to
prioritize my intended use. It may not meet yours, and that's OK! WikiKT is open-source,
and you're welcome to fork it and customize it to meet your needs. (Because I work on this
personal project only in my free time, I cannot personally offer support, significant PR review,
or similar assistance.)

# Stack

Notable dependencies include:

- **Ktor** (Netty engine), Kotlin, JVM 21
- **Mustache** for server-side templates
- **Exposed + R2DBC** with PostgreSQL recommended for deployment but H2 available for testing (or small/personal deployment)
- **commonmark** for Markdown, **jsoup** for HTML sanitizing

## Features

| Area | What it does                                                                                                    |
|------|-----------------------------------------------------------------------------------------------------------------|
| Pages | Markdown pages under wiki paths -- view / edit / create / move, revision history, staging, scheduled publishing |
| Access control | Per-group page rules (ALLOW/DENY, prefix or regex), view/edit ACLs, content masking                             |
| Admin | Users, groups, permissions, navigation menus, fragments, settings, branding                                     |
| Assets | Image upload (png/jpeg/gif/webp), versioning, scheduling, per-locale with default locale fallback supported     |
| Search | Full-text page (including framents) search backed by a search-index table                                       |
| Markdown | GFM rendering with icon shortcodes and a "rendering" pipeline for sanitization and other preprocessing          |

## Building & running

| Task | Description |
|------|-------------|
| `./gradlew run`         | Run the server (H2, on http://0.0.0.0:8080) |
| `./gradlew runPostgres` | Run against PostgreSQL (`application-postgres.yaml`) |
| `./gradlew test`        | Run the tests |
| `./gradlew build`       | Build the project |

On a successful start you'll see logs like:

```
[main] INFO  Application - Application started in 0.305 seconds.
[main] INFO  Application - Responding at http://0.0.0.0:8080
```

The default admin login is `admin` / `changeme` — **override it** via `wikikt.defaultAdmin.password`
or the `WIKIKT_ADMIN_PASSWORD` env var before exposing an instance.

### PostgreSQL

```
./gradlew databaseInstance   # start Postgres in Docker (docker compose up -d)
./gradlew runPostgres        # or: ./gradlew run -Pwikikt.db=postgres
```

## Configuration

Settings live in `src/main/resources/application.yaml` under the `wikikt:` namespace, each
overridable by a `WIKIKT_*` environment variable (e.g. `WIKIKT_ENV`, `WIKIKT_ADMIN_PASSWORD`,
`WIKIKT_DATABASE_TYPE`, `WIKIKT_SESSION_ENCRYPTION_KEY`). In `production` (`wikikt.environment` /
`WIKIKT_ENV`), insecure defaults -- including the `changeme` admin password and unset
session keys -- will log fatal errors instead of bypassable warnings.

## Deployment

The recommended production stack is **Docker Compose**: WikiKT + PostgreSQL + Caddy with automatic HTTPS
(via Let's Encrypt). It should work about the same on a DigitalOcean Droplet, Amazon EC2, Google Compute Engine, or
most self-hosted Linux boxes or similar. See [docs/install.md](docs/install.md) for common cloud deployment examples and 
non-Docker (fat JAR + systemd) alternative.

```bash
cp .env.example .env && chmod 600 .env   # then set domain, passwords, and session/MFA keys
docker compose -f docker-compose.prod.yml up -d --build
```

The image includes the `git` binary, so Git Sync (Administration > Git Sync) works out of the box, though
it is not required if you do not use this feature.
For local development, the  `compose.yaml` only provides PostgreSQL (`./gradlew databaseInstance`); the app
itself runs via Gradle.

## Documentation

* [Installation & deployment](docs/install.md): DigitalOcean, EC2, Google Cloud, etc. walkthroughs; self-hosting, environment variable reference
* [Docker stack details](docker/README.md): Information on recommended production Compose file configuration, upgrades, and backups
* [Exporting to Wiki.js](docs/wikijs-export.md): For users looking to export content *out* of WikiKT *to* Wiki.js (to import from, use Git sync to import Git repo or local file storage export)

For developers:
* [Database migrations](docs/migrations.md): How the R2DBC-native migration runner works and how to add a migration