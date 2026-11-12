# GenieScan

Prototipo Android nativo di **Cloud Scan / Smart Poly**. Repository principale: Frak89/Geniescan. Cloudy-Scanner e le immagini Blender fornite sono riferimenti di prodotto, non codice incorporato.

## Stato reale (v0.1.0 / prototipo)
- **Acquisizione 3D reale**: ARCore point cloud con pose in coordinate mondo e, quando supportata, campionamento di mappe ARCore Depth 16 bit.
- **Accumulo di punti** con deduplicazione voxel, filtro di confidenza e limite di memoria.
- **Anteprima** fotocamera OpenGL ES e modalità studio 3D orbitabile, punti e superficie parametrica, wireframe e backface culling.
- **Smart primitives esplicite**: box, cilindro, vaso e tavolo parametrici costruiti sulle dimensioni osservate; low/mid/high poligonale (6/12/32 lati per forme di rivoluzione).
- **Focus**: bersaglio ARCore al tocco o sul centro immagine, acquisizione dei punti entro un raggio selezionato.
- **Foto multi-vista**: pulsante FOTO oppure AUTO FOTO, salvataggio JPEG in cache (max 48 auto); filtro di nitidezza e distanza minima tra pose.
- **Copertura 360°**: contatore degli 8 settori angolari attorno al focus.
- **Pulizia selettiva**: evidenzia in viola i punti nel raggio FOCUS e rimuovili con ELIMINA.
- **Camera info**: caratteristiche intrinseche e capability depth riportate da Camera2.
- **Esportazione OBJ** via Storage Access Framework (formato triangolato con conteggi di vertici, triangoli, quads primitivi prima della triangolazione).
- **Selettore modello** (ONNX/TFLite) con URI persistente SAF: il file resta nella cartella scelta, ma NON c'è ancora esecuzione inferenza.

## Limiti da conoscere
Questa non è ancora una soluzione fotogrammetrica multi-foto o un modellatore NURBS. I primitive fit sono proposte basate su bounding box, **non** un riconoscimento semantico IA e non sono equivalenti alla scansione accurata di tutti i dettagli. La Depth API restituisce stime con possibili errori e superfici mobili possono introdurre artefatti. Se mancano tracking/depth, si usano i feature points ARCore, non dati fittizi. Le coordinate intrinseche vengono approssimate per mappare depth pixels e vanno calibrate/testate sul device.

Le funzionalità future (non disponibili in v0.1.0): AI on-device per riconoscimento oggetti; allineamento e fusione fotogrammetrica di foto multi-vista; texture 256 colori; NURBS/NURBS edge fitting; pulizia viola; quad mesh re-topology; animazione; ottimizzazione intelligente dei punti di ripresa; buffer incrementale del modello.

## Compilazione
Requisiti: JDK 17+, Android SDK Platform 36, Gradle 8.11.1, connessione durante il build per dipendenze Android/ARCore. 
```bash
gradle --no-daemon :app:assembleDebug
```
APK debug in `app/build/outputs/apk/debug/app-debug.apk`. Il workflow GitHub Actions compila automaticamente e pubblica un artifact ZIP contenente l'APK.

**Versioni**: compileSdk 36, targetSdk 36, minSdk 29 (Android 10+); funziona su Android 17 (API 37) con compatibilità API 36. ARCore 1.56.0.

## Uso
Consenti fotocamera e Google Play Services per AR. Scansiona lentamente muovendo il telefono, evitando oggetti in movimento. Premi STUDIO per ispezionare; FOCUS per restringere le nuove acquisizioni; forma BOX/TAVOLO/CILINDRO/VASO per ricavare un solido parametrico dal volume acquisito. Tocca ESPORTA per salvare OBJ. La modalità LOW/MID/HIGH modifica i segmenti delle forme curve; non inventa dettagli non misurati. AUTO FOTO salva in cache gli scatti nitidi da pose sufficientemente diverse.

## Privacy
Nessun server per le immagini né telemetria dedicata nel codice di GenieScan. ARCore può avere i propri componenti e requisiti di servizio Google. Tutti i modelli utente rimangono nel percorso selezionato.