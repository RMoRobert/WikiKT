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
