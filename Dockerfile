# Multi-stage build: compile on JDK 21, run on a lightweight JRE 21.
# Stage 1: Build (shadowJar produces the self-contained *-all.jar the runtime stage runs)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
# Stamps the jar with a release version (shown in the admin console and recorded in backups). The
# publish workflow passes the git tag here; left unset, the build falls back to the version in
# build.gradle.kts. Note git-based versioning is not an option: .dockerignore excludes .git/, so the
# build cannot see the repository history.
ARG WIKIKT_VERSION
# The commit being built, for the admin console's build info. Passed by the publish workflow for the
# same reason as WIKIKT_VERSION: .dockerignore excludes .git/, so the build cannot read it here.
ARG WIKIKT_GIT_SHA
COPY . .
RUN ./gradlew --no-daemon shadowJar -x test \
      ${WIKIKT_VERSION:+-PwikiktVersion=$WIKIKT_VERSION} \
      ${WIKIKT_GIT_SHA:+-PwikiktGitSha=$WIKIKT_GIT_SHA}

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
# ARGs are per-stage; re-declare the version for the label below.
ARG WIKIKT_VERSION
# Guardrail metadata for the optional wikikt-updater sidecar (see docker/updater/). The updater reads
# these OCI labels off the pulled image vs. the running one via `docker inspect` -- keeps offline and not
# susceptible to app problems -- to decide whether a one-click update is safe:
#   schema-version     - This build's DB schema (MIGRATIONS max). MUST match the code; BuildInfoTest
#                        asserts it against MigrationService. Rollback after a failed update is only
#                        automatic when this is UNCHANGED (migrations are forward-only).
#   compose-revision   - Bump when docker-compose.prod.yml or .home.yml change in a way an image pull
#                        can't deliver (e.g., new service/volume/required env var). The updater refuses
#                        ("blocked") when the pulled image's revision is higher than the running one.
#   min-upgrade-from   - Oldest running version this image upgrades cleanly from.
#   updater-protocol   - The request/status file contract version (SelfUpdateService.PROTOCOL).
ARG WIKIKT_SCHEMA_VERSION=2
ARG WIKIKT_COMPOSE_REVISION=1
ARG WIKIKT_MIN_UPGRADE_FROM=0.0.0
LABEL org.opencontainers.image.source="https://github.com/RMoRobert/WikiKT" \
      org.opencontainers.image.version="${WIKIKT_VERSION}" \
      com.wikikt.schema-version="${WIKIKT_SCHEMA_VERSION}" \
      com.wikikt.compose-revision="${WIKIKT_COMPOSE_REVISION}" \
      com.wikikt.min-upgrade-from="${WIKIKT_MIN_UPGRADE_FROM}" \
      com.wikikt.updater-protocol="1"
# git powers Administration > Git Sync; curl serves the container healthcheck.
RUN apk add --no-cache git curl
WORKDIR /app
COPY --from=builder /build/build/libs/wikikt*-all.jar ./wikikt.jar
# H2 database (when used), uploaded assets, and the git-sync clone all live under /app/data.
VOLUME ["/app/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/healthz >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "wikikt.jar"]
