#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
require_runtime
command -v sha256sum >/dev/null 2>&1 || fail 'sha256sum 명령이 필요합니다.'
printf '1/3 반입 파일 SHA-256 확인\n'
sha256sum -c SHA256SUMS
printf '2/3 로컬 이미지 불러오기\n'
while read -r reference expected archive; do
  [ -n "$reference" ] || continue
  docker load --input "$bundle_root/$archive"
done < images/images.list
verify_images
printf '3/3 초기 설정 확인\n'
./init-env.sh
printf '\n준비 완료. .env의 비밀번호·포트·접속 주소를 확인한 뒤 ./start.sh를 실행하세요.\n'
