# G7 Monitor

App Android moderna per leggere la glicemia direttamente da un **Dexcom G7** via Bluetooth, ispirata a [xDrip+](https://github.com/NightscoutFoundation/xDrip) ma con un'interfaccia nuova in Jetpack Compose / Material 3.

> ⚠️ **Non è un dispositivo medico.** Progetto personale/sperimentale: non usarlo per decisioni terapeutiche senza verificare con un dispositivo approvato.

## Cosa fa (versione 0.1)

- Trova il sensore G7 (nome `DXCM…`), si collega e si autentica con il **codice di abbinamento a 4 cifre**
- Riceve la glicemia ogni 5 minuti e recupera automaticamente le letture perse (backfill, fino a 24 h)
- Mostra valore attuale, freccia di tendenza, variazione, minuti trascorsi
- Grafico 3 / 6 / 12 / 24 h con fascia target, tocca/trascina per vedere il singolo valore
- Statistiche: tempo in target, media, numero letture
- Stato sensore: riscaldamento (30 min), giorno di utilizzo, scadenza (10 giorni + 12 h)
- Notifica persistente con l'ultima glicemia, riavvio automatico dopo il reboot
- **Allarmi**: molto bassa, bassa, alta, molto alta, discesa rapida, salita rapida, mancanza di segnale.
  - Ognuno ha soglia, durata del posticipo, suono, volume e modalità (suono e vibrazione, solo vibrazione, solo suono) configurabili.
  - Suonano sul canale "sveglia", anche in silenzioso, con una pagina a tutto schermo sopra la schermata di blocco. Se nessuno reagisce risuonano a ogni nuova lettura.
  - Per **disattivare** un allarme serve lo sblocco del telefono (PIN, sequenza, password o biometria).
  - I suoni sono generati da `tools/gen_alarm_sounds.py`.
- mg/dL o mmol/L, intervallo target configurabile, tema chiaro/scuro con colori dinamici (Material You)
- Import del certificato G7 ("G Key" di xDrip) scansionando il QR code
- Schermata **Diagnostica**: stato del collegamento, dispositivi Bluetooth vicini in tempo reale, registro eventi copiabile
- **Due modalità**, scelte al primo avvio e modificabili da *Impostazioni → Origine dei dati*. Ognuna mostra solo le proprie impostazioni:
  - **Sensore G7**: il telefono si collega al sensore. Il server è facoltativo: da *Impostazioni → Server (caricamento)* si inserisce l'indirizzo e, se il server risponde, le credenziali; da quel momento le glicemie vengono caricate. Il caricamento parte subito dopo ogni lettura, senza risvegli extra; a scelta ogni 15/30/60 minuti o solo con Wi-Fi. Quando colleghi o ricolleghi il server, ad esempio dopo un reset, il telefono:
    - scarica dal server le ultime 24 ore, così il grafico è subito completo;
    - invia tutte le letture che ha;
    - lascia che il server scarti i doppioni.
  - **Server**: il telefono riceve le glicemie da un **G7 Monitor Server** (progetto separato), senza sensore. Basta un account in sola lettura. Il telefono chiede i dati solo quando è attesa una nuova lettura, circa ogni 5 minuti, senza tenere una connessione sempre aperta.
- Verso il server solo HTTPS, con un codice di accesso revocabile per ogni telefono. La password non resta sul telefono.

## Tecnologie

- **Kotlin** + **Coroutines/Flow** (efficiente, nessuna libreria pesante)
- **Jetpack Compose + Material 3** per la UI, grafico disegnato a mano su `Canvas`
- Crittografia J-PAKE su curva P-256 **implementata in Kotlin puro** (xDrip usa BouncyCastle: qui l'APK resta piccolo)
- SQLite diretto per le letture, DataStore per le impostazioni
- Foreground service di tipo `connectedDevice`; minSdk 31 (Android 12+), quindi niente permesso di posizione

## Come aprirlo e installarlo

Requisiti: Android Studio recente, **JDK 21** come Gradle JDK (*Settings → Build Tools → Gradle → Gradle JDK*), telefono con Android 12 o successivo.

1. Apri **Android Studio** → *Open* → seleziona la cartella del progetto (oppure *File → New → Project from Version Control* con l'URL del repository).
2. Attendi la sincronizzazione Gradle (scarica le dipendenze la prima volta).
3. Collega il telefono con il *debug USB* attivo e premi **Run ▶**.
   In alternativa: `gradlew assembleDebug` e installa `app/build/outputs/apk/debug/app-debug.apk`.
4. Al primo avvio scegli **Dal sensore G7** (oppure **Dal server** per ricevere le glicemie di un'altra persona, e segui le schermate indirizzo → credenziali).
   Poi ⚙️ **Impostazioni** → inserisci il **codice di abbinamento** (sull'applicatore del G7) → *Salva codice* → attiva **Raccolta dati** e concedi i permessi Bluetooth e notifiche.
5. Consigliato: in *Affidabilità* premi **Consenti** per disattivare l'ottimizzazione batteria.
6. Al primo collegamento Android può chiedere di **abbinare** il dispositivo `DXCM…`: accetta.

### Note importanti

- **Usa un solo collettore sul telefono.** Se xDrip è installato con la raccolta G7 attiva, disattivala, altrimenti le due app si contendono il sensore.
- **Certificato (keks / "G Key").** Il G7, al primo abbinamento con un telefono nuovo, chiede un certificato applicativo. Se manca, l'autenticazione riesce ma il sensore risponde "non abbinato" e l'app mostra *"Il sensore richiede i certificati"*. **Questa app non lo include**: è lo stesso che xDrip carica dal QR code "G Key" (preferenze `keks_p1`, `keks_p2`, `keks_p3`). Importalo da *Impostazioni → Certificato G7*: puoi **scansionare il QR** (`xdp2:…`), incollarne il testo, oppure inserire i tre valori esadecimali. Dopo il primo abbinamento non serve più per quel sensore.
- **Altri dispositivi collegati al sensore** (microinfusore, ricevitore) continuano a funzionare: l'app usa lo stesso canale di autenticazione di xDrip (slot 2).
- **Problemi?** Apri *Diagnostica* (icona in alto nella Home): mostra se il G7 è visibile, lo stato del collegamento e, con *Mostra registro eventi*, ogni passo dell'autenticazione. Il pulsante *Copia* permette di condividere il registro. Lo stesso registro è in Logcat con il filtro `G7Collector`.

## Smartwatch Wear OS (complicazioni)

Il modulo `wear` è un'app per l'orologio, come GlucoDataHandler: riceve la glicemia dal telefono e la espone come **complicazioni** da aggiungere ai quadranti, compresi quelli Samsung. Funziona in entrambe le modalità del telefono, sensore e server.

| Complicazione | Tipi | Mostra |
|---|---|---|
| **Glicemia** | testo breve, testo lungo, indicatore | `123↗` con i minuti dall'ultima lettura; `123 mg/dL ↗ +4`; indicatore da 40 a 300 mg/dL |
| **Glicemia: variazione** | testo breve | `+4` rispetto alla lettura precedente |
| **Glicemia: ultima lettura** | testo breve | minuti dall'ultima lettura |

Batteria:
- l'orologio non chiede mai dati: è il telefono a inviarli, uno per lettura;
- i minuti trascorsi li conta il quadrante da solo;
- dopo 15 minuti senza letture il valore diventa `---` senza bisogno di svegliare l'orologio.

Toccando una complicazione si apre la schermata della glicemia sull'orologio.

### Installazione sull'orologio (Galaxy Watch 4 o successivo, Wear OS 3+)
Telefono e orologio devono avere app con lo stesso nome di pacchetto e **la stessa firma**: installa le due app compilate dallo stesso PC, oppure scaricate entrambe dalla stessa esecuzione della CI.

1. Sull'orologio: *Impostazioni → Info sull'orologio → Informazioni sul software*, tocca 5 volte *Versione software*. Poi *Opzioni sviluppatore → Debug ADB* e *Debug wireless*.
2. Dal PC, sulla stessa rete Wi-Fi, usa i dati mostrati dall'orologio in *Debug wireless*:
   ```
   adb pair IP:PORTA_ABBINAMENTO      (codice mostrato sull'orologio)
   adb connect IP:PORTA
   adb -s IP:PORTA install wear/build/outputs/apk/release/wear-release.apk
   ```
3. Sul quadrante: tieni premuto → *Personalizza* → tocca una complicazione → *G7 Monitor* → scegli *Glicemia*, *Glicemia: variazione* o *Glicemia: ultima lettura*.

I dati arrivano alla prossima lettura, oppure subito se apri G7 Monitor sull'orologio.

## Struttura

```
app/src/main/java/it/federico/g7monitor/
├── protocol/            # Kotlin puro, nessuna dipendenza Android (testabile su JVM)
│   ├── P256.kt          # aritmetica curva ellittica secp256r1
│   ├── JPake.kt         # EC-J-PAKE + prove zero-knowledge + hash della challenge
│   ├── G7Auth.kt        # macchina a stati di autenticazione (porting del plugin "keks" di xDrip)
│   ├── G7Messages.kt    # UUID GATT, comandi, parsing glicemia 0x4E e backfill
│   └── Bytes.kt
├── ble/
│   ├── G7Collector.kt   # ciclo: scansione → connessione → auth → dati → backfill → disconnessione
│   ├── GattClient.kt    # wrapper GATT a coroutine (una operazione per volta)
│   ├── BondHelper.kt    # abbinamento Bluetooth
│   └── G7Service.kt     # foreground service, notifica, watchdog, avvio al boot
├── data/                # SQLite letture, impostazioni, stato sensore, registro eventi
├── net/                 # server G7 Monitor: caricamento (Uploader) e ricezione (Follower)
├── alarm/               # allarmi: regole (Kotlin puro), motore, suono e vibrazione
├── watch/               # invio della glicemia allo smartwatch (Wear OS Data Layer)
└── ui/                  # Compose: Home, grafico, Impostazioni, server, allarmi, Diagnostica

wear/src/main/java/it/federico/g7monitor/wear/
├── DataLayerListenerService.kt   # riceve le letture dal telefono
├── WatchGlucose.kt               # ultima lettura e formattazione
├── complication/                 # le tre complicazioni
└── MainActivity.kt               # schermata della glicemia sull'orologio
```

### Corrispondenza con il codice di xDrip

| Questa app | xDrip |
|---|---|
| `protocol/G7Auth.kt` | `libkeks/.../jamorham/keks/Plugin.java` |
| `protocol/JPake.kt` | `libkeks/.../keks/Calc.java`, `Packet.java`, `Context.java` |
| `protocol/G7Messages.kt` | `g5model/EGlucoseRxMessage2.java`, `BackFillStream.java`, `BackFillTxMessage2.java`, `BluetoothServices.java` |
| `ble/G7Collector.kt` | `g5model/Ob1G5StateMachine.java` (`doCheckAuth2`, `doNext`, `doGetData`, `backFillIfNeeded`), `services/Ob1G5CollectionService.java` |

## Test

`app/src/test/.../G7ProtocolTest.kt` verifica: aritmetica P-256 contro la crypto di Java, prove ZKP, handshake completo contro un **sensore simulato** (anche con scambio dei certificati), riconnessione con chiave salvata, codice errato, slot di autenticazione, parsing dei pacchetti, import del QR "G Key", firma della challenge.
Durante lo sviluppo il simulatore è stato verificato facendolo dialogare con il **codice originale di xDrip** (plugin keks + BouncyCastle): stesso esito, stessa chiave di sessione e stessa sequenza di abbinamento. Il protocollo è stato poi provato con un G7 reale.

Da Android Studio: tasto destro su `G7ProtocolTest` → *Run*, oppure `gradlew testDebugUnitTest`.
Su GitHub, la action *Android CI* esegue i test e produce l'APK di debug a ogni push (scaricabile dagli *Artifacts* della build).

## Prossimi passi possibili

Allarmi ipo/iper, widget, upload su Nightscout, broadcast verso AndroidAPS, calibrazione, smartwatch.

## Licenza

GPL-3.0 (vedi `LICENSE`). Il protocollo è un porting del codice di xDrip+ (© JamOrHam e contributori, GPLv3), quindi anche questo progetto è GPLv3.
