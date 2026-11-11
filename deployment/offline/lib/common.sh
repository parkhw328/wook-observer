#!/bin/sh
# This file is sourced by the bundle's top-level helpers.
set -eu
bundle_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$bundle_root"
app_image=wook-observer:1.2.1

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

require_runtime() {
  [ "$(uname -s)" = Linux ] || fail '이 묶음은 Linux용입니다.'
  case "$(uname -m)" in x86_64|amd64) ;; *) fail '이 묶음은 x86_64 / amd64 CPU용입니다.';; esac
  command -v docker >/dev/null 2>&1 || fail 'Docker Engine/CLI가 필요합니다.'
  docker info >/dev/null 2>&1 || fail 'Docker 데몬 실행 상태와 현재 계정의 접속 권한을 확인하세요.'
  docker compose version >/dev/null 2>&1 || fail 'docker compose 명령을 제공하는 Compose 플러그인이 필요합니다.'
}

verify_images() {
  command -v sha256sum >/dev/null 2>&1 || fail 'sha256sum 명령이 필요합니다.'
  image_template=$(cat "$bundle_root/lib/image-fingerprint.template")
  while read -r reference expected archive; do
    [ -n "$reference" ] || continue
    material=$(docker image inspect "$reference" --format "$image_template" 2>/dev/null) || fail "이미지가 없습니다: $reference — ./install.sh를 실행하세요."
    actual=$(printf '%s\n' "$material" | sha256sum)
    actual=${actual%% *}
    [ "$actual" = "$expected" ] || fail "이미지의 실행 설정/파일시스템 레이어가 배포 묶음과 다릅니다: $reference"
    platform=$(docker image inspect "$reference" --format '{{.Os}}/{{.Architecture}}')
    [ "$platform" = linux/amd64 ] || fail "지원하지 않는 이미지 아키텍처: $reference / $platform"
  done < "$bundle_root/images/images.list"
}

require_environment() {
  [ -f "$bundle_root/.env" ] || fail '.env가 없습니다. ./install.sh로 초기 설정을 생성하세요.'
}

compose() {
  docker compose --project-directory "$bundle_root" --env-file "$bundle_root/.env" -f "$bundle_root/compose.yaml" "$@"
}
