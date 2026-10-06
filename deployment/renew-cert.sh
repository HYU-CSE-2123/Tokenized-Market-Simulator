#!/bin/sh
set -eu
# Run on Linux host under systemd timer; arguments are non-secret config only.
test "$#" -eq 3 || { echo 'usage: renew-cert.sh ENV_FILE CERT_ROOT PINNED_CERTBOT_IMAGE' >&2; exit 2; }
env_file=$1
cert_root=$2
image=$3
case "$cert_root" in /*) ;; *) echo 'Absolute certificate root required' >&2; exit 2;; esac
case "$image" in *@sha256:*) ;; *) echo 'Pin tested Certbot image digest' >&2; exit 2;; esac
docker run --rm -v "$cert_root/letsencrypt:/etc/letsencrypt" -v "$cert_root/acme:/var/www/acme" "$image" renew --webroot -w /var/www/acme --quiet
# Store live regular files, not symlinks to an unmounted archive directory.
test -f "$cert_root/domain" || { echo 'Certificate domain file required' >&2; exit 1; }
domain=$(tr -d '\r\n' < "$cert_root/domain")
case "$domain" in *[!a-zA-Z0-9.-]*|'') echo 'Invalid domain' >&2; exit 2;; esac
install -m 644 "$cert_root/letsencrypt/live/$domain/fullchain.pem" "$cert_root/live/fullchain.pem.next"
install -m 600 "$cert_root/letsencrypt/live/$domain/privkey.pem" "$cert_root/live/privkey.pem.next"
mv "$cert_root/live/fullchain.pem.next" "$cert_root/live/fullchain.pem"
mv "$cert_root/live/privkey.pem.next" "$cert_root/live/privkey.pem"
deployment_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
docker compose --env-file "$env_file" -f "$deployment_dir/compose.yml" exec -T web nginx -t
docker compose --env-file "$env_file" -f "$deployment_dir/compose.yml" exec -T web nginx -s reload
