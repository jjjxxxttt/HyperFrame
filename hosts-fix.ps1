$ErrorActionPreference = 'Stop'
$hostsPath = "$env:WINDIR\System32\drivers\etc\hosts"
$backupPath = "D:\dev\hosts.bak.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$line = "128.242.245.157 toapis.com"

Copy-Item -Path $hostsPath -Destination $backupPath -Force
$content = Get-Content $hostsPath -Raw
if ($content -notmatch [regex]::Escape($line)) {
  Add-Content -Path $hostsPath -Value "`r`n$line  # sclaw-fix: bypass Facebook AAAA, force IPv4`r`n"
  Write-Host "ADDED: $line"
  Write-Host "BACKUP: $backupPath"
} else {
  Write-Host "ALREADY PRESENT: $line"
  Write-Host "BACKUP: $backupPath"
}

ipconfig /flushdns | Out-Null
Write-Host "DNS cache flushed."

Write-Host "---"
Get-Content $hostsPath | Select-String -Pattern "toapis" -SimpleMatch
