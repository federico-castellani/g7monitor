#!/bin/sh
# Installa G7 Monitor sull'orologio Wear OS (Galaxy Watch 4 o successivo), da Mac o Linux.
# Serve solo la prima volta: poi l'app dell'orologio si aggiorna da sola.
#   sh installa-orologio.sh               (scarica l'ultima versione)
#   sh installa-orologio.sh file.apk      (usa un APK gia' scaricato)
set -eu
work="${TMPDIR:-/tmp}/g7monitor-orologio"
mkdir -p "$work"
apk="${1:-}"

echo "== Strumenti Android (adb)"
if command -v adb >/dev/null 2>&1; then
  adb=adb
elif [ -x "$work/platform-tools/adb" ]; then
  adb="$work/platform-tools/adb"
else
  case "$(uname -s)" in Darwin) os=darwin ;; *) os=linux ;; esac
  echo "adb non trovato: scarico gli Android platform-tools ufficiali di Google..."
  curl -fsSL "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" -o "$work/pt.zip"
  (cd "$work" && unzip -oq pt.zip)
  adb="$work/platform-tools/adb"
fi

if [ -z "$apk" ]; then
  echo "== Ultima versione dell'app per l'orologio"
  url=$(curl -fsSL https://api.github.com/repos/federico-castellani/g7monitor/releases/latest \
    | grep -o '"browser_download_url": *"[^"]*G7Monitor-Wear-[0-9.]*\.apk"' | head -n 1 | sed 's/.*"\(https[^"]*\)"/\1/')
  [ -n "$url" ] || { echo "Nessun APK per l'orologio nell'ultima release"; exit 1; }
  apk="$work/$(basename "$url")"
  curl -fsSL "$url" -o "$apk"
fi

echo "== Collegamento all'orologio"
echo "Sull'orologio: Impostazioni > Opzioni sviluppatore > Debug wireless > Abbina nuovo dispositivo."
printf "Indirizzo IP e porta mostrati sotto il codice (es. 192.168.1.50:37123): "; read -r pair
printf "Codice di abbinamento a 6 cifre: "; read -r code
"$adb" pair "$pair" "$code"
echo "Ora torna a 'Debug wireless': in alto c'e' l'indirizzo con una porta DIVERSA da quella dell'abbinamento."
printf "Indirizzo IP e porta di Debug wireless (es. 192.168.1.50:41235): "; read -r conn
"$adb" connect "$conn"

echo "== Installazione"
"$adb" -s "$conn" install -r "$apk"
"$adb" disconnect "$conn" >/dev/null 2>&1 || true
echo "Fatto! Apri G7 Monitor sull'orologio e consenti le notifiche: da ora si aggiorna da solo."
