#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077
gateway_only=false
case "${1:-}" in
    '') ;;
    --gateway-only) gateway_only=true ;;
    *) echo 'Unknown public deployment option.' >&2; exit 1 ;;
esac
files=(.env.hybrid.cloud .env.hybrid.public compose.hybrid.cloud.yaml compose.hybrid.public.yaml deploy/hybrid-public/Caddyfile)
if [[ "$gateway_only" == false ]]; then
    files+=(public-images.tar public-images.tar.sha256)
fi
for file in "${files[@]}"; do
    if [[ ! -f "$file" ]]; then
        echo "Missing public deployment file: $file" >&2
        exit 1
    fi
done
chmod 600 .env.hybrid.public
compose=(docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml -f compose.hybrid.public.yaml)
"${compose[@]}" config --quiet
if [[ "$gateway_only" == false ]]; then
    sha256sum --check public-images.tar.sha256
    docker image load --input public-images.tar
fi
"${compose[@]}" run --rm --no-deps gateway caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
if [[ "$gateway_only" == false ]]; then
    "${compose[@]}" up -d --wait --wait-timeout 300 backend frontend
fi
# The Caddyfile is a bind mount; recreate the gateway so configuration-only
# updates are applied even when its image and environment are unchanged.
"${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 60 gateway
"${compose[@]}" ps
echo 'Public gateway is running. Verify HTTPS certificate, authentication, REST and WebSocket from outside ECS.'
