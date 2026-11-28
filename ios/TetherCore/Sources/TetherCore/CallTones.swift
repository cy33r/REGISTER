import Foundation

/// The tone a client plays when the Android phone reports a call ended because the far end was busy or the network
/// failed it (`call.state` `tone`, protocol §5). The phone's own dialer plays these locally; a client hears nothing of
/// them over the call audio, so without its own tone a busy call just disappeared.
///
/// Both tones use the 425 Hz of the client's ringback (ITU-T E.180, the tone plan of Iran and most of Europe) with the
/// CEPT cadences Android's ToneGenerator also uses: busy 0.5 s on / 0.5 s off, congestion 0.2 s on / 0.2 s off. A
/// client plays the tone for ``seconds`` through the call's audio path, shows "Busy" or "Call failed", then ends its
/// local call; the user ending the call, or a new call, stops it at once.
public enum CallEndTone: String, Sendable, Equatable, CaseIterable {
    case busy
    case congestion

    /// "busy" and "congestion"; nil for "ended" (the short call-ended beep is not played), unknown values and no tone.
    public init?(wire: String?) {
        switch wire {
        case "busy": self = .busy
        case "congestion": self = .congestion
        default: return nil
        }
    }

    /// How long the tone plays before the local call ends.
    public static let seconds = 3.0
    /// The tone's frequency (Hz), the same as the ringback.
    public static let frequency = 425.0
    /// Edge ramps (s): no clicks at the cadence's edges.
    static let ramp = 0.005

    /// Milliseconds on, then off, repeated.
    public var cadenceMs: (on: Int, off: Int) {
        switch self {
        case .busy: (500, 500)
        case .congestion: (200, 200)
        }
    }

    /// The cadence's level (0…1) at sample `i` of the tone (5 ms ramps at each edge).
    public func envelope(sample i: Int, rate: Int) -> Double {
        let on = rate * cadenceMs.on / 1000, period = on + rate * cadenceMs.off / 1000
        let pos = i % period, edge = max(1, Int(Double(rate) * Self.ramp))
        return pos >= on ? 0 : Double(min(pos, on - pos, edge)) / Double(edge)
    }

    /// Whether an ended call plays its tone here. Only a call that is still shown here (its audio was on this client,
    /// not elsewhere) and that was not an incoming call still ringing (that one was simply missed).
    public static func play(_ tone: String?, shownHere: Bool, ringingIncoming: Bool) -> CallEndTone? {
        guard shownHere, !ringingIncoming else { return nil }
        return CallEndTone(wire: tone)
    }
}

/// Fills audio buffers with a ``CallEndTone`` (the render thread: no allocation). Starts at the beginning of the cadence.
public struct CallEndToneGenerator: Sendable {
    public let tone: CallEndTone
    public let rate: Int
    private var clock = 0
    private var phase = 0.0
    /// Peak level, the same as the ringback's.
    public static let level = 0.12

    public init(tone: CallEndTone, rate: Int) { self.tone = tone; self.rate = rate }

    /// Samples generated so far.
    public var position: Int { clock }

    public mutating func fill(_ out: UnsafeMutablePointer<Float>, count: Int) {
        let step = 2 * Double.pi * CallEndTone.frequency / Double(rate)
        for i in 0..<count {
            out[i] = Float(Self.level * tone.envelope(sample: clock, rate: rate) * sin(phase))
            phase += step
            if phase > 2 * .pi { phase -= 2 * .pi }
            clock += 1
        }
    }
}

/// The sample rate a downlink audio frame really carries.
///
/// Every frame is 20 ms (protocol §4), and G.711 has one byte per sample, so a µ-law frame's length says its rate.
/// The Android phone app 1.2.6 sent µ-law at its helper's 16 kHz while announcing 8 kHz when it moved a running call to
/// a client that had just reconnected (before that client's `sync` said it decodes Opus). Played at 8 kHz, the call ran
/// at half speed and the playout buffer drained the other half (the owner's call of 2026-11-28: drift 996668 ppm,
/// drained 8826 ms of 434 frames). A client plays such frames at the rate they carry, and sends its microphone at that
/// rate too (the phone's helper takes it at that rate).
public enum DownlinkRate {
    /// The rate to play frames of `codec` at: the announced one, except for µ-law frames whose length says otherwise
    /// (8 or 16 kHz).
    public static func of(codec: AudioFrame.Codec, announced: Int, frameBytes: Int) -> Int {
        guard codec == .pcmu else { return announced }
        let carried = frameBytes * 50
        return carried == 8000 || carried == 16000 ? carried : announced
    }
}

/// Counts the far end's speech in played frames, for the call statistics: a "no sound" report then shows whether speech
/// arrived at all (2026-11-28: 577 frames played, nothing said whether they held a voice). The threshold is the helper's
/// own (UplinkCheck.SPEECH_RMS).
@_spi(TetherApp) public enum SpeechLevel {
    /// RMS (16-bit samples) from which a frame counts as speech.
    public static let rms = 300.0

    public static func isSpeech(_ pcm: [Int16]) -> Bool {
        guard !pcm.isEmpty else { return false }
        let e = pcm.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(pcm.count)
        return e.squareRoot() >= rms
    }
}

/// What the call screen says about the call's audio, from the Android phone's `status.helper`.
@_spi(TetherApp) public enum CallAudioNotice: Equatable, Sendable {
    /// The helper is stopped and cannot start: the Android phone is not on Wi-Fi (a USB unplug restarted its adb on
    /// Samsung and took the helper with it, 2026-11-28). The call has no audio until Wi-Fi is on there for a moment.
    case needsWifi

    /// The notice for a call in `callState` (none for an ended call): `helper.noCallAudio` (any value; builds after
    /// 1.2.6 send it while a call has no audio for this reason), else a stopped helper whose `why` is "wifi" (1.2.6).
    public static func forCall(state callState: String, helper: HelperInfo?) -> CallAudioNotice? {
        guard callState != "ended", let h = helper else { return nil }
        if let n = h.noCallAudio, !n.isEmpty { return .needsWifi }
        return h.stoppedWithoutWifi ? .needsWifi : nil
    }
}
