import Foundation

/// The ELM327 text commands and replies, kept identical to the Android app's
/// ELM327Adapter so both versions talk to the 7.3L the same way.
enum ELM327 {
    /// Reset, echo off, linefeeds off, spaces off, headers off, protocol auto.
    /// The 7.3L is J1850 PWM; "auto" finds it on the first request.
    static let initCommands = ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"]

    /// Engine RPM (Mode 01, PID 0C).
    static let rpmCommand = "010C"

    /// The data bytes after `header` (e.g. "410C"), or nil when the reply has none.
    /// Skips "SEARCHING...", "NO DATA", "BUS INIT: OK" and similar lines.
    static func parseReply(_ response: String, header: String) -> [UInt8]? {
        let lines = response.split(whereSeparator: { $0 == "\r" || $0 == "\n" })
        for rawLine in lines {
            let line = String(rawLine)
                .replacingOccurrences(of: ">", with: "")
                .filter { !$0.isWhitespace }
                .uppercased()
            guard line.hasPrefix(header) else { continue }
            let payload = Array(line.dropFirst(header.count))
            guard payload.count >= 2, payload.count % 2 == 0 else { continue }
            var bytes: [UInt8] = []
            var valid = true
            var i = 0
            while i < payload.count {
                guard let byte = UInt8(String(payload[i..<(i + 2)]), radix: 16) else {
                    valid = false
                    break
                }
                bytes.append(byte)
                i += 2
            }
            if valid { return bytes }
        }
        return nil
    }

    /// RPM from a "010C" reply, or nil when the truck didn't answer (NO DATA, timeout).
    static func rpm(from response: String) -> Double? {
        guard let bytes = parseReply(response, header: "410C"), bytes.count >= 2 else { return nil }
        return Double(Int(bytes[0]) * 256 + Int(bytes[1])) / 4.0
    }
}
