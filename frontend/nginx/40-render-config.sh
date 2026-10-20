#!/bin/sh
set -eu

# Shared deployment access is server-only. Never export it as a frontend env var.
secret_file=/run/secrets/progress_subscription_token
if [ ! -r "$secret_file" ]; then
    echo 'Missing progress secret. Run the deployment initialization script first.' >&2
    exit 1
fi
progress_token=$(cat "$secret_file")
case "$progress_token" in
    ''|*[!A-Za-z0-9_-]*)
        echo 'Progress secret must contain only letters, digits, _ or -.' >&2
        exit 1
        ;;
esac
if [ "${#progress_token}" -lt 32 ] || [ "${#progress_token}" -gt 128 ]; then
    echo 'Progress secret must be 32-128 characters.' >&2
    exit 1
fi

nginx_listen=${AIDA_NGINX_LISTEN:-80}
backend_url=${AIDA_BACKEND_URL:-http://backend:8080}
# Validate before sed so configuration values cannot inject directives or substitutions.
if ! printf '%s' "$nginx_listen" | grep -Eq '^((127\.0\.0\.1|0\.0\.0\.0):)?[0-9]{1,5}$'; then
    echo 'Invalid AIDA_NGINX_LISTEN.' >&2
    exit 1
fi
if ! printf '%s' "$backend_url" | grep -Eq '^http://(backend|127\.0\.0\.1):[0-9]{1,5}$'; then
    echo 'Invalid AIDA_BACKEND_URL.' >&2
    exit 1
fi

umask 077
sed -e "s/__PROGRESS_SUBSCRIPTION_TOKEN__/$progress_token/g" \
    -e "s|__AIDA_NGINX_LISTEN__|$nginx_listen|g" \
    -e "s|__AIDA_BACKEND_URL__|$backend_url|g" \
    /etc/nginx/app.conf.template > /etc/nginx/conf.d/default.conf
unset progress_token nginx_listen backend_url
nginx -t
