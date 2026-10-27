#!/usr/bin/env bash
# Baixa os arquivos listados em models.lock para models/ e confere o sha256 de cada um.
# Uso: scripts/download-models.sh [diretório de destino, padrão: models]
# Se o hash divergir, apaga o arquivo baixado e sai com erro.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="${1:-$root/models}"
mkdir -p "$dest"

while read -r name sha url; do
  case "$name" in ''|'#'*) continue ;; esac
  target="$dest/$name"
  if [ -f "$target" ] && [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$sha" ]; then
    echo "ok (já presente): $name"
    continue
  fi
  echo "baixando $name"
  curl --fail --silent --show-error --location --output "$target.part" "$url"
  actual="$(sha256sum "$target.part" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then
    rm -f "$target.part"
    echo "sha256 divergente em $name: esperado $sha, obtido $actual" >&2
    exit 1
  fi
  mv "$target.part" "$target"
  echo "ok: $name"
done < <(tr -d '\r' < "$root/models.lock")
