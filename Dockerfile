# Multi-stage build: compile on JDK 21, run on a lightweight JRE 21.
# Stage 1: Build (shadowJar produces the self-contained *-all.jar the runtime stage runs)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN ./gradlew --no-daemon shadowJar -x test

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
  CMD curl -fsS http://localhost:8080/ >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "wikikt.jar"]
