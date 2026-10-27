#!/usr/bin/env bash
# Generates an API key ("sage_" + 40 base62 chars) from the OS CSPRNG and prints it with its SHA-256 (hex).
# Only the hash goes into the configuration (sage.auth.keys[].sha256). The key itself is shown once: store it somewhere safe.
set -euo pipefail

# Base64 of random bytes, keeping only base62 characters (dropping '+', '/' and '=' keeps the rest uniform).
# Reads fixed-size chunks until there are 40 chars; no pipe is closed early, so pipefail has nothing to trip on.
body=""
attempts=0
while [ "${#body}" -lt 40 ]; do
    attempts=$((attempts + 1))
    if [ "$attempts" -gt 100 ]; then
        echo "Could not read enough random data from /dev/urandom." >&2
        exit 1
    fi
    body="${body}$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
done
body="${body:0:40}"
key="sage_${body}"

if command -v sha256sum >/dev/null 2>&1; then
    hash="$(printf '%s' "$key" | sha256sum | cut -d' ' -f1)"
else
    hash="$(printf '%s' "$key" | shasum -a 256 | cut -d' ' -f1)"
fi

echo "API key (give it to the client, it is not stored anywhere): $key"
echo "SHA-256 (goes into the configuration):                       $hash"
echo
echo "application.yml:"
echo "  sage:"
echo "    auth:"
echo "      keys:"
echo "        - name: <short-name>"
echo "          role: EDITOR   # or READER"
echo "          sha256: $hash"
