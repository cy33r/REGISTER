import Foundation
import XCTest
@_spi(TetherApp) @testable import TetherCore

final class CallTonesTests: XCTestCase {
    func testWireValues() {
        XCTAssertEqual(CallEndTone(wire: "busy"), .busy)
        XCTAssertEqual(CallEndTone(wire: "congestion"), .congestion)
        XCTAssertNil(CallEndTone(wire: "ended"), "the call-ended beep is not played")
        XCTAssertNil(CallEndTone(wire: nil), "older Android phone apps send no tone")
        XCTAssertNil(CallEndTone(wire: "reorder"), "unknown values are ignored")
    }

    func testDecodesOptionalTone() throws {
        let json = #"{"cid":"c1","num":"+10000000001","state":"ended","dir":"out","hold":false,"cause":"BUSY","tone":"busy"}"#
        let c = try JSONDecoder().decode(CallInfo.self, from: Data(json.utf8))
        XCTAssertEqual(c.tone, "busy")
        let old = #"{"cid":"c1","num":"+10000000001","state":"ended","dir":"out","hold":false}"#
        XCTAssertNil(try JSONDecoder().decode(CallInfo.self, from: Data(old.utf8)).tone)
    }

    func testWhichEndedCallsPlay() {
        XCTAssertEqual(CallEndTone.play("busy", shownHere: true, ringingIncoming: false), .busy, "an outgoing call here")
        XCTAssertEqual(CallEndTone.play("congestion", shownHere: true, ringingIncoming: false), .congestion, "an active call that failed")
        XCTAssertNil(CallEndTone.play("busy", shownHere: false, ringingIncoming: false), "a call on another client or gone here")
        XCTAssertNil(CallEndTone.play("busy", shownHere: true, ringingIncoming: true), "a ringing call that ended was missed")
        XCTAssertNil(CallEndTone.play("ended", shownHere: true, ringingIncoming: false))
        XCTAssertNil(CallEndTone.play(nil, shownHere: true, ringingIncoming: false))
    }

    /// Busy: 425 Hz 0.5 s on / 0.5 s off; congestion 0.2 / 0.2 (CEPT, as Android's TONE_SUP_BUSY / TONE_SUP_CONGESTION).
    func testCadence() {
        let rate = 16000
        func onShare(_ t: CallEndTone, from: Int, to: Int) -> Double {
            Double((from..<to).filter { t.envelope(sample: $0, rate: rate) > 0.99 }.count) / Double(to - from)
        }
        XCTAssertEqual(CallEndTone.busy.envelope(sample: rate / 4, rate: rate), 1)
        XCTAssertEqual(CallEndTone.busy.envelope(sample: rate * 3 / 4, rate: rate), 0)
        XCTAssertEqual(CallEndTone.busy.envelope(sample: rate + rate / 4, rate: rate), 1, "repeats every second")
        XCTAssertEqual(CallEndTone.congestion.envelope(sample: rate / 10, rate: rate), 1)
        XCTAssertEqual(CallEndTone.congestion.envelope(sample: rate * 3 / 10, rate: rate), 0)
        XCTAssertEqual(onShare(.busy, from: 0, to: rate * 3), 0.5, accuracy: 0.02)
        XCTAssertEqual(onShare(.congestion, from: 0, to: rate * 3), 0.5, accuracy: 0.03)
        XCTAssertEqual(CallEndTone.busy.envelope(sample: 0, rate: rate), 0, "ramps in: no click")
    }

    func testGeneratorLevelAndFrequency() {
        var g = CallEndToneGenerator(tone: .busy, rate: 16000)
        var buf = [Float](repeating: 0, count: 8000)   // the first half second: on
        buf.withUnsafeMutableBufferPointer { g.fill($0.baseAddress!, count: 8000) }
        XCTAssertEqual(g.position, 8000)
        XCTAssertLessThanOrEqual(buf.map { abs($0) }.max()!, Float(CallEndToneGenerator.level) + 1e-6)
        // Zero crossings in 0.4 s of the on part: 2 × 425 × 0.4.
        let part = buf[800..<7200]
        let crossings = zip(part, part.dropFirst()).filter { ($0 < 0) != ($1 < 0) }.count
        XCTAssertEqual(Double(crossings), 2 * 425 * 0.4, accuracy: 4)
        var off = [Float](repeating: 1, count: 8000)    // the next half second: off
        off.withUnsafeMutableBufferPointer { g.fill($0.baseAddress!, count: 8000) }
        XCTAssertEqual(off[200..<7900].map { abs($0) }.max()!, 0)
    }

    func testSpeechCount() {
        XCTAssertFalse(SpeechLevel.isSpeech([Int16](repeating: 0, count: 320)))
        XCTAssertFalse(SpeechLevel.isSpeech((0..<320).map { Int16(200 * sin(Double($0) * 0.2)) }), "comfort noise is no speech")
        XCTAssertTrue(SpeechLevel.isSpeech((0..<320).map { Int16(3000 * sin(Double($0) * 0.2)) }))
        XCTAssertFalse(SpeechLevel.isSpeech([]))
    }

    func testNoAudioNotice() {
        let stopped = HelperInfo(state: "stopped", why: "wifi")
        XCTAssertEqual(CallAudioNotice.forCall(state: "active", helper: stopped), .needsWifi)
        XCTAssertEqual(CallAudioNotice.forCall(state: "dialing", helper: stopped), .needsWifi)
        XCTAssertEqual(CallAudioNotice.forCall(state: "ringing", helper: stopped), .needsWifi, "said before answering too")
        XCTAssertNil(CallAudioNotice.forCall(state: "ended", helper: stopped))
        XCTAssertNil(CallAudioNotice.forCall(state: "active", helper: HelperInfo(state: "running", needsWifi: true)), "running: audio flows")
        XCTAssertNil(CallAudioNotice.forCall(state: "active", helper: HelperInfo(state: "stopped")), "no reason given: no claim")
        XCTAssertNil(CallAudioNotice.forCall(state: "active", helper: nil))
        // Builds after 1.2.6: status.helper.noCallAudio while a call has no audio (the fixture variant "nocallaudio").
        let json = #"{"state":"stopped","up":false,"down":false,"why":"wifi","noCallAudio":"wifi"}"#
        let h = try? JSONDecoder().decode(HelperInfo.self, from: Data(json.utf8))
        XCTAssertEqual(h?.noCallAudio, "wifi")
        XCTAssertEqual(CallAudioNotice.forCall(state: "active", helper: h), .needsWifi)
        XCTAssertEqual(CallAudioNotice.forCall(state: "active", helper: HelperInfo(state: "starting", noCallAudio: "other")), .needsWifi,
                       "any value says the call has no audio")
        let unplug = #"{"state":"running","up":true,"down":true,"needsWifi":false,"stopsOnUnplug":true}"#
        XCTAssertEqual(try? JSONDecoder().decode(HelperInfo.self, from: Data(unplug.utf8)).stopsOnUsbUnplug, true)
    }
}
