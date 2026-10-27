# Generates an API key ("sage_" + 40 base62 chars) from a CSPRNG and prints it with its SHA-256 (hex).
# Only the hash goes into the configuration (sage.auth.keys[].sha256). The key itself is shown once: store it somewhere safe.
# Text kept ASCII-only on purpose, so it works in Windows PowerShell 5.1 regardless of file encoding.
$ErrorActionPreference = 'Stop'

$alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
$length = 40
# 248 = 4 * 62: bytes >= 248 are discarded, so every character is equally likely (no modulo bias).
$limit = 248

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$buffer = New-Object byte[] 1
$body = New-Object System.Text.StringBuilder
while ($body.Length -lt $length) {
    $rng.GetBytes($buffer)
    if ($buffer[0] -lt $limit) {
        [void]$body.Append($alphabet[$buffer[0] % 62])
    }
}
$rng.Dispose()

$key = 'sage_' + $body.ToString()
$sha = [System.Security.Cryptography.SHA256]::Create()
$hash = ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($key)) | ForEach-Object { $_.ToString('x2') }) -join ''
$sha.Dispose()

Write-Host "API key (give it to the client, it is not stored anywhere): $key"
Write-Host "SHA-256 (goes into the configuration):                       $hash"
Write-Host ''
Write-Host 'application.yml:'
Write-Host '  sage:'
Write-Host '    auth:'
Write-Host '      keys:'
Write-Host '        - name: <short-name>'
Write-Host '          role: EDITOR   # or READER'
Write-Host "          sha256: $hash"
