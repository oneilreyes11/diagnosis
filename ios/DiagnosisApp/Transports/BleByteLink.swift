import CoreBluetooth
import DiagnosisCore
import Foundation

/// ELM327 Bluetooth LE. Los adaptadores exponen un servicio tipo "UART" con una
/// característica de notificación (respuestas) y otra de escritura (comandos).
/// Los UUID cambian según fabricante, así que se eligen por sus propiedades.
final class BleByteLink: NSObject, ByteLink, CBPeripheralDelegate {
    let displayName: String
    private let peripheral: CBPeripheral
    private let manager: BleManager
    private var receiver: ByteLinkReceiver?
    private var pendingServices = 0
    private var notifyCharacteristic: CBCharacteristic?
    private var writeCharacteristic: CBCharacteristic?

    /// Servicios conocidos de adaptadores OBD BLE, por orden de preferencia.
    private static let preferredServices: [CBUUID] = [
        CBUUID(string: "FFF0"),
        CBUUID(string: "FFE0"),
        CBUUID(string: "18F0"),
        CBUUID(string: "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
    ]

    init(device: BleDevice, manager: BleManager = .shared) {
        peripheral = device.peripheral
        self.manager = manager
        displayName = "Bluetooth \(device.name)"
        super.init()
    }

    func open(receiver: ByteLinkReceiver) {
        self.receiver = receiver
        manager.connect(peripheral, onConnected: { [weak self] error in
            guard let self else { return }
            if let error {
                receiver.onClosed(error: error)
                return
            }
            peripheral.delegate = self
            peripheral.discoverServices(nil)
        }, onDisconnected: { error in
            receiver.onClosed(error: error ?? "Adaptador desconectado")
        })
    }

    func write(data: KotlinByteArray) {
        guard let characteristic = writeCharacteristic else { return }
        let type: CBCharacteristicWriteType =
            characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        let chunkSize = max(peripheral.maximumWriteValueLength(for: type), 20)
        let bytes = data.toData()
        var offset = 0
        while offset < bytes.count {
            let end = min(offset + chunkSize, bytes.count)
            peripheral.writeValue(bytes.subdata(in: offset..<end), for: characteristic, type: type)
            offset = end
        }
    }

    func close() {
        manager.cancel(peripheral)
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services, !services.isEmpty else {
            return fail(error?.localizedDescription ?? "El adaptador no expone servicios")
        }
        pendingServices = services.count
        services.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        pendingServices -= 1
        guard pendingServices == 0 else { return }
        guard let channel = selectChannel(in: peripheral.services ?? []) else {
            return fail("No se encontró el canal serie del adaptador. ¿Es un adaptador OBD Bluetooth LE?")
        }
        notifyCharacteristic = channel.notify
        writeCharacteristic = channel.write
        peripheral.setNotifyValue(true, for: channel.notify)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic == notifyCharacteristic else { return }
        if let error { return fail(error.localizedDescription) }
        receiver?.onOpened()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic == notifyCharacteristic, let value = characteristic.value, !value.isEmpty else { return }
        receiver?.onBytes(data: value.toKotlinByteArray())
    }

    // MARK: - Privado

    private func selectChannel(in services: [CBService]) -> (notify: CBCharacteristic, write: CBCharacteristic)? {
        for service in services.sorted(by: { rank($0) < rank($1) }) {
            let characteristics = service.characteristics ?? []
            let notify = characteristics.first { $0.properties.contains(.notify) || $0.properties.contains(.indicate) }
            let write = characteristics.first {
                $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse)
            }
            if let notify, let write { return (notify, write) }
        }
        return nil
    }

    private func rank(_ service: CBService) -> Int {
        Self.preferredServices.firstIndex(of: service.uuid) ?? Self.preferredServices.count
    }

    private func fail(_ message: String) {
        receiver?.onClosed(error: message)
        manager.cancel(peripheral)
    }
}
