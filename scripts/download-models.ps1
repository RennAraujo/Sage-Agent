# Baixa os arquivos listados em models.lock para models/ e confere o sha256 de cada um.
# Uso: .\scripts\download-models.ps1 [-Dest <diretório>]   (padrão: models)
# Se o hash divergir, apaga o arquivo baixado e sai com erro.
param([string]$Dest)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
if (-not $Dest) { $Dest = Join-Path $root 'models' }
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

foreach ($line in Get-Content -Path (Join-Path $root 'models.lock') -Encoding UTF8) {
    if ($line -match '^\s*(#|$)') { continue }
    $name, $sha, $url = $line -split '\s+', 3
    $target = Join-Path $Dest $name
    if ((Test-Path $target) -and ((Get-FileHash -Algorithm SHA256 $target).Hash -ieq $sha)) {
        Write-Host "ok (already present): $name"
        continue
    }
    Write-Host "baixando $name"
    $part = "$target.part"
    Invoke-WebRequest -Uri $url -OutFile $part -UseBasicParsing
    $actual = (Get-FileHash -Algorithm SHA256 $part).Hash
    if ($actual -ine $sha) {
        Remove-Item -Force $part
        Write-Error "sha256 divergente em ${name}: esperado $sha, obtido $actual"
        exit 1
    }
    Move-Item -Force $part $target
    Write-Host "ok: $name"
}
