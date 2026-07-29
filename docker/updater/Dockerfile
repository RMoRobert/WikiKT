# The WikiKT self-update sidecar ("wikikt-updater"). Holds the Docker socket so the app never has
# to; watches /req for a doorbell file written by the app and performs a guarded
# `docker compose pull` + `up -d --no-deps` of the app service, with a pre-update pg_dump, a health
# gate, and digest rollback. See updater.sh for the full contract and docker/README.md for the
# trust model and the compose `selfupdate` profile that runs this.
FROM docker:27-cli
RUN apk add --no-cache docker-cli-compose bash jq curl
COPY updater.sh /usr/local/bin/updater.sh
RUN chmod +x /usr/local/bin/updater.sh
# The file-protocol version this updater speaks (SelfUpdateService.PROTOCOL on the app side).
LABEL org.opencontainers.image.source="https://github.com/RMoRobert/WikiKT" \
      com.wikikt.updater-protocol="1"
ENTRYPOINT ["/usr/local/bin/updater.sh"]
