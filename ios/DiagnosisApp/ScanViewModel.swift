import DiagnosisCore
import Foundation

enum ConnectionKind: String, CaseIterable, Identifiable {
    case wifi = "WiFi"
    case bluetooth = "Bluetooth"
    case simulator = "Simulador"

    var id: String { rawValue }
}

@MainActor
final class ScanViewModel: ObservableObject {
    @Published var kind: ConnectionKind = .wifi
    @Published var wifiHost = "192.168.0.10"
    @Published var wifiPort = "35000"
    @Published var selectedDevice: BleDevice?
    @Published private(set) var isRunning = false
    @Published private(set) var result: QuickScanResult?
    @Published private(set) var errorMessage: String?

    func scan() async {
        errorMessage = nil
        guard let session = makeSession() else { return }
        isRunning = true
        result = nil
        defer { isRunning = false }
        do {
            result = try await session.quickScan()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func makeSession() -> DiagnosisSession? {
        switch kind {
        case .simulator:
            return DiagnosisSession.companion.simulated()
        case .wifi:
            guard let port = UInt16(wifiPort) else {
                errorMessage = "Puerto no válido"
                return nil
            }
            let link = WifiByteLink(host: wifiHost.trimmingCharacters(in: .whitespaces), port: port)
            return DiagnosisSession(transport: CallbackTransport(link: link, openTimeoutMs: 10_000))
        case .bluetooth:
            guard let device = selectedDevice else {
                errorMessage = "Elige un adaptador de la lista"
                return nil
            }
            return DiagnosisSession(transport: CallbackTransport(link: BleByteLink(device: device), openTimeoutMs: 15_000))
        }
    }
}
