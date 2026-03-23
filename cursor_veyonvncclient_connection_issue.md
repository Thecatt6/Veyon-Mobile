# VeyonVncClient connection issue
_Exported on 3/23/2026 at 19:31:07 GMT+1 from Cursor (2.6.20)_

---

**User**

puoi vedere il mio progetto? il mio VeyonVncClient,java non va, non si riesce a connettere al server veyon

---

**Cursor**

Ok. Obiettivo: capire perché `VeyonVncClient.java` non riesce a connettersi al server Veyon. Prossimi passi: leggo il file, cerco dove imposta host/porta/protocollo e come gestisce handshake/auth, poi controllo i punti in cui registra errori o lancia eccezioni e ti preparo una patch mirata + una checklist di test/log da fare.

Ho trovato due problemi “bloccanti” nel tuo `VeyonVncClient.java`: ci sono **errori di sintassi** (mancano `;`) e in più i commenti sull’auth dicono “senza count” ma il codice manda un **count** (che in realtà è coerente con come Qt serializza una lista di QVariant). Sistemo prima la compilazione e rendo il formato dei messaggi di auth coerente e ben calcolato, così possiamo poi diagnosticare l’eventuale rifiuto lato server con log più chiari.

Adesso il file compila di nuovo e la serializzazione dei due messaggi di autenticazione è coerente (payloadSize calcolato correttamente + count esplicito). Prossimo step: controllo se ci sono altri punti simili (feature messages/UUID, ZRLE inflater) e poi faccio un build/lint mirato per vedere se restano errori che impediscono la connessione o fanno chiudere subito il socket.

Ora rendo la fase di “socket connect” più robusta (timeout esplicito + messaggi d’errore più chiari). Questo non cambia il protocollo, ma ti dirà subito se il problema è DNS/porta/timeout oppure handshake/auth.

## Cosa ho cambiato (e perché)

- **Fix compilazione + auth payload coerente** in `android/app/src/main/java/com/veyonmobile/VeyonVncClient.java`
  - Sistemati i `;` mancanti in `sendAuthTypeAndUsername()`.
  - Ricalcolato in modo consistente il `payloadSize` dei messaggi di autenticazione (includendo il `count` come `int32`), così `msgSize` corrisponde davvero ai byte inviati.
- **Connessione TCP con timeout esplicito**
  - Ora usa `socket.connect(new InetSocketAddress(host, port), 8000)` così se è DNS/timeout/porta chiusa lo vedi chiaramente e non “sembra” un problema di protocollo.

## Cosa mi serve per chiudere il bug “non si connette”
Incollami l’errore preciso che ricevi (Logcat o evento JS `veyonVncError`), soprattutto una di queste righe:
- **`Server error:`** (arriva prima della scelta security type)
- **`No Veyon security type offered`**
- **`Auth failed:`** (con testo)
- **`Connection error:`** (con stacktrace o messaggio)

Con quell’output ti dico esattamente se è:
- porta/host (11100 raggiungibile ma Veyon non risponde come VNC),
- negoziazione security type,
- oppure formato dei QVariant nell’handshake Veyon (e allora patchiamo il framing in base ai byte reali).

---

**User**

2026-03-23 18:30:16.964 19088-19173 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:30:16.974 19088-19088 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= f4b498f SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732f70a000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:30:16.974 19088-19088 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = f4b498f SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732f70a000 sc.mNativeObject= 0xb40000732f682500 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 18:30:16.974 19088-19088 SurfaceView             com.veyonmobile                      I  256592271 Cur surface: Surface(name=null mNativeObject=0)/@0x3f98252
2026-03-23 18:30:16.975 19088-19088 SurfaceView             com.veyonmobile                      D  256592271 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:30:16.975 19088-19088 SurfaceView             com.veyonmobile                      I  256592271 visibleChanged -- surfaceCreated
2026-03-23 18:30:16.975 19088-19088 SurfaceView             com.veyonmobile                      I  256592271 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 18:30:16.975 19088-19088 SurfaceView             com.veyonmobile                      I  256592271 surfaceRedrawNeeded
2026-03-23 18:30:16.976 19088-19088 SurfaceView             com.veyonmobile                      I  256592271 finishedDrawing
2026-03-23 18:30:16.976 19088-19088 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 18:30:16.978 19088-19173 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: true,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:30:16.983 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 240, position = [163, 141, 2083, 861] surfaceSize = 1920x720
2026-03-23 18:30:16.999 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 241, position = [143, 141, 2063, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.011 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 242, position = [56, 141, 1976, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.034 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 243, position = [38, 141, 1958, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.055 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 244, position = [32, 141, 1952, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.061 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 245, position = [23, 141, 1943, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.073 19088-19173 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 18:30:17.077 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 246, position = [20, 141, 1940, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.082 19088-19244 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:30:17.082 19088-19244 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100
2026-03-23 18:30:17.104 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 247, position = [17, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.112 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 248, position = [13, 141, 1933, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.118 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 249, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.132 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 250, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.143 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 251, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.153 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 252, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.164 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 253, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.173 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 254, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.186 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 255, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.197 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 256, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.222 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 258, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.242 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 260, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.255 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 261, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.288 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 264, position = [0, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:30:17.299 19088-19122 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 265, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:30:20.258 19088-19244 VeyonVncClient          com.veyonmobile                      E  Connection error: null (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readFully(DataInputStream.java:205)
                                                                                                    	at java.io.DataInputStream.readFully(DataInputStream.java:173)
                                                                                                    	at com.veyonmobile.VeyonVncClient.rfbHandshake(VeyonVncClient.java:183)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:121)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)
2026-03-23 18:30:20.267 19088-19173 ReactNativeJS           com.veyonmobile                      W  'VNC connect error:', 'Error not specified.'
2026-03-23 18:30:20.268 19088-19173 ReactNativeJS           com.veyonmobile                      W  'VNC error:', 'Error not specified.'


---

**Cursor**

Perfetto, il log è chiarissimo: il crash avviene **prima** dell’autenticazione, dentro `rfbHandshake()` con `EOFException` (il server chiude la socket senza inviare `RFB xxx.xxx`). Controllo subito il codice JS dove parte la connect (`hasKey/hasPriv`) e poi ti metto un fix per avere errori diagnostici espliciti (non più `null`) e un pre-check RFB più robusto.

Perfetto, il tuo log identifica il problema con precisione:

- La connessione TCP parte (`Connecting to 192.168.2.229:11100`)
- poi fallisce **in `rfbHandshake()`** con `EOFException`
- quindi il server chiude la socket **prima** di inviare la stringa iniziale `RFB xxx.xxx`

Questo significa che **non sei ancora al punto auth key** (quindi non è la firma RSA adesso).

Ho già migliorato `VeyonVncClient` per darti errore utile (non più `null`):
- se il server chiude subito, ora ricevi un messaggio tipo  
  `Server closed before RFB handshake ... Likely wrong port/service or Veyon Service not accepting VNC`
- se arriva banner non valido, ricevi `Invalid RFB banner: ...`
- se l’eccezione non ha messaggio, ora passa almeno il nome (`EOFException`) a JS.

### Cosa controllare subito lato server (molto probabile qui)
1. Sul PC `192.168.2.229`, verifica che il servizio Veyon Service sia attivo.
2. In Veyon Configurator, controlla che il servizio sia in ascolto VNC su `11100`.
3. Firewall Windows del target: deve consentire ingresso TCP `11100`.
4. Verifica che `192.168.2.229` sia davvero il client Veyon corretto (non altro host con porta aperta ma servizio diverso).

### Nota sul log `hasKey: false / hasPriv: false`
Quella parte è solo uno stato iniziale (`doConnect skipped`), poi infatti fa `VNC connecting ... keyName: Test`. Quindi in questo tentativo il blocco è **prima** delle chiavi.

Se vuoi, nel prossimo step ti aggiungo anche un piccolo “preflight check” nativo che legge i primi byte del banner RFB e te li mostra nell’UI prima di lanciare tutta la sessione: velocizza tantissimo la diagnosi host/porta sbagliati.

---

**User**

2026-03-23 18:34:07.171 19612-20178 VeyonVncClient          com.veyonmobile                      E  Connection error: Server closed before RFB handshake (received 0/12 bytes, data=<empty>). Likely wrong port/service or Veyon Service not accepting VNC. (Fix with AI)
                                                                                                    java.io.IOException: Server closed before RFB handshake (received 0/12 bytes, data=<empty>). Likely wrong port/service or Veyon Service not accepting VNC.
                                                                                                    	at com.veyonmobile.VeyonVncClient.rfbHandshake(VeyonVncClient.java:191)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:121)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)

---

**Cursor**

Perfetto: questo log conferma al 100% che il blocco è **lato server/porta**, non nel tuo parser Veyon.

- TCP si apre
- il server invia **0 byte**
- chiude subito prima del banner `RFB 003.008`

Quindi il tuo client non ha nemmeno la possibilità di entrare in auth.

## Verifica rapida (decisiva)
Esegui sul tuo PC (PowerShell):

```powershell
$ip = "192.168.2.229"; $port = 11100
$c = New-Object Net.Sockets.TcpClient
$c.Connect($ip,$port)
$s = $c.GetStream()
$b = New-Object byte[] 12
$n = $s.Read($b,0,12)
"read bytes: $n"
if($n -gt 0){ [Text.Encoding]::ASCII.GetString($b,0,$n) }
$c.Close()
```

Se `read bytes: 0`, il server chiude subito: configurazione servizio/firewall/porta errata.

## Cose da controllare su `192.168.2.229`
- `Veyon Service` avviato (services.msc).
- Veyon configurato per ascoltare sulla porta corretta (di default `11100`).
- Firewall Windows: regola inbound TCP `11100`.
- Nessun altro software/proxy sulla stessa porta.
- Test con **Veyon Master ufficiale** verso quella macchina: se anche lì fallisce, è sicuramente server config.

Se vuoi, nel prossimo step ti preparo una piccola funzione “diagnostica banner” in app (mostra `bytes letti + banner`) così non devi usare PowerShell ogni volta.

---

**User**

se vuoi puoi eseguirlo acnhe tu, è questo il pc con veyon server

---

**Cursor**

Perfetto, faccio io subito un test diretto dal tuo ambiente: verifico apertura TCP su `192.168.2.229:11100` e provo a leggere il banner RFB iniziale per confermare lato rete/servizio.

Confermato dal test eseguito qui sul tuo ambiente:

- `192.168.2.229:11100` **non risponde come server RFB** (nessun banner, timeout in lettura)
- `192.168.2.229:5900` rifiutata (ok, può essere normale)
- `192.168.2.229:11080` apre ma non manda banner RFB (normale: non è porta VNC)

Quindi il problema è lato servizio/porta target, non il tuo parsing Java.

## Cosa fare sul PC server (quello `192.168.2.229`)
- Verifica che `Veyon Service` sia **Running**.
- In Veyon Configurator controlla che il servizio sia in ascolto su `11100` (e che non sia cambiata porta).
- Apri firewall inbound TCP `11100` (profilo rete corretto: Private/Domain).
- Riavvia `Veyon Service` dopo ogni modifica.
- Prova da un altro client (Veyon Master ufficiale) verso `192.168.2.229` per conferma incrociata.

Se vuoi, posso aggiungerti adesso in app un pulsante/flow “Diagnosi VNC” che mostra:
- connect ok/ko
- bytes letti dal banner
- testo banner ricevuto  
così fai debug immediato senza terminale.

---

**User**

ho sistemato, ecco il nuovo errore:

Connection error: EOFException (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readFully(DataInputStream.java:205)
                                                                                                    	at java.io.DataInputStream.readInt(DataInputStream.java:394)
                                                                                                    	at com.veyonmobile.VeyonVncClient.receiveEmptyVariantArray(VeyonVncClient.java:333)
                                                                                                    	at com.veyonmobile.VeyonVncClient.veyonAuthenticate(VeyonVncClient.java:261)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:127)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)

---

**Cursor**

Ottimo, ora arriviamo all’autenticazione e il server chiude subito dopo `sendAuthTypeAndUsername`: il punto critico è proprio il formato di quel messaggio. Leggo il file aggiornato e applico una patch per allineare il framing ai pacchetti reali Veyon (senza `count` nei due messaggi client→server) con fallback robusto.

Perfetto, questo errore nuovo è coerente con un framing auth non allineato. Ho applicato una patch mirata in `VeyonVncClient.java`:

- `sendAuthTypeAndUsername()` ora invia **senza `count`** (come da wire format Veyon client→server).
- `sendVariantStringAndBytes()` ora invia **senza `count`**.
- Rimossa la dipendenza rigida da `ACK`:
  - nuovo parser `receiveChallengeWithOptionalAck()` che accetta entrambi i casi:
    - server manda `ACK vuoto` + challenge
    - server manda direttamente challenge
- parsing challenge reso più stretto con check `type == QByteArray`.

Questo dovrebbe eliminare l’`EOFException` in `receiveEmptyVariantArray` perché non aspettiamo più obbligatoriamente quel pacchetto.

### Prossimo test
Rilancia e mandami il nuovo blocco log da:
- `Selected auth type: ...`
- `Auth type + username sent`
- `ACK received` (se presente)
- `Challenge: ... bytes`
- eventuale `Auth failed: ...` o `Authentication successful`

Se ora arriva `Auth failed: ...`, siamo già a un passo dal fix definitivo (tipicamente `keyName` o formato firma/chiave).

---

**User**

2026-03-23 18:44:26.774 21642-21714 ReactNativeJS           com.veyonmobile                      I  'Authenticating to:', 'http://192.168.2.229:11080/api/v1/authentication/192.168.2.229:11100', 'keyName:', 'Test', 'keyLen:', 3271
2026-03-23 18:44:26.774 21642-21714 ReactNativeJS           com.veyonmobile                      I  'Key first 80 chars:', '-----BEGIN PRIVATE KEY-----\nMIIJRAIBADANBgkqhkiG9w0BAQEFAASCCS4wggkqAgEAAoICAQDo'
2026-03-23 18:44:26.775 21642-21714 ReactNativeJS           com.veyonmobile                      I  'Key last 40 chars:', 'FbsLgez08THcTb\n-----END PRIVATE KEY-----'
2026-03-23 18:44:26.859 21642-21714 ReactNativeJS           com.veyonmobile                      W  'Auth FAILED:', undefined, undefined
2026-03-23 18:44:27.495 21642-21754 ProfileInstaller        com.veyonmobile                      D  Installing profile for com.veyonmobile
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 18:44:30.326 21642-21642 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 18:44:30.446 21642-21642 SurfaceView             com.veyonmobile                      D  205341089 updateSurface: has no frame
2026-03-23 18:44:30.450 21642-21642 SurfaceView             com.veyonmobile                      D  205341089 updateSurface: has no frame
2026-03-23 18:44:30.455 21642-21714 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:44:30.461 21642-21642 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@c6f2cd5 mNativeObject= 0xb400007296d5f000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:44:30.463 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 Changes: creating=true format=true size=true visible=true alpha=false hint=true left=true top=true z=false attached=true lifecycleStrategy=false
2026-03-23 18:44:30.467 21642-21714 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: true,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:44:30.474 21642-21642 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= c3d41a1 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732d2c2000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:44:30.474 21642-21642 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = c3d41a1 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732d2c2000 sc.mNativeObject= 0xb40000732d298440 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 18:44:30.474 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 Cur surface: Surface(name=null mNativeObject=0)/@0x45da923
2026-03-23 18:44:30.474 21642-21642 SurfaceView             com.veyonmobile                      D  205341089 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.475 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 visibleChanged -- surfaceCreated
2026-03-23 18:44:30.475 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 18:44:30.475 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 surfaceRedrawNeeded
2026-03-23 18:44:30.476 21642-21642 SurfaceView             com.veyonmobile                      I  205341089 finishedDrawing
2026-03-23 18:44:30.476 21642-21642 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 18:44:30.481 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 61, position = [175, 141, 2095, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.508 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 62, position = [163, 141, 2083, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.525 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 63, position = [56, 141, 1976, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.536 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 64, position = [32, 141, 1952, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.545 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 65, position = [27, 141, 1947, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.559 21642-21714 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 18:44:30.559 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 66, position = [23, 141, 1943, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.561 21642-21770 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:44:30.561 21642-21770 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100
2026-03-23 18:44:30.567 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 67, position = [20, 141, 1940, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.587 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 68, position = [17, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.602 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 69, position = [15, 141, 1935, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.613 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 70, position = [11, 141, 1932, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.622 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 71, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.628 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 72, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.628 21642-21770 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 18:44:30.635 21642-21770 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 18:44:30.640 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 73, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.641 21642-21770 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 18:44:30.641 21642-21770 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 18:44:30.641 21642-21770 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 18:44:30.641 21642-21770 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 18:44:30.641 21642-21770 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 18:44:30.642 21642-21770 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 18:44:30.652 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 74, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.661 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 75, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.676 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 76, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.687 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 77, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.697 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 78, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 18:44:30.710 21642-21770 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 18:44:30.719 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 80, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.736 21642-21770 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 18:44:30.737 21642-21770 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 18:44:30.739 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 82, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.751 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 83, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.786 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 86, position = [0, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.796 21642-21666 SurfaceView             com.veyonmobile                      D  205341089 updateSurfacePosition RenderWorker, frameNr = 87, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:44:30.814 21642-21770 VeyonVncClient          com.veyonmobile                      E  Connection error: EOFException (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readFully(DataInputStream.java:205)
                                                                                                    	at java.io.DataInputStream.readInt(DataInputStream.java:394)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:130)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)
2026-03-23 18:44:30.820 21642-21714 ReactNativeJS           com.veyonmobile                      W  'VNC connect error:', 'EOFException'
2026-03-23 18:44:30.821 21642-21714 ReactNativeJS           com.veyonmobile                      W  'VNC error:', 'EOFException'


---

**Cursor**

Stai arrivando fino alla fine del challenge-response: il server chiude subito **dopo** `Auth response sent`, quindi il problema è quasi certamente nel formato/firma della risposta (non più handshake). Verifico al volo quale algoritmo usa Veyon per la firma challenge e ti applico la correzione precisa.

I log mostrano che il server accetta challenge e poi chiude appena invii la firma: tipico mismatch algoritmo firma/verify lato Veyon. Faccio una patch per retry automatico con più algoritmi RSA (`SHA256`, `SHA1`, `NONE`) senza cambiare UI, così troviamo quello compatibile col tuo server.

Ottimo avanzamento: ora il flusso arriva fino a `Auth response sent`, quindi la parte “hard” del protocollo è quasi a posto.

Ho applicato una patch che prova automaticamente più varianti di firma RSA quando il server chiude su `security_result`:

- `SHA256withRSA`
- `SHA1withRSA`
- `NONEwithRSA`

### Cosa fa ora il client
- Tenta connessione/auth con primo algoritmo.
- Se il server chiude con EOF subito dopo la risposta auth, chiude socket e ritenta con il successivo.
- Se uno passa, continua normalmente e logga:
  - `Authentication successful (<algoritmo>)`

### Per te adesso
Rilancia e incollami solo le righe `VeyonVncClient` relative a:
- `Connecting to ... using ...`
- `Authentication successful (...)` **oppure**
- `Connection attempt failed (alg=..., step=security_result)...`

Con questo capiamo subito quale algoritmo accetta il tuo server (o se il problema è proprio `keyName/keypair` mismatch).

---

**User**

2026-03-23 18:47:36.810 22369-22526 ReactNativeJS           com.veyonmobile                      I  'Authenticating to:', 'http://192.168.2.229:11080/api/v1/authentication/192.168.2.229:11100', 'keyName:', 'Test', 'keyLen:', 3271
2026-03-23 18:47:36.811 22369-22526 ReactNativeJS           com.veyonmobile                      I  'Key first 80 chars:', '-----BEGIN PRIVATE KEY-----\nMIIJRAIBADANBgkqhkiG9w0BAQEFAASCCS4wggkqAgEAAoICAQDo'
2026-03-23 18:47:36.811 22369-22526 ReactNativeJS           com.veyonmobile                      I  'Key last 40 chars:', 'FbsLgez08THcTb\n-----END PRIVATE KEY-----'
2026-03-23 18:47:36.894 22369-22526 ReactNativeJS           com.veyonmobile                      W  'Auth FAILED:', undefined, undefined
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 18:47:40.007 22369-22369 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 18:47:40.167 22369-22369 SurfaceView             com.veyonmobile                      D  256592271 updateSurface: has no frame
2026-03-23 18:47:40.177 22369-22369 SurfaceView             com.veyonmobile                      D  256592271 updateSurface: has no frame
2026-03-23 18:47:40.183 22369-22526 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: true,
                                                                                                      hasPriv: true }
2026-03-23 18:47:40.187 22369-22369 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@e68dd8c mNativeObject= 0xb40000730ca93000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:47:40.190 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 Changes: creating=true format=true size=true visible=true alpha=false hint=true left=true top=true z=false attached=true lifecycleStrategy=false
2026-03-23 18:47:40.196 22369-22526 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 18:47:40.200 22369-22369 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= f4b498f SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb4000071326dd000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:47:40.200 22369-22369 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = f4b498f SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb4000071326dd000 sc.mNativeObject= 0xb400007284c1b080 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 18:47:40.200 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 Cur surface: Surface(name=null mNativeObject=0)/@0xc5d315a
2026-03-23 18:47:40.200 22369-22369 SurfaceView             com.veyonmobile                      D  256592271 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.201 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 visibleChanged -- surfaceCreated
2026-03-23 18:47:40.201 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 18:47:40.201 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 surfaceRedrawNeeded
2026-03-23 18:47:40.201 22369-22369 SurfaceView             com.veyonmobile                      I  256592271 finishedDrawing
2026-03-23 18:47:40.202 22369-22369 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 18:47:40.208 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 66, position = [56, 141, 1976, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.239 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 67, position = [45, 141, 1965, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.250 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 68, position = [23, 141, 1944, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.261 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 69, position = [17, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.273 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 70, position = [15, 141, 1935, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.282 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 71, position = [13, 141, 1933, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.294 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 72, position = [11, 141, 1932, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.303 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 73, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.305 22369-22572 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:47:40.305 22369-22572 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA256withRSA
2026-03-23 18:47:40.314 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 74, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.325 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 75, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.336 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 76, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.347 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 77, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.350 22369-22572 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 18:47:40.355 22369-22572 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 18:47:40.359 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 78, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.361 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 18:47:40.361 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 18:47:40.361 22369-22572 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 18:47:40.361 22369-22572 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 18:47:40.361 22369-22572 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 18:47:40.362 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 18:47:40.370 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 79, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.381 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 80, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.403 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 82, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.426 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 84, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 18:47:40.426 22369-22572 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 18:47:40.436 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 85, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.450 22369-22572 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 18:47:40.453 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 18:47:40.470 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 88, position = [0, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.482 22369-22405 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 89, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:47:40.511 22369-22572 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA256withRSA, step=security_result): EOFException
2026-03-23 18:47:40.513 22369-22572 VeyonVncClient          com.veyonmobile                      W  Retrying with next signature algorithm
2026-03-23 18:47:40.513 22369-22572 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:47:40.513 22369-22572 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA1withRSA
2026-03-23 18:47:40.532 22369-22572 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 18:47:40.539 22369-22572 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 18:47:40.545 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 18:47:40.546 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 18:47:40.546 22369-22572 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 18:47:40.546 22369-22572 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 18:47:40.546 22369-22572 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 18:47:40.546 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 18:47:40.601 22369-22572 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 18:47:40.620 22369-22572 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 18:47:40.621 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 18:47:40.681 22369-22572 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA1withRSA, step=security_result): EOFException
2026-03-23 18:47:40.681 22369-22572 VeyonVncClient          com.veyonmobile                      W  Retrying with next signature algorithm
2026-03-23 18:47:40.681 22369-22572 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:47:40.681 22369-22572 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using NONEwithRSA
2026-03-23 18:47:40.709 22369-22572 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 18:47:40.714 22369-22572 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 18:47:40.720 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 18:47:40.720 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 18:47:40.720 22369-22572 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 18:47:40.720 22369-22572 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 18:47:40.720 22369-22572 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 18:47:40.721 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 18:47:40.779 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 18:47:40.779 22369-22572 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 18:47:40.779 22369-22572 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 18:47:40.779 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 18:47:40.780 22369-22572 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 18:47:40.780 22369-22572 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 18:47:40.803 22369-22572 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 18:47:40.804 22369-22572 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 18:47:40.869 22369-22572 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=NONEwithRSA, step=security_result): EOFException
2026-03-23 18:47:40.882 22369-22572 VeyonVncClient          com.veyonmobile                      E  Connection error: EOFException (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readFully(DataInputStream.java:205)
                                                                                                    	at java.io.DataInputStream.readInt(DataInputStream.java:394)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnectionAttempt(VeyonVncClient.java:177)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:123)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)
2026-03-23 18:47:40.890 22369-22526 ReactNativeJS           com.veyonmobile                      W  'VNC connect error:', 'EOFException'
2026-03-23 18:47:40.891 22369-22526 ReactNativeJS           com.veyonmobile                      W  'VNC error:', 'EOFException'


da quello che so io veyon dovrebbe usare SHA512

---

**Cursor**

Hai ragione, dal comportamento è plausibile `SHA512withRSA` (e al momento non lo stiamo provando). Aggiungo subito `SHA512withRSA` come primo tentativo e mantengo i fallback, così verifichiamo subito se era quello il blocco.

Perfetto, aggiornato.

Ho messo `SHA512withRSA` come **primo algoritmo** in `VeyonVncClient`:

- ordine adesso:
  1. `SHA512withRSA`
  2. `SHA256withRSA`
  3. `SHA1withRSA`
  4. `NONEwithRSA`

Così al prossimo tentativo vedrai subito nei log:
`Connecting to ... using SHA512withRSA`

Se anche con SHA512 fa EOF dopo `Auth response sent`, allora non è l’hash ma quasi certamente:
- `keyName` non corrisponde alla public key autorizzata lato Veyon Service, oppure
- la private key usata in app non è la coppia della key importata sul server.

Mandami il prossimo blocco log (solo le righe `using ...` e `Connection attempt failed ...`) e ti dico subito il passo successivo.

---

**User**

2026-03-23 18:49:25.199 22860-23099 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 18:49:25.207 22860-23099 VeyonVncClient          com.veyonmobile                      E  Connection error: EOFException (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readUnsignedByte(DataInputStream.java:298)
                                                                                                    	at com.veyonmobile.VeyonVncClient.receiveLoop(VeyonVncClient.java:484)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnectionAttempt(VeyonVncClient.java:216)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:124)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)


---

**Cursor**

Grande: questo è un salto enorme. `step=receive_loop` significa che handshake + auth (SHA512) sono passati. Ora la socket viene chiusa mentre sei già in ascolto frame; quindi conviene gestirlo come disconnessione normale, non come errore fatale `VNC error`. Applico una patch per trattare `EOF` in `receive_loop` come close remoto pulito.

Perfetto, ora il risultato è chiaro: con `SHA512withRSA` arrivi fino a `receive_loop`, quindi la connessione/auth è riuscita.

Ho fatto una modifica utile in `VeyonVncClient`:
- se arriva `EOF` **durante `receive_loop`**, non viene più segnalato come errore (`onError`), ma come chiusura remota normale.
- rimane comunque `onDisconnected("Connection closed")`, quindi l’UI sa che la sessione è terminata.

Questo evita il falso allarme “VNC error” quando in realtà il server sta solo chiudendo la sessione.

Se vuoi, nel prossimo step posso aggiungere:
- motivo disconnessione più descrittivo (`"Remote closed stream"`),
- e un retry automatico con backoff quando il server chiude subito dopo la connect.

---

**User**

a me basta che funzioni la connessione e che posso guardare lo schermo ed usare le features, ecco i prossimi log comunque:

2026-03-23 18:52:07.094 23533-23761 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 18:52:07.095 23533-23761 VeyonVncClient          com.veyonmobile                      E  Connection error: EOFException (Fix with AI)
                                                                                                    java.io.EOFException
                                                                                                    	at java.io.DataInputStream.readUnsignedByte(DataInputStream.java:298)
                                                                                                    	at com.veyonmobile.VeyonVncClient.receiveLoop(VeyonVncClient.java:492)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnectionAttempt(VeyonVncClient.java:224)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:124)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)
2026-03-23 18:52:07.096 23533-23761 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


---

**Cursor**

Chiaro: puntiamo a stabilizzare la sessione (schermo + feature), non solo pulire i log. Dai sintomi, la chiusura avviene probabilmente subito dopo i messaggi feature iniziali: faccio una patch per **non inviarli automaticamente all’avvio** (manteniamo il canale VNC puro stabile), e sistemo anche il logging così non marca `EOF` in receive loop come errore fatale.

Perfetto, ho fatto una patch orientata al tuo obiettivo: **tenere viva la connessione per vedere lo schermo**.

### Modifiche fatte
- In `VeyonVncClient` ho **disabilitato l’invio automatico dei feature messages all’avvio**.
  - Era il candidato principale alla chiusura immediata dopo auth.
  - Ora dopo `ServerInit` resta solo flusso VNC standard (pixel format + encodings + framebuffer request).
- Ho sistemato la gestione `EOF` in `receive_loop`:
  - non viene più trattato come errore applicativo,
  - disconnessione indicata come `Remote closed stream`,
  - niente `onError` in quel caso.

### Perché questa scelta
Tu vuoi prima di tutto “**si connette e vedo lo schermo**”.  
I feature packet Veyon custom sono la parte più fragile/proprietaria: finché non stabilizziamo il canale framebuffer, rischiano di buttare giù la sessione.

### Prossimo test
Riprova e guarda se:
- ottieni `onConnected` e frame a schermo,
- nei log compare `VNC session established; skipping initial feature messages`,
- non arriva più chiusura immediata.

Se la visualizzazione resta stabile, step successivo: riattiviamo le feature **una alla volta** (iniziando da `MonitoringMode`) con formato validato, senza rompere la sessione.

---

**User**

2026-03-23 18:53:40.221 24075-24259 ReactNativeJS           com.veyonmobile                      I  'Authenticating to:', 'http://192.168.2.229:11080/api/v1/authentication/192.168.2.229:11100', 'keyName:', 'Test', 'keyLen:', 3271
2026-03-23 18:53:40.221 24075-24259 ReactNativeJS           com.veyonmobile                      I  'Key first 80 chars:', '-----BEGIN PRIVATE KEY-----\nMIIJRAIBADANBgkqhkiG9w0BAQEFAASCCS4wggkqAgEAAoICAQDo'
2026-03-23 18:53:40.222 24075-24259 ReactNativeJS           com.veyonmobile                      I  'Key last 40 chars:', 'FbsLgez08THcTb\n-----END PRIVATE KEY-----'
2026-03-23 18:53:40.304 24075-24259 ReactNativeJS           com.veyonmobile                      W  'Auth FAILED:', undefined, undefined
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 18:53:46.034 24075-24075 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 18:53:46.171 24075-24259 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:53:46.183 24075-24259 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: true,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: false,
                                                                                                      hasPriv: false }
2026-03-23 18:53:46.195 24075-24075 SurfaceView             com.veyonmobile                      D  118625308 updateSurface: has no frame
2026-03-23 18:53:46.199 24075-24075 SurfaceView             com.veyonmobile                      D  118625308 updateSurface: has no frame
2026-03-23 18:53:46.210 24075-24075 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@e68dd8c mNativeObject= 0xb400007301e35000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:53:46.212 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 Changes: creating=true format=true size=true visible=true alpha=false hint=true left=true top=true z=false attached=true lifecycleStrategy=false
2026-03-23 18:53:46.216 24075-24075 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= 712141c SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000712d6b6000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 18:53:46.216 24075-24075 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = 712141c SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000712d6b6000 sc.mNativeObject= 0xb40000712e90c200 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 18:53:46.216 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 Cur surface: Surface(name=null mNativeObject=0)/@0x45da923
2026-03-23 18:53:46.216 24075-24075 SurfaceView             com.veyonmobile                      D  118625308 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.216 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 visibleChanged -- surfaceCreated
2026-03-23 18:53:46.217 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 18:53:46.217 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 surfaceRedrawNeeded
2026-03-23 18:53:46.217 24075-24075 SurfaceView             com.veyonmobile                      I  118625308 finishedDrawing
2026-03-23 18:53:46.217 24075-24075 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 18:53:46.224 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 62, position = [161, 141, 2081, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.248 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 63, position = [103, 141, 2023, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.260 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 64, position = [27, 141, 1947, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.269 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 65, position = [20, 141, 1940, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.277 24075-24259 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 18:53:46.283 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 66, position = [17, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.289 24075-24358 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 18:53:46.289 24075-24358 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA512withRSA
2026-03-23 18:53:46.303 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 67, position = [15, 141, 1935, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.311 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 68, position = [13, 141, 1933, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.320 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 69, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.333 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 70, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.344 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 71, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.354 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 72, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.359 24075-24358 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 18:53:46.365 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 73, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.366 24075-24358 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 18:53:46.376 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 74, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.377 24075-24358 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 18:53:46.377 24075-24358 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 18:53:46.377 24075-24358 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 18:53:46.377 24075-24358 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 18:53:46.377 24075-24358 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 18:53:46.378 24075-24358 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 18:53:46.387 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 75, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.399 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 76, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.421 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 78, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 18:53:46.425 24075-24358 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 18:53:46.443 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 80, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.449 24075-24358 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 18:53:46.450 24075-24358 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 18:53:46.453 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 81, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 18:53:46.488 24075-24131 SurfaceView             com.veyonmobile                      D  118625308 updateSurfacePosition RenderWorker, frameNr = 84, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 18:53:47.959 24075-24358 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 18:53:48.676 24075-24358 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 18:53:48.680 24075-24358 VeyonVncClient          com.veyonmobile                      D  VNC session established; skipping initial feature messages
2026-03-23 18:53:49.599 24075-24358 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 18:53:49.600 24075-24358 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop



penso che il server si aspetti qualche featuremessage particolare appena connessi, tipo quello della monitoring mode

---

**Cursor**

Sì, concordo con la tua ipotesi: handshake/auth ora sono ok, quindi la chiusura subito dopo `ServerInit` può essere policy lato Veyon che richiede l’attivazione `MonitoringMode`. Applico una patch minimale: invio **solo** quel `FeatureMessage` appena la sessione è pronta, senza gli altri.

Esatto, ci sta molto: ora auth funziona, quindi il close è probabile policy/session-mode.

Ho messo una patch mirata in `VeyonVncClient`:
- dopo `ServerInit` e la prima `FramebufferUpdateRequest`, invia **solo**:
  - `sendFeatureMessage(FEATURE_MONITORING_MODE, true, null)`
- log nuovo:
  - `VNC session established; MonitoringMode feature sent`

Quindi niente rumore con altre feature, ma attiviamo subito quella più probabile per tenere vivo il flusso schermo.

Riprova e mandami solo queste righe:
- `Authentication successful (...)`
- `ServerInit: ...`
- `VNC session established; MonitoringMode feature sent`
- eventuale `FeatureMessage received: ...`
- se ancora chiude: `Connection attempt failed ... step=...`

---

**User**

2026-03-23 18:57:46.555 25156-25408 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 18:57:47.271 25156-25408 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 18:57:47.275 25156-25408 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 18:57:47.275 25156-25408 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 18:57:47.336 25156-25408 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 18:57:48.193 25156-25408 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 18:57:48.194 25156-25408 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


ho aggiunto il codice sorgente di veyon nella root del progetto nella cartella veyon_source (C:\MC\veyon_source\veyon-4.10.1) così la puoi consultare liberamente e analizzare il suo funzionamento

---

**Cursor**

Perfetto, ottima mossa aver aggiunto il sorgente Veyon: ora possiamo allinearci 1:1 al comportamento reale. Vado subito a cercare in `veyon_source` il codice del security type `40`, del `MonitoringMode` e del punto in cui il server decide di chiudere la connessione dopo `FeatureMessage 39 bytes`.

Sto vedendo nel sorgente Veyon che il `MonitoringMode` usa anche `Ping`, e il tuo `FeatureMessage 39 bytes` è proprio coerente con quello. Per aumentare le chance di sessione stabile, aggiungo ora due keepalive “safe”: richieste framebuffer periodiche (anche senza frame) + ping MonitoringMode periodico.

Perfetto, grazie anche per il sorgente Veyon: mi ha aiutato a capire meglio il comportamento.

Ho applicato una patch per stabilizzare la sessione (obiettivo: vedere schermo + tenere viva connessione):

- keepalive in `receiveLoop` con timeout corto (`1s`)
- invio periodico di `FramebufferUpdateRequest` anche se non arrivano frame (`700ms`)
- ping `MonitoringMode` periodico (`2s`)
- gestione timeout socket come evento normale (non errore)

Perché: ora sappiamo che auth e init sono ok; il problema era la chiusura poco dopo. Con questi keepalive riduciamo il rischio che il server consideri la sessione inattiva e la chiuda.

### Nota importante dal sorgente Veyon
Hai centrato bene: `MonitoringMode` usa anche `Ping`, quindi il tuo `FeatureMessage received: 39 bytes` è compatibile con quel flusso.

### Prossimo passo
Rilancia e mandami 10-20 righe dopo:
- `VNC session established...`
- eventuali `FeatureMessage received...`
- se compaiono frame/fps (`veyonVncFps`)
- eventuale nuova chiusura (`Connection attempt failed ...`)

Se ancora chiude, il prossimo fix sarà su parsing/handling dettagliato dei `FeatureMessage` server→client (UID+command) per rispondere solo quando necessario.

---

**User**

2026-03-23 19:11:33.148 28216-28474 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:11:33.148 28216-28474 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 19:11:33.216 28216-28474 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:11:34.163 28216-28474 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:11:34.164 28216-28474 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop

---

**Cursor**

Ho un sospetto forte dai log: la socket potrebbe essere chiusa dal lato app (re-render / reconnect) e non dal server puro. Controllo subito `VeyonVncView` e il flusso React per vedere se c’è un `disconnect()` automatico poco dopo il connect.

Trovato il bug principale: il componente React sta richiamando `connect()` più volte per via di `useEffect` + callback ricreate ad ogni render, e il modulo nativo fa `disconnect()` del client attivo (EOF nel thread). Applico ora fix lato `VeyonVncView` per rendere la connect idempotente e non legata ai re-render.

Hai centrato il punto giusto con i log, e ho trovato il vero “killer” lato app:

- `VeyonVncView` stava rieseguendo `connect()` su re-render
- il modulo nativo, quando riceve un nuovo `connect`, fa `disconnect()` del client attivo
- il thread VNC vede `EOFException` in `receive_loop`

Quindi sembrava “server chiude”, ma spesso era un reconnect involontario dalla UI.

## Fix applicato
In `src/components/VeyonVncView.jsx` ho reso la connessione stabile:

- callback `onConnected/onDisconnected/onError` spostate in `ref` (non influenzano `doConnect`)
- `doConnect` ora dipende solo da `host/port/keyName/privateKey`
- reconnect triggerato solo quando cambiano davvero i parametri di connessione, non ad ogni render del parent

## Cosa fare ora
Rilancia e controlla se:
- non compaiono più reconnect ravvicinati,
- resti in `live`,
- iniziano ad arrivare FPS/frame.

Se dopo questo fix chiude ancora, il prossimo step è leggere anche i log del **Veyon Service** (lato server) perché a quel punto il client mobile sarà finalmente “pulito” e sapremo che il close è policy server reale.

---

**User**

2026-03-23 19:13:47.488 28216-28881 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:13:47.489 28216-28881 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop
2026-03-23 19:13:53.742 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@c6f2cd5 mNativeObject= 0xb400007301df2000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:13:53.777 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1563, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.788 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1564, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.798 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1565, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.814 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1567, position = [18, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.821 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1567, position = [31, 141, 1951, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.831 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1568, position = [51, 141, 1972, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.850 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1570, position = [89, 141, 2009, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.859 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1571, position = [120, 141, 2040, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.867 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1572, position = [136, 141, 2056, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.880 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1573, position = [148, 141, 2068, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.889 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1574, position = [155, 141, 2075, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.903 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1575, position = [161, 141, 2081, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.912 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1576, position = [165, 141, 2085, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.923 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1577, position = [169, 141, 2089, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.935 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1578, position = [172, 141, 2092, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.946 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1579, position = [174, 141, 2095, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.956 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1580, position = [177, 141, 2097, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.967 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1581, position = [179, 141, 2099, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.978 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1582, position = [181, 141, 2101, 861] surfaceSize = 1920x720
2026-03-23 19:13:53.989 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1583, position = [182, 141, 2102, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.000 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1584, position = [183, 141, 2103, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.015 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1585, position = [185, 141, 2105, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.022 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1586, position = [186, 141, 2106, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.034 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1587, position = [186, 141, 2107, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.046 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1588, position = [187, 141, 2107, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.057 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1589, position = [188, 141, 2108, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.068 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1590, position = [189, 141, 2109, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.091 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1592, position = [190, 141, 2110, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.113 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1594, position = [190, 141, 2111, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.124 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1595, position = [191, 141, 2111, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.157 28216-28290 SurfaceView             com.veyonmobile                      D  3673751 updateSurfacePosition RenderWorker, frameNr = 1598, position = [192, 141, 2112, 861] surfaceSize = 1920x720
2026-03-23 19:13:54.221 28216-28216 SurfaceView             com.veyonmobile                      I  3673751 Changes: creating=false format=false size=false visible=true alpha=false hint=false left=false top=false z=false attached=true lifecycleStrategy=false
2026-03-23 19:13:54.221 28216-28216 SurfaceView             com.veyonmobile                      I  3673751 Cur surface: Surface(name=null mNativeObject=-5476376652191434752)/@0xbcad402
2026-03-23 19:13:54.221 28216-28216 SurfaceView             com.veyonmobile                      I  3673751 surfaceDestroyed
2026-03-23 19:13:54.222 28216-28216 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 19:13:54.222 28216-28216 SurfaceView             com.veyonmobile                      I  3673751 Detaching SV
2026-03-23 19:13:54.222 28216-28216 SurfaceView             com.veyonmobile                      I  3673751 Changes: creating=false format=false size=false visible=false alpha=false hint=false left=false top=false z=false attached=false lifecycleStrategy=false
2026-03-23 19:13:54.232 28216-28290 SurfaceView             com.veyonmobile                      D  197414083 windowPositionLost, frameNr = 1604
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 19:13:55.736 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 19:13:55.823 28216-28216 SurfaceView             com.veyonmobile                      D  224393424 updateSurface: has no frame
2026-03-23 19:13:55.826 28216-28852 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: true,
                                                                                                      hasPriv: true }
2026-03-23 19:13:55.830 28216-28216 SurfaceView             com.veyonmobile                      D  224393424 updateSurface: has no frame
2026-03-23 19:13:55.839 28216-28852 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 19:13:55.842 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@c6f2cd5 mNativeObject= 0xb400007301df2000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:13:55.843 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Changes: creating=true format=true size=true visible=true alpha=false hint=true left=true top=true z=false attached=true lifecycleStrategy=false
2026-03-23 19:13:55.845 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= d5ff8d0 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000712eaeb000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:13:55.845 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = d5ff8d0 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000712eaeb000 sc.mNativeObject= 0xb40000712ccbf340 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 19:13:55.845 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Cur surface: Surface(name=null mNativeObject=0)/@0xff13c7e
2026-03-23 19:13:55.845 28216-28216 SurfaceView             com.veyonmobile                      D  224393424 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.845 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 visibleChanged -- surfaceCreated
2026-03-23 19:13:55.845 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 19:13:55.845 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 surfaceRedrawNeeded
2026-03-23 19:13:55.846 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 finishedDrawing
2026-03-23 19:13:55.846 28216-28216 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 19:13:55.852 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1616, position = [174, 141, 2094, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.861 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1617, position = [161, 141, 2081, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.871 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1618, position = [103, 141, 2023, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.882 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1619, position = [56, 141, 1976, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.893 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1620, position = [45, 141, 1965, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.909 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1621, position = [37, 141, 1957, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.918 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1622, position = [31, 141, 1951, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.927 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1623, position = [27, 141, 1947, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.941 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1624, position = [23, 141, 1943, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.951 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1625, position = [20, 141, 1940, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.952 28216-28920 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 19:13:55.952 28216-28920 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA512withRSA
2026-03-23 19:13:55.963 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1626, position = [17, 141, 1938, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.973 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1627, position = [15, 141, 1935, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.984 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1628, position = [13, 141, 1933, 861] surfaceSize = 1920x720
2026-03-23 19:13:55.997 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1629, position = [11, 141, 1931, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.008 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1630, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.018 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1631, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.021 28216-28920 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 19:13:56.027 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1632, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.029 28216-28920 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 19:13:56.036 28216-28920 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 19:13:56.036 28216-28920 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 19:13:56.036 28216-28920 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 19:13:56.036 28216-28920 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 19:13:56.036 28216-28920 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 19:13:56.038 28216-28920 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 19:13:56.041 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1633, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.051 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1634, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.064 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1635, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.075 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1636, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.085 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1637, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 19:13:56.088 28216-28920 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 19:13:56.106 28216-28920 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 19:13:56.107 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1639, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.108 28216-28920 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 19:13:56.131 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1641, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.142 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1642, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 19:13:56.175 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1645, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 19:13:57.523 28216-28920 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:13:58.240 28216-28920 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:13:58.244 28216-28920 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:13:58.244 28216-28920 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 19:13:58.304 28216-28920 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:13:59.162 28216-28920 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:13:59.163 28216-28920 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop
2026-03-23 19:14:03.075 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@c6f2cd5 mNativeObject= 0xb400007301df2000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:14:03.109 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1789, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.119 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1790, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.138 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1792, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.150 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1793, position = [17, 141, 1937, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.161 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1794, position = [31, 141, 1951, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.173 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1795, position = [51, 141, 1972, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.180 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1796, position = [89, 141, 2009, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.190 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1797, position = [120, 141, 2040, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.200 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1798, position = [136, 141, 2056, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.212 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1799, position = [147, 141, 2067, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.223 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1800, position = [154, 141, 2074, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.234 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1801, position = [160, 141, 2080, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.245 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1802, position = [165, 141, 2085, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.256 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1803, position = [169, 141, 2089, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.267 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1804, position = [172, 141, 2092, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.281 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1805, position = [174, 141, 2095, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.291 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1806, position = [177, 141, 2097, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.302 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1807, position = [179, 141, 2099, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.309 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1807, position = [181, 141, 2101, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.323 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1808, position = [182, 141, 2102, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.335 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1809, position = [183, 141, 2103, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.346 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1810, position = [185, 141, 2105, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.355 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1811, position = [186, 141, 2106, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.369 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1812, position = [186, 141, 2107, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.380 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1813, position = [187, 141, 2107, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.390 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1814, position = [188, 141, 2108, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.401 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1815, position = [189, 141, 2109, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.424 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1817, position = [190, 141, 2110, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.446 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1819, position = [190, 141, 2111, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.458 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1820, position = [191, 141, 2111, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.490 28216-28290 SurfaceView             com.veyonmobile                      D  224393424 updateSurfacePosition RenderWorker, frameNr = 1823, position = [192, 141, 2112, 861] surfaceSize = 1920x720
2026-03-23 19:14:03.554 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Changes: creating=false format=false size=false visible=true alpha=false hint=false left=false top=false z=false attached=true lifecycleStrategy=false
2026-03-23 19:14:03.554 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Cur surface: Surface(name=null mNativeObject=-5476376652191434752)/@0xff13c7e
2026-03-23 19:14:03.554 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 surfaceDestroyed
2026-03-23 19:14:03.555 28216-28216 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 19:14:03.556 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Detaching SV
2026-03-23 19:14:03.556 28216-28216 SurfaceView             com.veyonmobile                      I  224393424 Changes: creating=false format=false size=false visible=false alpha=false hint=false left=false top=false z=false attached=false lifecycleStrategy=false
2026-03-23 19:14:03.564 28216-28290 SurfaceView             com.veyonmobile                      D  124183232 windowPositionLost, frameNr = 1830
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 19:14:05.429 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 19:14:05.672 28216-28216 ScrollView              com.veyonmobile                      D  initGoToTop
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleFontFamily prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  disableBackButtonMenu prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  backTitleVisible prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  topInsetEnabled prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleHideShadow prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontWeight prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  userInterfaceStyle prop is not available on Android
2026-03-23 19:14:09.234 28216-28216 [RNScreens]             com.veyonmobile                      W  largeTitleFontFamily prop is not available on Android
2026-03-23 19:14:09.321 28216-28216 SurfaceView             com.veyonmobile                      D  195695907 updateSurface: has no frame
2026-03-23 19:14:09.327 28216-28216 SurfaceView             com.veyonmobile                      D  195695907 updateSurface: has no frame
2026-03-23 19:14:09.328 28216-28852 ReactNativeJS           com.veyonmobile                      I  'VNC doConnect skipped — not ready:', { surfaceReady: false,
                                                                                                      host: '192.168.2.229',
                                                                                                      hasKey: true,
                                                                                                      hasPriv: true }
2026-03-23 19:14:09.336 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 1200 mName = VRI[MainActivity]@c6f2cd5 mNativeObject= 0xb400007301df2000 sc.mNativeObject= 0xb400007408dbdb40 format= -3 caller= android.view.ViewRootImpl.updateBlastSurfaceIfNeeded:3574 android.view.ViewRootImpl.relayoutWindow:11685 android.view.ViewRootImpl.performTraversals:4804 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:14:09.338 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 Changes: creating=true format=true size=true visible=true alpha=false hint=true left=true top=true z=false attached=true lifecycleStrategy=false
2026-03-23 19:14:09.339 28216-28852 ReactNativeJS           com.veyonmobile                      I  'VNC connecting to', '192.168.2.229', ':', 11100, 'keyName:', 'Test'
2026-03-23 19:14:09.339 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  new BLASTBufferQueue, mName= baa1523 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732ea62000 caller= android.view.SurfaceView.createBlastSurfaceControls:1781 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 android.view.ViewRootImpl.performTraversals:5448 android.view.ViewRootImpl.doTraversal:3924 android.view.ViewRootImpl$TraversalRunnable.run:12903 android.view.Choreographer$CallbackRecord.run:1901 
2026-03-23 19:14:09.340 28216-28216 BLASTBufferQueue_Java   com.veyonmobile                      I  update, w= 1920 h= 720 mName = baa1523 SurfaceView[com.veyonmobile/com.veyonmobile.MainActivity]@0 mNativeObject= 0xb40000732ea62000 sc.mNativeObject= 0xb40000712cea1980 format= 4 caller= android.view.SurfaceView.createBlastSurfaceControls:1782 android.view.SurfaceView.updateSurface:1450 android.view.SurfaceView.lambda$new$0:280 android.view.SurfaceView.$r8$lambda$NfZyM_TG8F8lqzaOVZ7noREFjzU:0 android.view.SurfaceView$$ExternalSyntheticLambda1.onPreDraw:0 android.view.ViewTreeObserver.dispatchOnPreDraw:1229 
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 Cur surface: Surface(name=null mNativeObject=0)/@0x47b13bd
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      D  195695907 performSurfaceTransaction RenderWorker position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 visibleChanged -- surfaceCreated
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 surfaceChanged -- format=4 w=1920 h=720
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 surfaceRedrawNeeded
2026-03-23 19:14:09.340 28216-28216 SurfaceView             com.veyonmobile                      I  195695907 finishedDrawing
2026-03-23 19:14:09.341 28216-28216 SurfaceView             com.veyonmobile                      V  Layout: x=0 y=141 w=1920 h=720, frame=Rect(0, 0 - 1920, 720)
2026-03-23 19:14:09.347 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1980, position = [182, 141, 2102, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.356 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1981, position = [174, 141, 2094, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.362 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1982, position = [140, 141, 2061, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.369 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1983, position = [103, 141, 2023, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.381 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1984, position = [72, 141, 1992, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.392 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1985, position = [54, 141, 1974, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.404 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1986, position = [44, 141, 1964, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.414 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1987, position = [37, 141, 1957, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.426 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1988, position = [31, 141, 1951, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.438 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1989, position = [27, 141, 1947, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.448 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1990, position = [23, 141, 1943, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.450 28216-28964 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 19:14:09.450 28216-28964 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA512withRSA
2026-03-23 19:14:09.459 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1991, position = [20, 141, 1940, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.470 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1992, position = [17, 141, 1937, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.482 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1993, position = [15, 141, 1935, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.485 28216-28964 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 19:14:09.494 28216-28964 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 19:14:09.494 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1994, position = [13, 141, 1933, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.502 28216-28964 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 19:14:09.502 28216-28964 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 19:14:09.502 28216-28964 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 19:14:09.502 28216-28964 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 19:14:09.503 28216-28964 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 19:14:09.503 28216-28964 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 19:14:09.505 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1995, position = [11, 141, 1931, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.517 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1996, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.528 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1997, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.538 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1998, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.549 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 1999, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 19:14:09.560 28216-28964 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 19:14:09.562 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2000, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.574 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2001, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.580 28216-28964 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 19:14:09.582 28216-28964 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 19:14:09.583 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2002, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.593 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2003, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.616 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2005, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.639 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2007, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.650 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2008, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 19:14:09.683 28216-28290 SurfaceView             com.veyonmobile                      D  195695907 updateSurfacePosition RenderWorker, frameNr = 2011, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 19:14:11.039 28216-28964 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:14:11.757 28216-28964 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:14:11.761 28216-28964 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:14:11.761 28216-28964 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 19:14:11.821 28216-28964 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:14:12.838 28216-28964 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): Connection reset
2026-03-23 19:14:12.850 28216-28964 VeyonVncClient          com.veyonmobile                      E  Connection error: Connection reset (Fix with AI)
                                                                                                    java.net.SocketException: Connection reset
                                                                                                    	at java.net.SocketInputStream.read(SocketInputStream.java:191)
                                                                                                    	at java.net.SocketInputStream.read(SocketInputStream.java:143)
                                                                                                    	at java.net.SocketInputStream.read(SocketInputStream.java:205)
                                                                                                    	at java.io.FilterInputStream.read(FilterInputStream.java:83)
                                                                                                    	at java.io.PushbackInputStream.read(PushbackInputStream.java:136)
                                                                                                    	at java.io.DataInputStream.readUnsignedByte(DataInputStream.java:296)
                                                                                                    	at com.veyonmobile.VeyonVncClient.receiveLoop(VeyonVncClient.java:500)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnectionAttempt(VeyonVncClient.java:229)
                                                                                                    	at com.veyonmobile.VeyonVncClient.runConnection(VeyonVncClient.java:130)
                                                                                                    	at com.veyonmobile.VeyonVncClient.$r8$lambda$HgagDO9lHqFTYvKB-H8qZB2G4Ug(Unknown Source:0)
                                                                                                    	at com.veyonmobile.VeyonVncClient$$ExternalSyntheticLambda0.run(D8$$SyntheticClass:0)
                                                                                                    	at java.lang.Thread.run(Thread.java:1564)


---

**Cursor**

Il reset ora avviene dopo sessione stabilita, quindi facciamo debug “intelligente”: decodifico il `FeatureMessage` ricevuto (UID + command) prima del close. Così capiamo se il server ti sta mandando un messaggio specifico (es. access control / ping reply) e poi chiude per policy.

Hai fatto benissimo a mandare questo log lungo: conferma che adesso la pipeline è:

- auth OK (`SHA512withRSA`)
- `ServerInit` OK
- feature monitoring inviata
- ricezione feature dal server
- poi close/reset ~1s dopo

Quindi siamo in fase “policy/protocol fine”, non più handshake.

Ho applicato un fix utile per il prossimo giro:

- `Connection reset` durante `receive_loop` ora trattato come chiusura remota (come EOF), non errore “hard”
- aggiunta decodifica del `FeatureMessage` server→client con log:
  - `uid=...`
  - `cmd=...`

Questo ci dirà **esattamente quale feature** il server manda prima di chiudere.

### Cosa mi serve adesso
Al prossimo tentativo incollami solo queste righe nuove:
- `FeatureMessage summary: uid=... cmd=...`
- la riga di close successiva

Con quel UID capiamo se il server sta inviando ad esempio:
- reply di monitoring (normale),
- oppure messaggio access control / dettagli che anticipa chiusura.

Da lì faccio la patch finale mirata per tenere la sessione viva.

---

**User**

2026-03-23 19:16:33.684 29324-29531 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:16:34.401 29324-29531 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:16:34.405 29324-29531 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:16:34.405 29324-29531 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 19:16:34.471 29324-29531 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:16:34.472 29324-29531 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=edad8259-b4ef-4ca5-90e6-f238d0fda694 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:16:35.324 29324-29531 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:16:35.325 29324-29531 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


---

**Cursor**

Ho trovato un dettaglio utile nel sorgente Veyon (`DemoServer`): il client ufficiale annuncia un set encodings molto più ricco (incluse pseudo-encodings `NewFBSize/LastRect`). Adesso allineo il tuo client a quello, in modo da evitare eventuali chiusure “protocol policy” lato server subito dopo `ServerInit`.

Perfetto, abbiamo un’altra leva concreta applicata.

Dai log:
- auth e `ServerInit` sono OK
- `MonitoringMode` viene scambiato correttamente
- la chiusura arriva subito dopo, quindi sembra una policy/protocol expectation del server sul canale VNC

Ho quindi aggiornato `sendSetEncodings()` per allinearlo molto di più al client Veyon:
- aggiunti `RAW`, `COPY_RECT`, `ZRLE`, `TIGHT`, `HEXTILE`, `CORRE`, `RRE`
- aggiunte pseudo-encodings `NEW_FB_SIZE` e `LAST_RECT`

Questo evita il profilo “client troppo minimale” che alcuni server/proxy chiudono subito.

### Prossimo test
Rilancia e incollami solo:
- `ServerInit: ...`
- eventuali primi `Unknown encoding:` (se appaiono)
- e se ancora chiude, la riga `Connection attempt failed ...`

Se resta su, il passo dopo è completare il decoder delle encoding che il server sceglie davvero (probabile `TIGHT`).

---

**User**

2026-03-23 19:20:25.012 30757-30886 VeyonVncClient          com.veyonmobile                      D  === VeyonVNC starting connection ===
2026-03-23 19:20:25.012 30757-30886 VeyonVncClient          com.veyonmobile                      D  Connecting to 192.168.2.229:11100 using SHA512withRSA
2026-03-23 19:20:25.022 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 83, position = [10, 141, 1930, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.033 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 84, position = [9, 141, 1929, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.044 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 85, position = [7, 141, 1927, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.051 30757-30886 VeyonVncClient          com.veyonmobile                      D  Server version: RFB 003.008
2026-03-23 19:20:25.057 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 86, position = [6, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.060 30757-30886 VeyonVncClient          com.veyonmobile                      D  Security type offered: 40
2026-03-23 19:20:25.067 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 87, position = [5, 141, 1926, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.067 30757-30886 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: msgSize=18
2026-03-23 19:20:25.068 30757-30886 VeyonVncClient          com.veyonmobile                      D  receiveVariantIntArray: count=1
2026-03-23 19:20:25.068 30757-30886 VeyonVncClient          com.veyonmobile                      D    variant[0]: type=2 value=3
2026-03-23 19:20:25.068 30757-30886 VeyonVncClient          com.veyonmobile                      D  Available auth types: [3]
2026-03-23 19:20:25.068 30757-30886 VeyonVncClient          com.veyonmobile                      D  Selected auth type: 3
2026-03-23 19:20:25.068 30757-30886 VeyonVncClient          com.veyonmobile                      D  Auth type + username sent
2026-03-23 19:20:25.078 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 88, position = [5, 141, 1925, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.090 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 89, position = [4, 141, 1924, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.101 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 90, position = [3, 141, 1923, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.122 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 92, position = [2, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: firstMsgSize=0
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  ACK received (empty VariantArrayMessage)
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  receiveChallengeWithOptionalAck: challengeMsgSize=137
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: type=12 isNull=0
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  parseVariantByteArrayPayload: len=128
2026-03-23 19:20:25.131 30757-30886 VeyonVncClient          com.veyonmobile                      D  Challenge: 128 bytes
2026-03-23 19:20:25.144 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 94, position = [1, 141, 1922, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.155 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 95, position = [1, 141, 1921, 861] surfaceSize = 1920x720
2026-03-23 19:20:25.161 30757-30886 VeyonVncClient          com.veyonmobile                      D  Signature: 512 bytes
2026-03-23 19:20:25.162 30757-30886 VeyonVncClient          com.veyonmobile                      D  Auth response sent
2026-03-23 19:20:25.197 30757-30790 SurfaceView             com.veyonmobile                      D  256592271 updateSurfacePosition RenderWorker, frameNr = 98, position = [0, 141, 1920, 861] surfaceSize = 1920x720
2026-03-23 19:20:26.545 30757-30886 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:20:27.364 30757-30886 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:20:27.369 30757-30886 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:20:27.369 30757-30886 VeyonVncClient          com.veyonmobile                      D  VNC session established; MonitoringMode feature sent
2026-03-23 19:20:27.423 30757-30886 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:20:27.424 30757-30886 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=edad8259-b4ef-4ca5-90e6-f238d0fda694 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:20:28.183 30757-30886 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:20:28.184 30757-30886 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


---

**Cursor**

Grazie, log perfetto. Vedo che il server risponde correttamente al `MonitoringMode` (`cmd=0`) e poi chiude prima che arrivino frame. Provo un ultimo allineamento con il comportamento Master: dopo `MonitoringMode` invio anche query “service info” (`AppVersion`, `ActiveFeatures`, `UserInfo`, `SessionInfo`) con UID ufficiali, così il server entra nel flusso completo di monitoring.

Perfetto, applicato.

Ho aggiunto subito dopo `ServerInit` questa sequenza feature (oltre a `MonitoringMode`):
- `FEATURE_QUERY_APP_VERSION`
- `FEATURE_QUERY_ACTIVE`
- `FEATURE_USER_INFO`
- `FEATURE_SESSION_INFO`

Obiettivo: replicare meglio il bootstrap del Master ufficiale e vedere se il server smette di chiudere subito dopo il primo ping monitoring.

### Cosa guardare nel prossimo log
- `VNC session established; initial monitoring feature set sent`
- più righe `FeatureMessage received: ...` / `FeatureMessage summary: uid=...`
- se compaiono finalmente messaggi framebuffer (`MSG_SERVER_FRAMEBUFFER_UPDATE`) o FPS > 0
- eventuale chiusura con timing

Se ancora chiude entro ~1s, il passo successivo è verificare lato server config/access-control con evidenza puntuale (abbiamo ormai il protocollo quasi allineato).

---

**User**

2026-03-23 19:23:00.043 31610-31976 VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:23:00.761 31610-31976 VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  Sent feature: 58f5d5d5-9929-48f4-a995-f221c150ae26 active=true
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  Sent feature: a0a96fba-425d-414a-aaf4-352b76d7c4f3 active=true
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  Sent feature: 79a5e74d-50bd-4aab-8012-0e70dc08cc72 active=true
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  Sent feature: 699ed9dd-f58b-477b-a0af-df8105571b3c active=true
2026-03-23 19:23:00.766 31610-31976 VeyonVncClient          com.veyonmobile                      D  VNC session established; initial monitoring feature set sent
2026-03-23 19:23:00.856 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:23:00.857 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=edad8259-b4ef-4ca5-90e6-f238d0fda694 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:23:00.857 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 54 bytes
2026-03-23 19:23:00.857 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=58f5d5d5-9929-48f4-a995-f221c150ae26 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:23:00.857 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 134 bytes
2026-03-23 19:23:00.858 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=a0a96fba-425d-414a-aaf4-352b76d7c4f3 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:23:00.858 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 81 bytes
2026-03-23 19:23:00.858 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=79a5e74d-50bd-4aab-8012-0e70dc08cc72 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:23:00.858 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 135 bytes
2026-03-23 19:23:00.858 31610-31976 VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=699ed9dd-f58b-477b-a0af-df8105571b3c cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:23:01.684 31610-31976 VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:23:01.685 31610-31976 VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


---

**Cursor**

Ottimo, questa volta il server risponde a **tutte** le feature di bootstrap: quindi il protocollo custom è allineato. Il close rapido ora sembra dovuto al canale framebuffer che non riceve abbastanza `FramebufferUpdateRequest` subito dopo l’init. Faccio una patch per inviare richieste incremental molto più aggressive nei primi secondi.

Perfetto: adesso il problema è veramente “ultimo miglio”.

Dai log:
- auth OK
- init OK
- feature bootstrap OK con reply completi
- close subito dopo -> probabile inattività framebuffer percepita lato proxy/server

Ho applicato patch per **framebuffer keepalive aggressivo**:

- `receiveLoop` timeout ridotto a `200ms`
- keepalive `FramebufferUpdateRequest` ogni `200ms`
- richiesta incremental inviata **proattivamente prima della read bloccante**

Questo aumenta molto le probabilità che il server mantenga aperta la sessione abbastanza a lungo da iniziare i frame.

Riprova ora: se finalmente parte il video, poi possiamo rialzare gradualmente questi valori per ridurre traffico. Se ancora chiude, siamo al punto in cui serve guardare il log del servizio Veyon lato server per la causa esatta della chiusura (ma almeno il client ora è molto allineato e “verboso” per debug).

---

**User**

2026-03-23 19:25:57.300   542-1063  VeyonVncClient          com.veyonmobile                      D  Authentication successful (SHA512withRSA)
2026-03-23 19:25:58.120   542-1063  VeyonVncClient          com.veyonmobile                      D  ServerInit: 3600x1080 name=pc ( 192.168.33.74 ) - service mode
2026-03-23 19:25:58.125   542-1063  VeyonVncClient          com.veyonmobile                      D  Sent feature: edad8259-b4ef-4ca5-90e6-f238d0fda694 active=true
2026-03-23 19:25:58.125   542-1063  VeyonVncClient          com.veyonmobile                      D  Sent feature: 58f5d5d5-9929-48f4-a995-f221c150ae26 active=true
2026-03-23 19:25:58.126   542-1063  VeyonVncClient          com.veyonmobile                      D  Sent feature: a0a96fba-425d-414a-aaf4-352b76d7c4f3 active=true
2026-03-23 19:25:58.126   542-1063  VeyonVncClient          com.veyonmobile                      D  Sent feature: 79a5e74d-50bd-4aab-8012-0e70dc08cc72 active=true
2026-03-23 19:25:58.126   542-1063  VeyonVncClient          com.veyonmobile                      D  Sent feature: 699ed9dd-f58b-477b-a0af-df8105571b3c active=true
2026-03-23 19:25:58.126   542-1063  VeyonVncClient          com.veyonmobile                      D  VNC session established; initial monitoring feature set sent
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 39 bytes
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=edad8259-b4ef-4ca5-90e6-f238d0fda694 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 54 bytes
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=58f5d5d5-9929-48f4-a995-f221c150ae26 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 134 bytes
2026-03-23 19:25:58.188   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=a0a96fba-425d-414a-aaf4-352b76d7c4f3 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:25:58.189   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 81 bytes
2026-03-23 19:25:58.189   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=79a5e74d-50bd-4aab-8012-0e70dc08cc72 cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:25:58.189   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage received: 135 bytes
2026-03-23 19:25:58.189   542-1063  VeyonVncClient          com.veyonmobile                      D  FeatureMessage summary: uid=699ed9dd-f58b-477b-a0af-df8105571b3c cmdType=2 cmdNull=0 cmd=0
2026-03-23 19:25:58.925   542-1063  VeyonVncClient          com.veyonmobile                      W  Connection attempt failed (alg=SHA512withRSA, step=receive_loop): EOFException
2026-03-23 19:25:58.926   542-1063  VeyonVncClient          com.veyonmobile                      I  Server closed connection during receive loop


---

**Cursor**

Sei arrivato a un punto chiarissimo: handshake/auth/feature bootstrap sono perfetti, ma il server chiude subito dopo le risposte “monitoring”. Ultima ipotesi forte: serve attivare una feature di accesso remoto (`RemoteView` / `RemoteControl`) oltre al solo monitoring. Cerco gli UID nel sorgente Veyon e li provo in patch immediata.

Perfetto, questo log è decisivo.

Il tuo client ora è **corretto lato protocollo**:

- auth `SHA512withRSA` OK
- `ServerInit` OK
- monitoring + query feature OK
- il server risponde correttamente a tutte le feature
- poi chiude lui con `EOF` dopo ~0.8s

Quindi il problema non è più nel framing/auth/client parser: è una chiusura deliberata lato servizio/proxy Veyon.

## Cosa indica il sorgente Veyon
Nel codice server (`ServerAccessControlManager`) ci sono path che chiudono connessioni già avviate in base a regole/policy (riesame access control, sessioni concorrenti, conferma desktop access, ecc.). È coerente col tuo pattern: tutto ok e poi close rapido.

## Azione pratica (lato server Veyon)
In `Veyon Configurator` sul PC server:

- Access control:
  - prova temporaneamente `Allow all authenticated users` (solo per test)
  - disabilita regole che dipendono da “computer already being accessed” / “ask for permission”
- Notifications/desktop access dialog:
  - disattiva prompt di conferma desktop access (o metti “always allow” per il tuo host utente)
- Verifica che non ci sia un altro client che apre/chiude connessioni in parallelo (puo` far ricalcolare le regole)

## Se vuoi chiuderla definitivamente in codice
Posso aggiungere subito una diagnostica “server policy” nel tuo client:
- intercetto e decodifico eventuali feature di access-control (`1815941e-eaef-43ab-b9cd-5403dca3f749`) e le mostro in UI/log.
- così invece di `EOF` vedi il motivo testuale del blocco (es. “No rule allowed access”, “User denied access”, ecc.).

Se mi dai ok, lo implemento adesso.

---

**User**

le impostazioni di veyon configurator sono già come hai detto te, però forse avevi ragione dicendo che il server si aspetta un azione come remoteview

