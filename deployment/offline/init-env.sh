#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
if [ -e .env ] || [ -L .env ]; then
  printf '기존 .env를 유지합니다. 기존 DB와 함께 사용하는 암호화 키를 보존하세요.\n'
  exit 0
fi
require_runtime
verify_images
umask 077
env_temporary=$(mktemp "$bundle_root/.env.new.XXXXXX")
trap 'rm -f "$env_temporary"' EXIT HUP INT TERM
# Randomness comes from the shipped image; host OpenSSL/Java/Python are unnecessary.
docker run --rm --pull never --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --entrypoint sh "$app_image" -c '
    set -eu
    db_password=$(openssl rand -hex 24)
    initial_admin_password=$(openssl rand -hex 18)
    credential_key=$(openssl rand -base64 32)
    printf "DATABASE_PASSWORD=%s\n" "$db_password"
    printf "OBSERVER_ADMIN_USER=admin\n"
    printf "OBSERVER_ADMIN_PASSWORD=%s\n" "$initial_admin_password"
    printf "OBSERVER_ENCRYPTION_KEY=%s\n" "$credential_key"
    printf "OBSERVER_BIND_ADDRESS=127.0.0.1\nOBSERVER_PORT=18080\nOBSERVER_MIRROR_HOSTS=\nCOOKIE_SECURE=false\n"
  ' > "$env_temporary"
# Hard-link creation is atomic and refuses to replace a concurrently created .env.
ln "$env_temporary" "$bundle_root/.env" || fail '.env가 이미 생겼거나 생성할 수 없습니다. 기존 파일은 변경하지 않았습니다.'
printf '새 .env를 권한 600으로 생성했습니다. 초기 비밀번호는 OBSERVER_ADMIN_PASSWORD 항목에서 확인하세요.\n'
