#!/bin/sh
set -eu
umask 077
# Fail closed: never automatically redeploy an existing release/chain namespace.
test ! -e /release/chain.properties || { echo 'Release already initialized; refusing redeploy' >&2; exit 1; }
test ! -e /release/init-attempt || { echo 'Previous initialization attempt exists; inspect chain, do not redeploy automatically' >&2; exit 1; }
touch /release/init-attempt
export OPERATOR_PRIVATE_KEY="$(sed -n 's/^OPERATOR_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
export PRICE_SIGNER_PRIVATE_KEY="$(sed -n 's/^PRICE_SIGNER_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
# Keys remain in the private process environment, not argv or output.
forge script script/DeployPublic.s.sol:DeployPublic --rpc-url http://anvil:8545 --broadcast --offline --quiet > /release/private-init.log 2>&1 || {
    echo 'Initialization failed; inspect restricted private-init.log on host, do not paste secret-bearing diagnostics' >&2
    exit 1
}
test -s /release/chain.properties
chown 0:10001 /release/chain.properties
chmod 640 /release/chain.properties
echo 'Private demo chain initialized. Verify contracts before starting backend.'
