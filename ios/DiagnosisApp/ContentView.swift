import CoreBluetooth
import DiagnosisCore
import SwiftUI

struct ContentView: View {
    @StateObject private var model = ScanViewModel()
    @ObservedObject private var ble = BleManager.shared

    var body: some View {
        NavigationStack {
            Form {
                Section("Conexión") {
                    Picker("Tipo", selection: $model.kind) {
                        ForEach(ConnectionKind.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    switch model.kind {
                    case .wifi: wifiRows
                    case .bluetooth: bluetoothRows
                    case .simulator:
                        Text("Vehículo simulado, sin adaptador.").foregroundStyle(.secondary)
                    }
                }

                Section {
                    Button {
                        Task { await model.scan() }
                    } label: {
                        HStack {
                            Text("Leer vehículo")
                            if model.isRunning {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(model.isRunning)
                }

                if let error = model.errorMessage {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    }
                }

                if let result = model.result {
                    ResultSections(result: result)
                }
            }
            .navigationTitle("Diagnosis")
        }
    }

    @ViewBuilder private var wifiRows: some View {
        TextField("IP del adaptador", text: $model.wifiHost)
            .keyboardType(.numbersAndPunctuation)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        TextField("Puerto", text: $model.wifiPort)
            .keyboardType(.numberPad)
        Text("Conecta antes el iPhone a la red WiFi del adaptador (Ajustes › WiFi). Para probar sin coche, ejecuta «diag --emulate» en el PC y pon aquí la IP del PC.")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }

    @ViewBuilder private var bluetoothRows: some View {
        Group {
            switch ble.state {
            case .poweredOn:
                Button(ble.isScanning ? "Buscando…" : "Buscar adaptadores") { ble.startScan() }
                    .disabled(ble.isScanning)
                ForEach(ble.devices) { device in
                    Button {
                        model.selectedDevice = device
                    } label: {
                        HStack {
                            Text(device.name)
                            Spacer()
                            Text("\(device.rssi) dBm").font(.caption).foregroundStyle(.secondary)
                            if model.selectedDevice?.id == device.id {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                    .foregroundStyle(.primary)
                }
            case .unauthorized:
                Text("Permite el acceso a Bluetooth en Ajustes › Diagnosis.").foregroundStyle(.secondary)
            case .poweredOff:
                Text("Activa el Bluetooth.").foregroundStyle(.secondary)
            case .unsupported:
                Text("Este dispositivo no tiene Bluetooth LE.").foregroundStyle(.secondary)
            default:
                ProgressView()
            }
            Text("Solo adaptadores Bluetooth LE (p. ej. Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX). Los ELM327 Bluetooth clásicos no funcionan en iPhone.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .onAppear { ble.activate() }
        .onDisappear { ble.stopScan() }
    }
}

private struct ResultSections: View {
    let result: QuickScanResult

    var body: some View {
        Section("Adaptador") {
            LabeledContent("Versión", value: result.adapter.version)
            if let voltage = result.batteryVoltage {
                LabeledContent("Batería", value: String(format: "%.1f V", voltage.doubleValue))
            }
            ForEach(result.adapter.warnings, id: \.self) { warning in
                Text(warning).font(.footnote).foregroundStyle(.secondary)
            }
        }

        Section("Vehículo") {
            if let error = result.vehicleError {
                Label(error, systemImage: "car.side").foregroundStyle(.orange)
            } else {
                if let obdProtocol = result.obdProtocol {
                    LabeledContent("Protocolo", value: obdProtocol.displayName)
                }
                if let vin = result.vin {
                    LabeledContent("VIN") { Text(vin.value).font(.body.monospaced()).textSelection(.enabled) }
                    LabeledContent("WMI · VDS · VIS", value: "\(vin.wmi) · \(vin.vds) · \(vin.vis)")
                } else {
                    LabeledContent("VIN", value: "No disponible")
                }
            }
        }
    }
}
