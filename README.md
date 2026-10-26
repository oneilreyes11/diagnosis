# Sistema de diagnosis OBD

Sistema propio de diagnosis de vehículos sobre adaptadores ELM327, para
**iPhone**, Android y PC con un único núcleo en Kotlin Multiplatform.

## App web (la forma principal de usarlo)

Web instalable (PWA) que habla con el adaptador por **Web Bluetooth**. Gratis, sin
Mac y sin cuenta de desarrollador.

| Dispositivo | Cómo abrirla |
|---|---|
| iPhone / iPad | App gratuita [Bluefy](https://apps.apple.com/app/id1492822055) (Safari no tiene Web Bluetooth) |
| Android | Chrome |
| PC / Mac | Chrome o Edge |

Solo adaptadores **Bluetooth LE**. Uso: enciende el contacto, pulsa *Elegir adaptador*,
elige el tuyo en la lista y pulsa *Leer vehículo*. El modo *Simulador* sirve para
probarla sin coche, y *Tráfico con el adaptador* muestra cada comando enviado.

Se publica sola en GitHub Pages (`.github/workflows/web.yml`) con cada push a `main`.
En local:

```bash
./gradlew :web:jsBrowserDistribution    # genera web/build/dist/js/productionExecutable
```

Web Bluetooth exige HTTPS (o `localhost`).

## Fase 2 (actual): motor del Audi A3 8Y 1.5 TFSI (DFYA)

- **Averías** confirmadas, pendientes y permanentes, con ficha en español de más de 120
  códigos: qué significa, síntomas, causas probables ordenadas, gravedad, qué hacer y
  notas propias del 1.5 TFSI (`core/.../knowledge`, `core/.../vehicle/Vehicles.kt`).
- **Borrado** de averías con confirmación y explicación de sus efectos.
- **Datos congelados** del momento del fallo.
- **Datos en vivo**: 31 sensores con gráfica, hasta 6 PIDs por petición y presión de
  turbo calculada.
- **Preparación ITV**: testigo de motor, monitores de emisiones y contadores desde el borrado.
- **Perfil del vehículo**: peticiones dirigidas solo a la centralita del motor (7E0/7E8)
  y reconocimiento por VIN.
- **Modo demostración** con un A3 simulado (fallo en el cilindro 2) para probar sin coche.

## Fase 1

- Driver ELM327: inicialización, comandos AT, detección de errores, protocolo activo.
- OBD-II: tensión de batería (`ATRV`), conexión con el vehículo (`01 00`) y VIN (`09 02`)
  en CAN y en protocolos antiguos (J1850, ISO 9141, KWP2000).
- Transportes:

| Plataforma | WiFi | Bluetooth | USB |
|---|---|---|---|
| iPhone | ✅ Network.framework | ✅ solo **Bluetooth LE** (CoreBluetooth) | — |
| Android | ✅ | ✅ clásico (SPP) | — |
| PC | ✅ | ✅ clásico emparejado (puerto COM) | ✅ |

> **iPhone y Bluetooth clásico**: Apple solo permite Bluetooth clásico a accesorios
> certificados MFi, así que los ELM327 Bluetooth "de toda la vida" no funcionan en
> iPhone. Valen los WiFi y los Bluetooth LE (Vgate iCar Pro BLE, Veepeak BLE,
> OBDLink CX…).

## Módulos

| Módulo | Contenido |
|---|---|
| `web` | App web: `WebBluetoothLink`, interfaz y PWA (manifest, service worker) |
| `core` (Kotlin Multiplatform: JVM, JS e iOS) | `DiagnosisSession`, `Elm327Driver`, `ObdClient`, `Vin`, `Transport`, `CallbackTransport`/`ByteLink` (puente para transportes nativos), `MockTransport`, `Elm327Simulator`; en JVM `WifiTransport` y `Elm327EmulatorServer` |
| `transport-serial` | `SerialTransport` (jSerialComm) |
| `cli` | Herramienta `diag` |
| `ios/` | App SwiftUI: `WifiByteLink`, `BleByteLink`, `BleManager` |
| `android/transport-android` | `AndroidBluetoothTransport` (build aparte, requiere Android SDK) |

## PC

Requisitos: JDK 17 (`JAVA_HOME`).

```bash
./gradlew build                 # compila (también el núcleo iOS) y ejecuta los tests
./gradlew :cli:installDist      # genera cli/build/install/diag
```

```bash
cli/build/install/diag/bin/diag --mock            # simulador (can, kwp, sin-vin, apagado)
cli/build/install/diag/bin/diag --wifi            # ELM327 WiFi en 192.168.0.10:35000
cli/build/install/diag/bin/diag --ports           # puertos serie disponibles
cli/build/install/diag/bin/diag --serial COM5     # ELM327 USB o Bluetooth emparejado
cli/build/install/diag/bin/diag --emulate         # el PC se hace pasar por un ELM327 WiFi
```

`--log` muestra todo el tráfico con el adaptador.

## App nativa de iPhone (opcional)

Solo hace falta para adaptadores **WiFi** en iPhone (la web no puede abrir conexiones
TCP). Compilar e instalar una app iOS requiere **macOS con Xcode** (Apple no lo permite
desde Windows).

En el Mac:

```bash
brew install xcodegen
cd ios && xcodegen        # genera Diagnosis.xcodeproj
open Diagnosis.xcodeproj
```

En Xcode: *Signing & Capabilities* → elige tu Apple ID (gratis vale para tu propio
iPhone, la app caduca a los 7 días; con cuenta de desarrollador, 1 año y TestFlight).
Conecta el iPhone y pulsa ▶. La primera compilación descarga Kotlin/Native y tarda.

Sin Mac: el workflow `.github/workflows/ci.yml` compila la app y pasa los tests del
núcleo en un simulador iOS en GitHub Actions (macOS). Para instalarla en un iPhone
sin Mac hace falta un servicio de compilación en la nube con firma (Codemagic,
Bitrise…) y una cuenta de desarrollador de Apple.

### Probar el iPhone sin coche

1. En el PC: `diag --emulate` (permite el acceso en el firewall de Windows).
2. iPhone y PC en la misma WiFi.
3. En la app: WiFi → IP del PC (la que muestra `--emulate`) → puerto 35000 → *Leer vehículo*.

## Limitaciones conocidas

- Cabeceras desactivadas (`ATH0`): si responden varias centralitas no se sabe cuál es cuál.
  Se activará en la fase 3 (UDS).
- La app iOS (Swift) no se ha compilado todavía: falta un Mac o ejecutar el CI en GitHub.
  El núcleo Kotlin sí compila para iOS y sus tests pasan en JVM.
- El módulo Android no se ha compilado (falta el Android SDK en este equipo).
