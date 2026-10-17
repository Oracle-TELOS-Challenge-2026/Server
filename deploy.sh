#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
BINTIME_KEY="${BINTIME_SSH_KEY:-$HOME/Downloads/team3/team-3-1.key}"
ssh_options=(-i "$BINTIME_KEY" -o BatchMode=yes -o ConnectTimeout=10)

if ! docker info >/dev/null 2>&1; then
  echo 'Docker Desktop을 먼저 실행한 뒤 다시 배포해 주세요.' >&2
  exit 1
fi
if [[ -n "$(git diff --name-only --diff-filter=U)" ]]; then
  echo 'Git 병합 충돌을 먼저 해결해 주세요.' >&2
  exit 1
fi
BINTIME_REVISION=$(git rev-parse HEAD)
if [[ -n "$(git status --porcelain)" ]]; then BINTIME_REVISION+="-dirty"; fi
echo "배포할 코드: $BINTIME_REVISION"

# HTTPS 인증서를 최초 발급한 서버에만 배포한다.
ssh "${ssh_options[@]}" opc@140.238.19.165 \
  'sudo -n test -s /home/opc/bintime/infra/certbot/conf/live/140.238.19.165/fullchain.pem &&
   sudo -n test -s /home/opc/bintime/infra/certbot/conf/live/140.238.19.165/privkey.pem'

./gradlew build --no-daemon

docker build \
  --platform linux/arm64 \
  --label "org.opencontainers.image.revision=$BINTIME_REVISION" \
  -t bintime-backend:local .

docker save bintime-backend:local \
  | ssh "${ssh_options[@]}" opc@140.238.19.165 \
    'sudo -n docker load'

scp "${ssh_options[@]}" infra/nginx/default.conf \
  opc@140.238.19.165:bintime/infra/nginx/default.conf
scp "${ssh_options[@]}" compose.yaml compose.fcm.yaml opc@140.238.19.165:bintime/
scp "${ssh_options[@]}" infra/certbot/renew.sh \
  infra/certbot/bintime-cert-renew.service infra/certbot/bintime-cert-renew.timer \
  opc@140.238.19.165:bintime/infra/certbot/

ssh "${ssh_options[@]}" opc@140.238.19.165 bash -s -- "$BINTIME_REVISION" <<'REMOTE'
set -euo pipefail
cd ~/bintime
sudo -n install -m 0644 infra/certbot/bintime-cert-renew.service infra/certbot/bintime-cert-renew.timer /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now bintime-cert-renew.timer

compose_files=(-f compose.yaml)
sudo -n docker compose "${compose_files[@]}" config --quiet </dev/null
# Compose가 서버 .env의 따옴표와 빈 값을 해석하고, 검사 결과만 반환한다.
fcm_configured=$(
  sudo -n docker compose "${compose_files[@]}" config --environment </dev/null |
    awk '/^FCM_CREDENTIALS_FILE=./ { configured=1 } END { print configured+0 }'
)
if [[ "$fcm_configured" == 1 ]]; then
  compose_files+=(-f compose.fcm.yaml)
fi
sudo -n docker compose "${compose_files[@]}" config --quiet </dev/null
expected_image=$(sudo -n docker image inspect --format '{{.Id}}' bintime-backend:local)
sudo -n docker compose "${compose_files[@]}" up --detach --no-build --wait --wait-timeout 180 </dev/null
app_id=$(sudo -n docker compose "${compose_files[@]}" ps --quiet app </dev/null)
if [[ -z "$app_id" ]] || [[ "$(sudo -n docker inspect --format '{{.Image}}' "$app_id")" != "$expected_image" ]]; then
  echo '전송한 새 이미지로 앱이 실행되지 않았습니다.' >&2
  exit 1
fi
if [[ "$(sudo -n docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$app_id")" != "$1" ]]; then
  echo '실행 중인 앱의 코드 버전이 이번 배포와 다릅니다.' >&2
  exit 1
fi
sudo -n docker compose "${compose_files[@]}" exec -T nginx nginx -t </dev/null
sudo -n docker compose "${compose_files[@]}" exec -T nginx nginx -s reload </dev/null
curl --fail --silent --show-error --max-time 10 --resolve 140.238.19.165:443:127.0.0.1 https://140.238.19.165/healthz
printf '\n실행 중인 앱 코드: %s\n' "$1"
REMOTE

curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
  https://140.238.19.165/healthz
