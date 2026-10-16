#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

docker run --rm \
  -v "$PWD/infra/certbot/conf:/etc/letsencrypt:z" \
  -v "$PWD/infra/certbot/www:/var/www/certbot:z" \
  -v "$PWD/infra/certbot/log:/var/log/letsencrypt:z" \
  certbot/certbot:v5.8.0 renew --quiet

docker exec bintime-nginx-1 nginx -t
docker exec bintime-nginx-1 nginx -s reload
