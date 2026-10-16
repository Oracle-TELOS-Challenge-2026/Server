#!/usr/bin/env bash
set -euo pipefail

BINTIME_KEY="${BINTIME_SSH_KEY:-$HOME/Downloads/team3/team-3-1.key}"
BINTIME_FRONTEND_DIST="${BINTIME_FRONTEND_DIST:-}"
has_frontend=0
if [[ -n "$BINTIME_FRONTEND_DIST" ]]; then
  if [[ ! -s "$BINTIME_FRONTEND_DIST/index.html" || ! -d "$BINTIME_FRONTEND_DIST/assets" ]]; then
    echo 'BINTIME_FRONTEND_DIST는 index.html과 assets가 있는 프론트 dist 폴더여야 합니다.' >&2
    exit 1
  fi
  if [[ -n "$(find "$BINTIME_FRONTEND_DIST" -type l -print -quit)" ]]; then
    echo '프론트 dist에 심볼릭 링크를 넣지 마세요.' >&2
    exit 1
  fi
  has_frontend=1
fi

# 공개 설정과 화면 파일을 먼저 확인해 로그인 복귀 주소만 바뀌는 배포를 막는다.
ssh -i "$BINTIME_KEY" opc@140.238.19.165 bash -s -- "$has_frontend" <<'PREFLIGHT'
set -euo pipefail
cd ~/bintime
sudo -n test -s infra/certbot/conf/live/140.238.19.165/fullchain.pem
sudo -n test -s infra/certbot/conf/live/140.238.19.165/privkey.pem
frontend_origin=$(
  sudo -n docker compose -f compose.yaml config --environment </dev/null |
    awk '/^FRONTEND_URL=/ { sub(/^FRONTEND_URL=/, ""); print; found=1 }
         END { if (!found) print "https://140.238.19.165" }'
)
if [[ "$frontend_origin" != https://140.238.19.165 ]]; then
  echo '서버 ~/bintime/.env의 FRONTEND_URL을 https://140.238.19.165로 변경하세요.' >&2
  exit 1
fi
if [[ "$1" == 0 && ! -s infra/frontend/dist/index.html ]]; then
  echo '최초 배포에는 BINTIME_FRONTEND_DIST=/프론트/dist bash deploy.sh를 사용하세요.' >&2
  exit 1
fi
PREFLIGHT

./gradlew build --no-daemon

docker build \
  --platform linux/arm64 \
  -t bintime-backend:local .

docker save bintime-backend:local \
  | ssh -i "$BINTIME_KEY" opc@140.238.19.165 \
    'sudo -n docker load'

scp -i "$BINTIME_KEY" infra/nginx/default.conf \
  opc@140.238.19.165:bintime/infra/nginx/default.conf
scp -i "$BINTIME_KEY" compose.yaml compose.fcm.yaml opc@140.238.19.165:bintime/
scp -i "$BINTIME_KEY" infra/certbot/renew.sh \
  infra/certbot/bintime-cert-renew.service infra/certbot/bintime-cert-renew.timer \
  opc@140.238.19.165:bintime/infra/certbot/

if [[ "$has_frontend" == 1 ]]; then
  COPYFILE_DISABLE=1 tar --no-xattrs --exclude='.DS_Store' --exclude='._*' \
    -C "$BINTIME_FRONTEND_DIST" -czf - . |
    ssh -i "$BINTIME_KEY" opc@140.238.19.165 '
      set -euo pipefail
      stage=$(mktemp -d)
      trap "rm -rf -- \"$stage\"" EXIT
      tar --no-same-owner -xzf - -C "$stage"
      test -s "$stage/index.html"
      install -d -m 0755 /home/opc/bintime/infra/frontend/dist
      # 전송이 끝난 뒤 파일을 복사하고, 새 HTML은 마지막에 교체한다.
      # 기존 assets는 열려 있는 화면이 계속 쓸 수 있도록 유지한다.
      mv "$stage/index.html" "$stage/.index.html.next"
      cp -R "$stage"/. /home/opc/bintime/infra/frontend/dist/
      chmod 0755 /home/opc/bintime/infra/frontend/dist
      mv -f /home/opc/bintime/infra/frontend/dist/.index.html.next \
        /home/opc/bintime/infra/frontend/dist/index.html
    '
fi

ssh -i "$BINTIME_KEY" opc@140.238.19.165 bash -s <<'REMOTE'
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
sudo -n docker compose "${compose_files[@]}" up --detach --no-build --wait --wait-timeout 180 </dev/null
sudo -n docker compose "${compose_files[@]}" exec -T nginx nginx -t </dev/null
sudo -n docker compose "${compose_files[@]}" exec -T nginx nginx -s reload </dev/null
curl --fail --silent --show-error --max-time 10 --resolve 140.238.19.165:443:127.0.0.1 https://140.238.19.165/healthz
REMOTE

curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
  https://140.238.19.165/healthz
curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
  --output /dev/null https://140.238.19.165/
