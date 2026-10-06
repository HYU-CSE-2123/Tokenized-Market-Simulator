#!/bin/sh
set -eu
# Only this private demo chain. Loading a checkpoint must not freeze the report clock.
rm -f /tmp/anvil-ready
anvil "$@" &
anvil_pid=$!
trap 'kill -INT "$anvil_pid" 2>/dev/null || true; wait "$anvil_pid" || true; exit 0' TERM INT
attempt=0
until timestamp=$(cast block latest --field timestamp --rpc-url http://127.0.0.1:8545 2>/dev/null); do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ] || ! kill -0 "$anvil_pid" 2>/dev/null; then
        kill -INT "$anvil_pid" 2>/dev/null || true
        wait "$anvil_pid" || true
        exit 1
    fi
    sleep 1
done
now=$(date +%s)
if [ "$timestamp" -gt "$((now + 2))" ]; then
    echo 'Chain clock is ahead of host; refusing readiness. Check clock, do not reset state.' >&2
    kill -INT "$anvil_pid"; wait "$anvil_pid" || true; exit 1
fi
if [ "$now" -gt "$timestamp" ]; then
    cast rpc evm_setNextBlockTimestamp "$now" --rpc-url http://127.0.0.1:8545 >/dev/null
    cast rpc evm_mine --rpc-url http://127.0.0.1:8545 >/dev/null
fi
: > /tmp/anvil-ready
wait "$anvil_pid"
