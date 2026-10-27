#!/usr/bin/env bash
# Gera 32 bytes aleatórios em base64 em secrets/local-master.key (diretório ignorado pelo git).
set -euo pipefail

cd "$(dirname "$0")/.."
target="secrets/local-master.key"

if [ -e "$target" ]; then
    echo "$target já existe. Apague-o à mão se quiser trocar a chave (os dados cifrados com a atual ficam ilegíveis)." >&2
    exit 1
fi

umask 077
mkdir -p secrets
head -c 32 /dev/urandom | base64 > "$target"
chmod 600 "$target"
echo "Chave mestra local gerada em $target"
