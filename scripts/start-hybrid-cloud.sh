#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    echo 'This bundle requires a Linux amd64 cloud host.' >&2
    exit 1
fi
for file in .env.hybrid.cloud secrets/hybrid_progress_subscription_token images.tar images.tar.sha256; do
    if [[ ! -f "$file" ]]; then
        echo "Missing deployment file: $file" >&2
        exit 1
    fi
done
chmod 700 secrets
chmod 600 .env.hybrid.cloud
# Compose file-backed secrets retain host permissions. The backend runs as app,
# so allow container reads while the host's 0700 parent protects the file.
chmod 444 secrets/hybrid_progress_subscription_token
docker compose version > /dev/null
docker info > /dev/null

# Never source the env file as shell code or print resolved secrets.
compose=(docker compose --env-file .env.hybrid.cloud -f compose.hybrid.cloud.yaml)
if [[ -f compose.hybrid.public.yaml && -f .env.hybrid.public ]]; then
    compose+=(-f compose.hybrid.public.yaml)
fi
"${compose[@]}" config --quiet
for port in 13307 16380 15673 16333 16334 19200; do
    if ! timeout 5 bash -c 'exec 3<>/dev/tcp/127.0.0.1/"$1"' _ "$port" 2>/dev/null; then
        echo "SSH reverse forward 127.0.0.1:$port is unavailable. Start the Windows tunnel first." >&2
        exit 1
    fi
done

sha256sum --check images.tar.sha256
docker image load --input images.tar
"${compose[@]}" up -d --wait --wait-timeout 420
"${compose[@]}" ps
# Nginx alone is not enough: require a real API request to the configured database.
curl --fail --silent --show-error --max-time 20 \
    'http://127.0.0.1:8088/api/v1/knowledge-bases?page=0&size=1' > /dev/null
echo 'Cloud frontend, backend health, and database-backed API passed.'
echo 'Model calls, document ingestion, and WebSocket events still require the business acceptance checks.'
