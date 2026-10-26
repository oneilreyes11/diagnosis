import DiagnosisCore
import Foundation
import Network

/// ELM327 WiFi por TCP. La primera vez iOS pide permiso de "red local".
final class WifiByteLink: NSObject, ByteLink {
    let displayName: String
    private let host: String
    private let port: UInt16
    private let queue = DispatchQueue(label: "com.sistemadiagnosis.wifi")
    private var connection: NWConnection?

    init(host: String, port: UInt16) {
        self.host = host
        self.port = port
        displayName = "WiFi \(host):\(port)"
        super.init()
    }

    func open(receiver: ByteLinkReceiver) {
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
            receiver.onClosed(error: "Puerto no válido")
            return
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        self.connection = connection
        var opened = false
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                opened = true
                receiver.onOpened()
                self?.receive(on: connection, receiver: receiver)
            case .waiting(let error):
                // Sin ruta al adaptador: normalmente el iPhone no está en su red WiFi.
                if !opened {
                    connection.cancel()
                    receiver.onClosed(error: "No se alcanza el adaptador (\(error.localizedDescription)). ¿Está el iPhone en su red WiFi?")
                }
            case .failed(let error):
                receiver.onClosed(error: error.localizedDescription)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(on connection: NWConnection, receiver: ByteLinkReceiver) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            if let data, !data.isEmpty {
                receiver.onBytes(data: data.toKotlinByteArray())
            }
            if let error {
                receiver.onClosed(error: error.localizedDescription)
            } else if isComplete {
                receiver.onClosed(error: "El adaptador cerró la conexión")
            } else {
                self?.receive(on: connection, receiver: receiver)
            }
        }
    }

    func write(data: KotlinByteArray) {
        connection?.send(content: data.toData(), completion: .contentProcessed { _ in })
    }

    func close() {
        connection?.cancel()
        connection = nil
    }
}
