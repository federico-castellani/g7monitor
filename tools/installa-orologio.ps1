# Installa G7 Monitor sull'orologio Wear OS (Galaxy Watch 4 o successivo).
# Serve solo la prima volta: poi l'app dell'orologio si aggiorna da sola.
#
# Uso (PowerShell, dalla cartella dove l'hai scaricato):
#   powershell -ExecutionPolicy Bypass -File .\installa-orologio.ps1
# Con un APK gia' scaricato:
#   powershell -ExecutionPolicy Bypass -File .\installa-orologio.ps1 -Apk .\G7Monitor-Wear-0.3.0.apk

param([string]$Apk = '')

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # downloads are much faster without the progress bar
$work = Join-Path $env:TEMP 'g7monitor-orologio'
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

# ---------------------------------------------------------------- adb
Step 'Strumenti Android (adb)'
$adb = $null
$cmd = Get-Command adb -ErrorAction SilentlyContinue
if ($cmd) { $adb = $cmd.Source }
elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") { $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
elseif (Test-Path "$work\platform-tools\adb.exe") { $adb = "$work\platform-tools\adb.exe" }
else {
  Write-Host 'adb non trovato: scarico gli Android platform-tools ufficiali di Google...'
  $zip = Join-Path $work 'platform-tools.zip'
  Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
  Expand-Archive $zip -DestinationPath $work -Force
  $adb = "$work\platform-tools\adb.exe"
}
Write-Host "adb: $adb"

# ---------------------------------------------------------------- APK
if (-not $Apk) {
  Step "Ultima versione dell'app per l'orologio"
  $rel = Invoke-RestMethod 'https://api.github.com/repos/federico-castellani/g7monitor/releases/latest' -Headers @{ 'User-Agent' = 'G7Monitor-installer' }
  $asset = $rel.assets | Where-Object { $_.name -match '^G7Monitor-Wear-\d+\.\d+\.\d+\.apk$' } | Select-Object -First 1
  if (-not $asset) { throw "Nessun APK per l'orologio nella release $($rel.tag_name)" }
  $Apk = Join-Path $work $asset.name
  Write-Host "Scarico $($asset.name)..."
  Invoke-WebRequest $asset.browser_download_url -OutFile $Apk
}
if (-not (Test-Path $Apk)) { throw "File non trovato: $Apk" }

# ---------------------------------------------------------------- pairing and connection
Step "Collegamento all'orologio"
Write-Host "Sull'orologio: Impostazioni > Opzioni sviluppatore > Debug wireless > Abbina nuovo dispositivo."
Write-Host "(Orologio e computer devono essere sulla stessa rete Wi-Fi.)"
$pair = Read-Host "Indirizzo IP e porta mostrati sotto il codice (es. 192.168.1.50:37123)"
$code = Read-Host 'Codice di abbinamento a 6 cifre'
& $adb pair $pair $code
if ($LASTEXITCODE -ne 0) { throw 'Abbinamento non riuscito: controlla indirizzo e codice (il codice cambia ogni volta).' }

Write-Host ''
Write-Host "Ora torna alla schermata 'Debug wireless': in alto c'e' l'indirizzo IP e una porta DIVERSA da quella dell'abbinamento."
$conn = Read-Host 'Indirizzo IP e porta di Debug wireless (es. 192.168.1.50:41235)'
& $adb connect $conn
if ($LASTEXITCODE -ne 0) { throw "Collegamento non riuscito a $conn" }

# ---------------------------------------------------------------- install
Step 'Installazione'
& $adb -s $conn install -r $Apk
if ($LASTEXITCODE -ne 0) {
  Write-Host "Se dice INSTALL_FAILED_UPDATE_INCOMPATIBLE, sull'orologio c'e' una versione con un'altra firma:" -ForegroundColor Yellow
  Write-Host "disinstallala (adb -s $conn uninstall it.federico.g7monitor) e rilancia lo script." -ForegroundColor Yellow
  throw 'Installazione non riuscita'
}
& $adb disconnect $conn | Out-Null

Write-Host ''
Write-Host 'Fatto!' -ForegroundColor Green
Write-Host "Apri G7 Monitor sull'orologio e consenti le notifiche: da ora si aggiorna da solo."
Write-Host "Puoi spegnere il Debug wireless. Aggiungi le complicazioni dal quadrante: tieni premuto > Personalizza."
