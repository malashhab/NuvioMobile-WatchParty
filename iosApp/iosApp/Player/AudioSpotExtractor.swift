import Foundation
import Libmpv

/// Sync to audio: decodes a short stretch of a video's audio into a 16 kHz mono WAV file, with
/// its own hidden mpv (no picture, no sound out), as fast as the network allows. Kotlin turns
/// the file into a speech timeline and matches it against the subtitle lines.
enum AudioSpotExtractor {
    /// Blocks until done; call it off the main thread. True when the file holds audio.
    static func extract(
        url: String,
        headers: [String: String],
        startSeconds: Double,
        durationSeconds: Double,
        outputPath: String,
        timeoutSeconds: Double = 45
    ) -> Bool {
        try? FileManager.default.removeItem(atPath: outputPath)
        guard let handle = mpv_create() else { return false }
        defer { mpv_terminate_destroy(handle) }

        func option(_ name: String, _ value: String) {
            _ = mpv_set_option_string(handle, name, value)
        }
        option("terminal", "no")
        option("msg-level", "all=no")
        option("config", "no")
        option("ytdl", "no")
        option("vid", "no")
        option("sid", "no")
        option("audio-display", "no")
        // The pcm output writes a WAV file and runs as fast as audio can be decoded.
        option("ao", "pcm")
        option("ao-pcm-file", outputPath)
        option("ao-pcm-waveheader", "yes")
        option("audio-samplerate", "16000")
        option("audio-channels", "mono")
        option("audio-format", "s16")
        option("audio-fallback-to-null", "no")
        option("hr-seek", "yes")
        option("start", String(format: "%.3f", startSeconds))
        option("end", String(format: "%.3f", startSeconds + durationSeconds))
        option("cache", "no")
        option("network-timeout", "15")
        option("idle", "no")
        if !headers.isEmpty {
            let serialized = headers
                .sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
                .map { key, value in
                    let escaped = value
                        .replacingOccurrences(of: "\\", with: "\\\\")
                        .replacingOccurrences(of: ",", with: "\\,")
                    return "\(key): \(escaped)"
                }
                .joined(separator: ",")
            option("http-header-fields", serialized)
        }
        guard mpv_initialize(handle) >= 0 else { return false }

        let rawArgs: [String?] = ["loadfile", url, nil]
        var cargs = rawArgs.map { $0.flatMap { UnsafePointer<CChar>(strdup($0)) } }
        defer { for ptr in cargs where ptr != nil { free(UnsafeMutablePointer(mutating: ptr!)) } }
        guard mpv_command(handle, &cargs) >= 0 else { return false }

        let deadline = Date().addingTimeInterval(timeoutSeconds)
        var finished = false
        while Date() < deadline {
            guard let event = mpv_wait_event(handle, 0.5) else { continue }
            if event.pointee.event_id == MPV_EVENT_END_FILE || event.pointee.event_id == MPV_EVENT_SHUTDOWN {
                finished = true
                break
            }
        }
        if !finished { return false }

        // A WAV header is 44 bytes; anything shorter than ~1 s of audio is not worth analysing.
        let attributes = try? FileManager.default.attributesOfItem(atPath: outputPath)
        let size = (attributes?[.size] as? NSNumber)?.intValue ?? 0
        return size > 44 + 32_000
    }
}
