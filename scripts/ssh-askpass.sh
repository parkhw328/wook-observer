#!/bin/sh
# OpenSSH invokes this installed helper; the secret itself is never an argument or environment value.
set -eu
test -n "${OBSERVER_SSH_SECRET_FILE:-}"
exec cat -- "$OBSERVER_SSH_SECRET_FILE"
