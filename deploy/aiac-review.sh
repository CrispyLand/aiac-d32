#!/usr/bin/env bash
# Forced-command script for the restricted review SSH key.
# Reads a JSON payload from stdin, forwards it to the local review endpoint,
# and returns the JSON response on stdout.
#
# Installed at /opt/aiac/bin/aiac-review on the VPS.
# The SSH authorized_keys entry uses:
#   command="/opt/aiac/bin/aiac-review",restrict ssh-ed25519 AAAA... github-actions-review

set -euo pipefail

# This key can only run this script — ignore whatever the SSH client asked for.
unset SSH_ORIGINAL_COMMAND

# Size limit: 1MB. Read at most MAX_SIZE+1 bytes; reject if the payload exceeds it.
MAX_SIZE=1048576
PAYLOAD=$(head -c $((MAX_SIZE + 1)))
SIZE=${#PAYLOAD}

if [ "$SIZE" -eq 0 ]; then
    printf '{"error":"empty payload"}\n' >&2
    exit 1
fi
if [ "$SIZE" -gt "$MAX_SIZE" ]; then
    printf '{"error":"payload too large (%d bytes, max %d)"}\n' "$SIZE" "$MAX_SIZE" >&2
    exit 1
fi

# Forward to the agent's review endpoint on localhost.
# --data-binary @- reads from stdin; printf avoids trailing newline issues.
printf '%s' "$PAYLOAD" | curl -sf --max-time 120 \
    -H 'Content-Type: application/json' \
    --data-binary @- \
    http://127.0.0.1:8080/api/review
