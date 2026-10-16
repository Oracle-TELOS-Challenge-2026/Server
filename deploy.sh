#!/usr/bin/env bash
set -euo pipefail

BINTIME_KEY="${BINTIME_SSH_KEY:-$HOME/Downloads/team3/team-3-1.key}"

# HTTPS 인증서를 최초 발급한 서버에만 배포한다.
ssh -i "$BINTIME_KEY" opc@140.238.19.165 \
  'sudo -n test -s /home/opc/bintime/infra/certbot/conf/live/140.238.19.165/fullchain.pem &&
   sudo -n test -s /home/opc/bintime/infra/certbot/conf/live/140.238.19.165/privkey.pem'

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
