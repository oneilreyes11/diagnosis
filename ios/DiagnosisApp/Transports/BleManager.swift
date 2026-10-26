import CoreBluetooth
import Foundation

struct BleDevice: Identifiable, Equatable {
    let peripheral: CBPeripheral
    let name: String
    var rssi: Int
    var id: UUID { peripheral.identifier }
}

/// Un único CBCentralManager para toda la app: un periférico solo se puede
/// conectar desde el central que lo descubrió.
final class BleManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    static let shared = BleManager()

    @Published private(set) var devices: [BleDevice] = []
    @Published private(set) var state: CBManagerState = .unknown
    @Published private(set) var isScanning = false

    // Se crea al usarlo: así iOS no pide permiso de Bluetooth a quien solo usa WiFi.
    private lazy var central = CBCentralManager(delegate: self, queue: .main)
    private var scanWhenReady = false
    private var connectHandlers: [UUID: (String?) -> Void] = [:]
    private var disconnectHandlers: [UUID: (String?) -> Void] = [:]

    func activate() {
        _ = central
    }

    func startScan() {
        guard central.state == .poweredOn else {
            scanWhenReady = true
            return
        }
        devices = []
        // Sin filtro de servicios: cada clon ELM327 BLE anuncia UUIDs distintos.
        central.scanForPeripherals(withServices: nil)
        isScanning = true
    }

    func stopScan() {
        scanWhenReady = false
        guard isScanning else { return }
        central.stopScan()
        isScanning = false
    }

    func connect(_ peripheral: CBPeripheral,
                 onConnected: @escaping (String?) -> Void,
                 onDisconnected: @escaping (String?) -> Void) {
        stopScan()
        connectHandlers[peripheral.identifier] = onConnected
        disconnectHandlers[peripheral.identifier] = onDisconnected
        central.connect(peripheral)
    }

    func cancel(_ peripheral: CBPeripheral) {
        central.cancelPeripheralConnection(peripheral)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
        if central.state == .poweredOn, scanWhenReady {
            scanWhenReady = false
            startScan()
        } else if central.state != .poweredOn {
            isScanning = false
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
        // Los adaptadores OBD siempre anuncian nombre; así se ocultan relojes, auriculares sin nombre, etc.
        guard let name, !name.isEmpty else { return }
        if let index = devices.firstIndex(where: { $0.id == peripheral.identifier }) {
            devices[index].rssi = RSSI.intValue
        } else {
            devices.append(BleDevice(peripheral: peripheral, name: name, rssi: RSSI.intValue))
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectHandlers.removeValue(forKey: peripheral.identifier)?(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        disconnectHandlers.removeValue(forKey: peripheral.identifier)
        connectHandlers.removeValue(forKey: peripheral.identifier)?(error?.localizedDescription ?? "No se pudo conectar")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectHandlers.removeValue(forKey: peripheral.identifier)?(error?.localizedDescription ?? "Desconectado")
        disconnectHandlers.removeValue(forKey: peripheral.identifier)?(error?.localizedDescription)
    }
}
