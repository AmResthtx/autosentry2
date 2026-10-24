import ExternalAccessory
import Foundation

/// One ELM327 conversation with the OBDLink MX+, which iPhones reach as an
/// Apple-certified (MFi) accessory under the "com.obdlink" protocol. Commands
/// go out one at a time; a reply is complete at the ELM327 ">" prompt.
/// Everything runs on the main thread (the streams are scheduled on the main run loop).
final class AccessoryLink: NSObject, StreamDelegate {
    static let protocolString = "com.obdlink"

    /// Called when the link drops on its own (stream error or end), not when close() is called.
    var onLost: (() -> Void)?

    private struct Request {
        let command: String
        let timeout: TimeInterval
        let done: (String?) -> Void
    }

    private var session: EASession?
    private var queue: [Request] = []
    private var current: Request?
    private var timeoutWork: DispatchWorkItem?
    private var pendingWrite = Data()
    private var readBuffer = ""

    var isOpen: Bool { session != nil }

    /// The paired MX+ if iOS reports it connected right now.
    static func connectedAdapter() -> EAAccessory? {
        EAAccessoryManager.shared().connectedAccessories.first {
            $0.protocolStrings.contains(protocolString)
        }
    }

    /// Opens a session. Returns false when iOS refuses one, which can mean the
    /// app still needs to be approved by OBDLink for this protocol.
    func open(_ accessory: EAAccessory) -> Bool {
        close()
        guard let newSession = EASession(accessory: accessory, forProtocol: Self.protocolString) else {
            return false
        }
        session = newSession
        let streams: [Stream?] = [newSession.inputStream, newSession.outputStream]
        for stream in streams {
            stream?.delegate = self
            stream?.schedule(in: .main, forMode: .default)
            stream?.open()
        }
        return true
    }

    /// Closes the session; anything still waiting gets a nil reply.
    func close() {
        timeoutWork?.cancel()
        timeoutWork = nil
        if let old = session {
            let streams: [Stream?] = [old.inputStream, old.outputStream]
            for stream in streams {
                stream?.close()
                stream?.remove(from: .main, forMode: .default)
                stream?.delegate = nil
            }
        }
        session = nil
        pendingWrite = Data()
        readBuffer = ""
        let waiting = (current.map { [$0] } ?? []) + queue
        current = nil
        queue = []
        waiting.forEach { $0.done(nil) }
    }

    /// Sends one ELM327 command; `done` gets the full reply, or nil on timeout or a closed link.
    func send(_ command: String, timeout: TimeInterval = 2.0, done: @escaping (String?) -> Void) {
        guard isOpen else {
            done(nil)
            return
        }
        queue.append(Request(command: command, timeout: timeout, done: done))
        startNextIfIdle()
    }

    private func startNextIfIdle() {
        guard current == nil, isOpen, !queue.isEmpty else { return }
        let next = queue.removeFirst()
        current = next
        readBuffer = ""
        pendingWrite.append(Data((next.command + "\r").utf8))
        flushWrites()
        let work = DispatchWorkItem { [weak self] in self?.finishCurrent(nil) }
        timeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + next.timeout, execute: work)
    }

    private func finishCurrent(_ reply: String?) {
        timeoutWork?.cancel()
        timeoutWork = nil
        guard let finished = current else { return }
        current = nil
        finished.done(reply)
        startNextIfIdle()
    }

    private func flushWrites() {
        guard let output = session?.outputStream, output.hasSpaceAvailable, !pendingWrite.isEmpty else { return }
        let written = pendingWrite.withUnsafeBytes { raw -> Int in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return output.write(base, maxLength: raw.count)
        }
        if written > 0 {
            pendingWrite.removeFirst(written)
        }
    }

    private func readAvailable() {
        guard let input = session?.inputStream else { return }
        var buffer = [UInt8](repeating: 0, count: 512)
        while input.hasBytesAvailable {
            let count = input.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            readBuffer += String(decoding: buffer[0..<count], as: UTF8.self)
        }
        if readBuffer.contains(">") {
            let reply = readBuffer
            readBuffer = ""
            finishCurrent(reply)
        }
    }

    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            readAvailable()
        case .hasSpaceAvailable:
            flushWrites()
        case .errorOccurred, .endEncountered:
            close()
            onLost?()
        default:
            break
        }
    }
}
