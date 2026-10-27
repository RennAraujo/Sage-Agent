# Gera 32 bytes aleatórios em base64 em secrets\local-master.key (diretório ignorado pelo git).
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root 'secrets'
$target = Join-Path $dir 'local-master.key'

if (Test-Path $target) {
    Write-Error "$target já existe. Apague-o à mão se quiser trocar a chave (os dados cifrados com a atual ficam ilegíveis)."
}

New-Item -ItemType Directory -Force -Path $dir | Out-Null
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[System.IO.File]::WriteAllText($target, [Convert]::ToBase64String($bytes), [System.Text.UTF8Encoding]::new($false))
Write-Host "Chave mestra local gerada em $target"
