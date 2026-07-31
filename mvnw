#!/bin/sh

set -eu

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

echo "mvn was not found in PATH." >&2
echo "Install Maven or update IntelliJ to use a bundled/local Maven distribution." >&2
exit 1
