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

# OAuth 시작·콜백 주소와 실제 프록시를 먼저 확인한다.
ssh -i "$BINTIME_KEY" opc@140.238.19.165 bash -s -- "$has_frontend" <<'PREFLIGHT'
set -euo pipefail
cd ~/bintime
sudo -n test -s infra/certbot/conf/live/140.238.19.165/fullchain.pem
sudo -n test -s infra/certbot/conf/live/140.238.19.165/privkey.pem
frontend_origin=$(
  sudo -n docker compose -f compose.yaml config --environment </dev/null |
    awk '/^FRONTEND_URL=/ { sub(/^FRONTEND_URL=/, ""); print; found=1 }
         END { if (!found) print "https://frontend-oracle.vercel.app" }'
)
callback_uri=$(
  sudo -n docker compose -f compose.yaml config --environment </dev/null |
    awk '/^KAKAO_REDIRECT_URI=/ { sub(/^KAKAO_REDIRECT_URI=/, ""); print; found=1 }
         END { if (!found) print "https://frontend-oracle.vercel.app/api/v1/auth/oauth/kakao/callback" }'
)
case "$frontend_origin" in
  https://140.238.19.165|https://frontend-oracle.vercel.app) ;;
  *) echo 'FRONTEND_URL은 등록된 IP 또는 Vercel 운영 주소여야 합니다.' >&2; exit 1 ;;
esac
if [[ "$callback_uri" != "$frontend_origin/api/v1/auth/oauth/kakao/callback" ]]; then
  echo 'KAKAO_REDIRECT_URI와 FRONTEND_URL의 운영 호스트를 맞추세요.' >&2
  exit 1
fi
if [[ "$frontend_origin" == https://frontend-oracle.vercel.app ]]; then
  # SPA의 HTML 200을 정상 API로 오인하지 않도록 비로그인 JSON 401을 검사한다.
  curl --silent --show-error --connect-timeout 5 --max-time 15 --include \
    "$frontend_origin/api/v1/users/me" |
    python3 -c '
import json, sys
headers, _, body = sys.stdin.read().replace("\r\n", "\n").partition("\n\n")
if not headers.startswith("HTTP/") or headers.splitlines()[0].split()[1] != "401" or "application/json" not in headers.lower():
    sys.exit("Vercel /api 프록시가 준비되지 않았습니다.")
if not isinstance(json.loads(body), dict):
    sys.exit("Vercel /api 응답이 JSON 객체가 아닙니다.")
'
elif [[ "$1" == 0 && ! -s infra/frontend/dist/index.html ]]; then
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
if [[ "$has_frontend" == 1 ]]; then
  curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
    --output /dev/null https://140.238.19.165/
fi
