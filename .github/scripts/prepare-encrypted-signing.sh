#!/usr/bin/env bash
set -euo pipefail
# Run locally from the repository root. Existing keystore identity is preserved.
# Paste the printed value into GitHub Actions secret SIGNING_KEYSTORE_ENC_BASE64.
# Store the passphrase as GitHub Actions secret SIGNING_PASSPHRASE.
test -s app/debug.keystore || { echo "Missing original app/debug.keystore" >&2; exit 1; }
: "${SIGNING_PASSPHRASE:?Set a long, randomly generated SIGNING_PASSPHRASE first}"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
umask 077
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
  -pass env:SIGNING_PASSPHRASE -in app/debug.keystore -out "$tmp"
printf '\nEncrypted keystore base64 (paste as SIGNING_KEYSTORE_ENC_BASE64):\n'
base64 -w0 "$tmp"
printf '\n'
echo "Only delete the committed keystore after CI successfully verifies the encrypted signing identity."
