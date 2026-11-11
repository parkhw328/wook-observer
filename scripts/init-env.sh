#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; preserving existing credentials.' >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { echo 'openssl is required.' >&2; exit 1; }
umask 077
# noclobber also prevents a concurrent invocation from replacing credentials.
set -C
{
  printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'OBSERVER_ADMIN_USER=admin\n'
  printf 'OBSERVER_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 18)"
  printf 'OBSERVER_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
  printf 'OBSERVER_BIND_ADDRESS=127.0.0.1\nOBSERVER_PORT=18080\nOBSERVER_MIRROR_HOSTS=\nCOOKIE_SECURE=false\n'
} > .env
printf 'Created private .env (mode 600). Read OBSERVER_ADMIN_PASSWORD locally for the initial login.\n'
