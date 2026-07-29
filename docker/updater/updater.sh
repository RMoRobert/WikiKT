#!/usr/bin/env bash
# WikiKT self-update sidecar. Pairs with SelfUpdateService.kt (the app side) and the `selfupdate`
# compose profile (docker-compose.prod.yml / docker-compose.home.yml).
#
# TRUST MODEL — read before editing:
#   This container holds /var/run/docker.sock, which is root-equivalent on the host. The app does
#   not, ever. The app's request file is a DOORBELL, NOT A COMMAND: nothing the app writes may
#   become a docker argument, an image reference, a path, a tag, a flag, or a shell word. The
#   updater's target comes from its own environment and the compose file on the host (which the app
#   cannot write); it pulls whatever the compose file's image ref resolves to; and every guardrail
#   is evaluated from OCI labels via `docker inspect` — data a compromised app cannot forge without
#   controlling the registry. App-supplied strings (requestedBy, expectVersion) are validated by
#   regex, truncated, and used ONLY in log/status text. Style rules that keep it that way:
#   set -euo pipefail, argv arrays, no eval, no sh -c "$var". shellcheck runs in CI.
#
# FILE PROTOCOL (all timestamps epoch MILLISECONDS; schema/protocol = 1):
#   /req/request.json   (app -> updater, updater mounts /req READ-ONLY)
#       {schema, requestId(uuid), requestedAt, requestedBy, fromVersion, expectVersion}
#   /state/status.json  (updater -> app; app mounts the state dir READ-ONLY)
#       {schema, requestId, phase, terminal, startedAt, updatedAt, finishedAt, fromVersion,
#        fromDigest, toVersion, toDigest, message, backupPath, logTail}
#       phases: preparing backing-up pulling checking stopping starting verifying
#               -> success | failed | rolled-back | blocked   (terminal: true)
#   /state/updater.json (heartbeat, rewritten every ~10 s; how the app detects us)
#       {schema, protocol, beatAt, composeProject, targetService, capabilities,
#        runningComposeRevision}
#
# The updater cannot delete request.json (read-only mount), so /state/.last-request-id provides
# replay protection and /state/.lock (mkdir, atomic) is the single-flight guarantee.
#
# --dry-run: validate any pending request and print the exact argv of every mutating command
# without executing anything or writing state. The best artifact for reviewing the trust boundary.
set -euo pipefail

REQ_DIR="${WIKIKT_REQUEST_DIR:-/req}"
STATE_DIR="${WIKIKT_STATE_DIR:-/state}"
SERVICE="${WIKIKT_SERVICE:-wikikt}"
DB_SERVICE="${WIKIKT_DB_SERVICE:-postgres}"
DB_USER="${WIKIKT_DB_USER:-wikikt}"
DB_NAME="${WIKIKT_DB_NAME:-wikikt}"
HEALTH_TIMEOUT="${WIKIKT_HEALTH_TIMEOUT:-180}"
POLL_SECONDS="${WIKIKT_POLL_SECONDS:-10}"
BACKUPS_KEEP="${WIKIKT_BACKUPS_KEEP:-3}"
# Optional disambiguator when several compose projects on this host run a service named $SERVICE.
PROJECT_FILTER="${WIKIKT_COMPOSE_PROJECT:-}"
PROTOCOL=1
SCHEMA=1

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# Logs go to stderr: several callers (validate_request, find_container) are used in $(...) command
# substitution, where stdout is the return channel and any log line would corrupt the captured value.
log() { echo "[updater] $(date -u +%FT%TZ) $*" >&2; }
now_ms() { echo $(( $(date +%s) * 1000 )); }

# Mutating commands go through runc so --dry-run can print argv instead of executing.
runc() {
    if (( DRY_RUN )); then
        log "DRY-RUN would exec:" "$(printf '%q ' "$@")"
    else
        "$@"
    fi
}

append_log() {
    log "$*"
    (( DRY_RUN )) && return 0
    echo "$(date -u +%FT%TZ) $*" >> "$STATE_DIR/.log"
    tail -n 200 "$STATE_DIR/.log" > "$STATE_DIR/.log.tmp" && mv -f "$STATE_DIR/.log.tmp" "$STATE_DIR/.log"
}

# ---- state writers (single writer of /state; every write is an atomic move) ------------------

# Per-run context, reset for each request.
REQUEST_ID=""; STARTED_AT=0; FROM_VERSION=""; FROM_DIGEST=""; TO_VERSION=""; TO_DIGEST=""; BACKUP_PATH=""

write_status() { # $1=phase $2=terminal(true|false) $3=message
    (( DRY_RUN )) && { log "DRY-RUN status: $1 (terminal=$2): $3"; return 0; }
    local finished=null
    [[ "$2" == "true" ]] && finished=$(now_ms)
    jq -n \
        --argjson schema "$SCHEMA" \
        --arg requestId "$REQUEST_ID" \
        --arg phase "$1" \
        --argjson terminal "$2" \
        --argjson startedAt "$STARTED_AT" \
        --argjson updatedAt "$(now_ms)" \
        --argjson finishedAt "$finished" \
        --arg fromVersion "$FROM_VERSION" \
        --arg fromDigest "$FROM_DIGEST" \
        --arg toVersion "$TO_VERSION" \
        --arg toDigest "$TO_DIGEST" \
        --arg message "$3" \
        --arg backupPath "$BACKUP_PATH" \
        --slurpfile logtail <(tail -n 15 "$STATE_DIR/.log" 2>/dev/null | jq -R . | jq -s .) \
        '{schema: $schema, requestId: $requestId, phase: $phase, terminal: $terminal,
          startedAt: $startedAt, updatedAt: $updatedAt, finishedAt: $finishedAt,
          fromVersion: $fromVersion, fromDigest: $fromDigest,
          toVersion: $toVersion, toDigest: $toDigest,
          message: $message, backupPath: $backupPath, logTail: ($logtail[0] // [])}' \
        > "$STATE_DIR/.status.tmp" && mv -f "$STATE_DIR/.status.tmp" "$STATE_DIR/status.json"
}

finish() { # $1=terminal-phase $2=message  -> releases the lock
    append_log "終 $1: $2"
    write_status "$1" true "$2"
    (( DRY_RUN )) || { echo "$REQUEST_ID" > "$STATE_DIR/.last-request-id"; rmdir "$STATE_DIR/.lock" 2>/dev/null || true; }
}

# Heartbeat runs in its own background loop so it stays fresh during long pulls.
heartbeat_loop() {
    while :; do
        local cid="" project="" running_rev=null
        cid=$(find_container || true)
        if [[ -n "$cid" ]]; then
            project=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$cid" 2>/dev/null || true)
            local rev
            rev=$(docker inspect -f '{{ index .Config.Labels "com.wikikt.compose-revision" }}' "$cid" 2>/dev/null || true)
            [[ "$rev" =~ ^[0-9]+$ ]] && running_rev=$rev
        fi
        jq -n \
            --argjson schema "$SCHEMA" \
            --argjson protocol "$PROTOCOL" \
            --argjson beatAt "$(now_ms)" \
            --arg composeProject "$project" \
            --arg targetService "$SERVICE" \
            --argjson runningComposeRevision "$running_rev" \
            '{schema: $schema, protocol: $protocol, beatAt: $beatAt,
              composeProject: $composeProject, targetService: $targetService,
              capabilities: ["update","pg-backup","health-gate","auto-rollback"],
              runningComposeRevision: $runningComposeRevision}' \
            > "$STATE_DIR/.beat.tmp" && mv -f "$STATE_DIR/.beat.tmp" "$STATE_DIR/updater.json"
        sleep "$POLL_SECONDS"
    done
}

# ---- discovery --------------------------------------------------------------------------------

find_container() {
    local -a filters=(--filter "label=com.docker.compose.service=$SERVICE")
    [[ -n "$PROJECT_FILTER" ]] && filters+=(--filter "label=com.docker.compose.project=$PROJECT_FILTER")
    local ids
    ids=$(docker ps -q "${filters[@]}")
    local count
    count=$(wc -w <<< "$ids")
    if (( count > 1 )); then
        log "ERROR: $count running containers match service '$SERVICE'; set WIKIKT_COMPOSE_PROJECT to disambiguate"
        return 1
    fi
    [[ -n "$ids" ]] || return 1
    echo "$ids"
}

container_label() { docker inspect -f "{{ index .Config.Labels \"$2\" }}" "$1" 2>/dev/null || true; }
image_label() { docker image inspect -f "{{ index .Config.Labels \"$2\" }}" "$1" 2>/dev/null || true; }

# semver_lte A B -> 0 when A <= B (sort -V; good enough for X.Y.Z, which is all releases use)
semver_lte() { [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)" == "$1" ]]; }

# ---- request validation (every failure is a hard ignore with a logged reason) -----------------

validate_request() { # echoes "requestId requestedBy" on success
    local f="$REQ_DIR/request.json"
    [[ -f "$f" ]] || return 1
    local size
    size=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")
    (( size <= 4096 )) || { log "ignoring request: ${size} bytes exceeds 4096"; return 1; }
    jq -e . "$f" >/dev/null 2>&1 || { log "ignoring request: not valid JSON"; return 1; }
    local schema rid rat rby
    schema=$(jq -r '.schema // 0' "$f")
    [[ "$schema" == "1" ]] || { log "ignoring request: schema $schema != 1"; return 1; }
    rid=$(jq -r '.requestId // ""' "$f")
    [[ "$rid" =~ ^[0-9a-f-]{36}$ ]] || { log "ignoring request: malformed requestId"; return 1; }
    rat=$(jq -r '.requestedAt // 0' "$f")
    [[ "$rat" =~ ^[0-9]+$ ]] || { log "ignoring request: malformed requestedAt"; return 1; }
    local now delta
    now=$(now_ms)
    delta=$(( now > rat ? now - rat : rat - now ))
    (( delta <= 900000 )) || { log "ignoring request $rid: requestedAt outside ±15 min"; return 1; }
    if [[ -f "$STATE_DIR/.last-request-id" && "$(cat "$STATE_DIR/.last-request-id")" == "$rid" ]]; then
        return 1 # already handled; silent (the file lingers because /req is read-only to us)
    fi
    # requestedBy is telemetry only: strip to a safe charset, truncate, never a shell word.
    rby=$(jq -r '.requestedBy // ""' "$f" | tr -cd 'A-Za-z0-9._@-' | cut -c1-64)
    echo "$rid $rby"
}

# ---- the update itself ------------------------------------------------------------------------

process_request() { # $1=requestId $2=requestedBy
    REQUEST_ID=$1; STARTED_AT=$(now_ms); FROM_VERSION=""; FROM_DIGEST=""; TO_VERSION=""; TO_DIGEST=""; BACKUP_PATH=""
    append_log "update requested by '${2:-unknown}' (request $REQUEST_ID)"
    write_status preparing false "Locating the running WikiKT container…"

    # 1. Compose identity from the RUNNING CONTAINER'S labels, not env guesses — works with
    #    -p custom-name, multiple -f files, and either compose file, unconfigured.
    local cid
    cid=$(find_container) || { finish failed "No running '$SERVICE' container found."; return 0; }
    local project workdir config_files
    project=$(container_label "$cid" com.docker.compose.project)
    workdir=$(container_label "$cid" com.docker.compose.project.working_dir)
    config_files=$(container_label "$cid" com.docker.compose.project.config_files)
    if [[ -z "$project" || -z "$config_files" ]]; then
        finish failed "Container '$SERVICE' is not compose-managed (missing compose labels)."
        return 0
    fi
    local -a compose=(docker compose -p "$project" --project-directory "$workdir")
    local f missing=""
    IFS=',' read -r -a files <<< "$config_files"
    for f in "${files[@]}"; do
        # `docker compose` inside this container resolves these paths in OUR filesystem, and any
        # relative bind-mount sources against the HOST. Both only line up because the compose dir is
        # mounted at its own host path — the single most important deployment requirement.
        [[ -f "$f" ]] || missing+="$f "
        compose+=(-f "$f")
    done
    if [[ -n "$missing" ]]; then
        finish failed "Compose file(s) not visible: ${missing}— WIKIKT_COMPOSE_DIR must be this project's absolute host path, mounted at that same path."
        return 0
    fi

    # 2. Where we are now. .Image is the image ID; prefer a repo digest for the rollback ref.
    FROM_DIGEST=$(docker inspect -f '{{ .Image }}' "$cid")
    local from_repo_digest
    from_repo_digest=$(docker image inspect -f '{{ if .RepoDigests }}{{ index .RepoDigests 0 }}{{ end }}' "$FROM_DIGEST" 2>/dev/null || true)
    local rollback_ref="${from_repo_digest:-$FROM_DIGEST}"
    FROM_VERSION=$(image_label "$FROM_DIGEST" org.opencontainers.image.version)
    local from_schema from_rev
    from_schema=$(image_label "$FROM_DIGEST" com.wikikt.schema-version)
    from_rev=$(image_label "$FROM_DIGEST" com.wikikt.compose-revision)

    # Self-update needs a pullable image ref; a `build: .` service has nothing to pull.
    local image_ref
    image_ref=$("${compose[@]}" config --format json 2>/dev/null | jq -r ".services[\"$SERVICE\"].image // empty")
    if [[ -z "$image_ref" ]]; then
        finish blocked "The '$SERVICE' service builds from source (no image: in the compose file); update manually with git pull + build."
        return 0
    fi

    # 3. Pull whatever the compose file resolves to. Never an app-supplied ref.
    write_status pulling false "Pulling $image_ref…"
    local pull_out
    if ! pull_out=$(runc "${compose[@]}" pull --quiet "$SERVICE" 2>&1); then
        append_log "pull failed: $pull_out"
        finish failed "Image pull failed; is the registry reachable? (docker compose logs wikikt-updater for detail)"
        return 0
    fi

    # 4. Offline guardrails from the pulled image's labels.
    write_status checking false "Comparing the pulled image against the running one…"
    if (( DRY_RUN )); then
        log "DRY-RUN: would inspect '$image_ref' labels, then stop/start/verify. Ending dry run."
        return 0
    fi
    TO_DIGEST=$(docker image inspect -f '{{ .Id }}' "$image_ref" 2>/dev/null || true)
    TO_VERSION=$(image_label "$image_ref" org.opencontainers.image.version)
    local to_schema to_rev to_min
    to_schema=$(image_label "$image_ref" com.wikikt.schema-version)
    to_rev=$(image_label "$image_ref" com.wikikt.compose-revision)
    to_min=$(image_label "$image_ref" com.wikikt.min-upgrade-from)

    if [[ -n "$TO_DIGEST" && "$TO_DIGEST" == "$FROM_DIGEST" ]]; then
        finish success "Already up to date ($FROM_VERSION); nothing was restarted."
        return 0
    fi
    if [[ "$to_rev" =~ ^[0-9]+$ && "$from_rev" =~ ^[0-9]+$ ]] && (( to_rev > from_rev )); then
        finish blocked "This release requires a Compose file change (revision $from_rev -> $to_rev). Update your compose file per the release notes, then upgrade manually. Nothing was restarted."
        return 0
    fi
    if [[ -n "$to_min" && -n "$FROM_VERSION" ]] && ! semver_lte "$to_min" "$FROM_VERSION"; then
        finish blocked "This release upgrades cleanly only from $to_min or newer (running: $FROM_VERSION). Upgrade stepwise per the release notes. Nothing was restarted."
        return 0
    fi

    # 5. Pre-update database backup (best effort, but a failure aborts — "no backup, no update").
    if "${compose[@]}" config --services 2>/dev/null | grep -qx "$DB_SERVICE"; then
        write_status backing-up false "Taking a pre-update database backup…"
        mkdir -p "$STATE_DIR/backups"
        BACKUP_PATH="$STATE_DIR/backups/pre-update-$(date +%s).dump"
        # pg_dump runs inside the postgres container so client and server versions always match.
        if ! runc "${compose[@]}" exec -T "$DB_SERVICE" pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$BACKUP_PATH" 2>>"$STATE_DIR/.log"; then
            rm -f "$BACKUP_PATH"; BACKUP_PATH=""
            finish failed "Pre-update pg_dump failed; refusing to update without a backup."
            return 0
        fi
        # Retention: newest $BACKUPS_KEEP kept.
        # Names embed epoch seconds, so lexical sort == chronological; newest $BACKUPS_KEEP survive.
        (cd "$STATE_DIR/backups" && find . -maxdepth 1 -name 'pre-update-*.dump' | sort -r | tail -n +"$((BACKUPS_KEEP + 1))" | xargs -r rm -f)
    else
        append_log "no '$DB_SERVICE' service in this project (H2 setup?): skipping pg_dump; the DB file lives in the app volume"
    fi

    # 6. Recreate ONLY the app service. --no-deps is load-bearing: without it Postgres, Caddy — and
    #    this very container — would be recreated mid-run.
    write_status starting false "Restarting $SERVICE on the new image…"
    local up_out
    if ! up_out=$(runc "${compose[@]}" up -d --no-deps "$SERVICE" 2>&1); then
        append_log "up failed: $up_out"
        finish failed "docker compose up failed; the previous container may still be running. Check docker compose logs."
        return 0
    fi

    # 7. Health gate — the whole reason this sidecar exists instead of a plain pull-and-pray.
    write_status verifying false "Waiting for the new container to report healthy (0s/${HEALTH_TIMEOUT}s)…"
    local new_cid="" health="starting" waited=0
    while (( waited < HEALTH_TIMEOUT )); do
        new_cid=$("${compose[@]}" ps -q "$SERVICE" 2>/dev/null || true)
        if [[ -n "$new_cid" ]]; then
            health=$(docker inspect -f '{{ if .State.Health }}{{ .State.Health.Status }}{{ else }}{{ .State.Status }}{{ end }}' "$new_cid" 2>/dev/null || echo unknown)
            case "$health" in
                healthy) break ;;
                unhealthy|exited|dead) break ;;
            esac
        fi
        sleep 5; waited=$(( waited + 5 ))
        write_status verifying false "Waiting for the new container to report healthy (${waited}s/${HEALTH_TIMEOUT}s)…"
    done

    if [[ "$health" == "healthy" ]]; then
        finish success "Updated ${FROM_VERSION:-unknown} -> ${TO_VERSION:-unknown} successfully."
        return 0
    fi

    # 8. Unhealthy. Roll back ONLY when the schema version provably did not change — migrations are
    #    forward-only, so rolling the image back never rolls the database back.
    append_log "new container health: $health after ${waited}s"
    if [[ -n "$to_schema" && "$to_schema" == "$from_schema" ]]; then
        write_status stopping false "New version unhealthy; rolling back to the previous image…"
        printf 'services:\n  %s:\n    image: "%s"\n' "$SERVICE" "$rollback_ref" > /tmp/rollback.yml
        if runc "${compose[@]}" -f /tmp/rollback.yml up -d --no-deps "$SERVICE" >>"$STATE_DIR/.log" 2>&1; then
            finish rolled-back "New version was unhealthy ($health); rolled back to ${FROM_VERSION:-the previous image}. Backup: ${BACKUP_PATH:-n/a}."
        else
            finish failed "New version unhealthy AND rollback failed — manual intervention required. Backup: ${BACKUP_PATH:-n/a}."
        fi
    else
        finish failed "New version is unhealthy ($health) and its database schema differs (${from_schema:-?} -> ${to_schema:-?}), so automatic rollback is NOT safe. Restore manually: stop the stack, restore the backup (${BACKUP_PATH:-n/a}) with pg_restore, pin the previous image, start. See docker/README.md."
    fi
}

# ---- main -------------------------------------------------------------------------------------

if (( DRY_RUN )); then
    log "dry run: validating any pending request and printing planned argv"
    if req=$(validate_request); then
        # shellcheck disable=SC2086  # req is two validated, shell-safe tokens
        process_request $req
    else
        log "dry run: no valid pending request in $REQ_DIR"
    fi
    exit 0
fi

mkdir -p "$STATE_DIR"
# A previous updater crash can leave the lock held with a non-terminal status — recover on start,
# or the doorbell would be dead forever (we cannot delete the request file; /req is read-only).
if [[ -d "$STATE_DIR/.lock" ]]; then
    log "stale lock found from a previous run; clearing it"
    if [[ -f "$STATE_DIR/status.json" ]] && [[ "$(jq -r '.terminal' "$STATE_DIR/status.json" 2>/dev/null)" == "false" ]]; then
        REQUEST_ID=$(jq -r '.requestId // ""' "$STATE_DIR/status.json" 2>/dev/null)
        STARTED_AT=$(jq -r '.startedAt // 0' "$STATE_DIR/status.json" 2>/dev/null)
        write_status failed true "The updater was interrupted mid-run; check the app and updater logs."
    fi
    rmdir "$STATE_DIR/.lock" 2>/dev/null || true
fi

heartbeat_loop &
log "watching $REQ_DIR/request.json for service '$SERVICE' (poll ${POLL_SECONDS}s)"

while :; do
    if req=$(validate_request); then
        if mkdir "$STATE_DIR/.lock" 2>/dev/null; then
            # shellcheck disable=SC2086  # req is two validated, shell-safe tokens
            process_request $req
        fi
    fi
    sleep "$POLL_SECONDS"
done
