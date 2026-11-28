import Foundation
import XCTest
@testable import TetherCore

/// A mid-call reconnect (the owner's call of 2026-11-28 13:11): ~30 s of call audio, the link lost for 6.6 s, a new
/// session, then the call's audio again. The iPhone's statistics after the reconnect were "playout delay 222 ms,
/// drained 8826 ms (speech 70 ms), lost 0 of 434 frames, drift 996668 ppm": the Android phone (1.2.6) had moved the
/// call to the new session before the iPhone's sync and sent µ-law at its helper's 16 kHz, announced as 8 kHz.
final class PlayoutReconnectTests: XCTestCase {
    struct Run { var driftPpm = 0.0, drainedMs = 0.0, speechCutMs = 0.0, playedMs = 0.0, meanDelayMs = 0.0, floorMs = 0.0, restarts = 0 }

    /// Pushes `frames` (decoded PCM) arriving at `arrivals` (ms) into `b`, pulling 10 ms every 10 ms, until `until` ms.
    static func play(_ b: PlayoutBuffer, rate: Double, frames: [[Int16]], arrivals: [Double], missing: [Int: Int] = [:],
                     from start: Double, until end: Double) -> Run {
        let pull = Int(rate / 100)
        var out = [Float](repeating: 0, count: pull)
        var next = 0, t = start, real = 0, delaySum = 0.0, pulls = 0
        while t < end {
            while next < arrivals.count && arrivals[next] <= t {
                b.push(frames[next], at: UInt64((arrivals[next] + 1_000_000) * 1e6), missing: missing[next] ?? 0)
                next += 1
            }
            real += out.withUnsafeMutableBufferPointer { b.pull(into: $0.baseAddress!, count: pull) }
            delaySum += Double(b.buffered) / rate * 1000; pulls += 1
            t += 10
        }
        return Run(driftPpm: b.driftPpm, drainedMs: Double(b.trimmed) / rate * 1000, speechCutMs: Double(b.speechTrimmed) / rate * 1000,
                   playedMs: Double(real) / rate * 1000, meanDelayMs: delaySum / Double(max(1, pulls)),
                   floorMs: Double(b.jitterFloor) / rate * 1000, restarts: b.arrivalRestarts)
    }

    /// 16 kHz call audio as 20 ms frames (320 samples): what the helper captures, whatever the codec says.
    static func talk(_ n: Int, seed: UInt64) -> [[Int16]] { PlayoutLongGapTests.talk(frames: n, seed: seed).map(\.pcm) }

    /// The reconnect as the iPhone lived it: the session's audio stops with the link (its statistics are logged), the
    /// new session's audio.start says "pcmu" at 8000 Hz, and 320-byte µ-law frames follow, 20 ms apart.
    func testReconnectWithMuLawAt16kHzPlaysAtTheRateTheFramesCarry() {
        // Before the drop: 30 s of Opus at 16 kHz, a little jitter.
        let before = Self.talk(1500, seed: 21)
        let tr1 = PlayoutLongGapTests.tethering(frames: 1500, seed: 21, drift: -0.000032, stalls: [])
        let first = Self.play(PlayoutBuffer(rate: 16000), rate: 16000, frames: before, arrivals: tr1, from: 0, until: 30_000)
        XCTAssertLessThan(abs(first.driftPpm), 1000, "before the drop the drift estimate is sane")

        // 6.6 s without audio, then the new session: 434 frames (8.7 s), 320 samples each.
        let after = Self.talk(434, seed: 22)
        let tr2 = PlayoutLongGapTests.tethering(frames: 434, seed: 22, drift: -0.000019, stalls: [])
        let announced = 8000, frameBytes = after[0].count   // G.711: one byte per sample
        XCTAssertEqual(frameBytes, 320)

        // What 1.2.6 did: the playout at the announced 8 kHz. These are the owner's numbers: half of the audio drained,
        // the rest played at half speed.
        let wrong = Self.play(PlayoutBuffer(rate: Double(announced)), rate: Double(announced), frames: after, arrivals: tr2, from: 0, until: 9_000)
        print(String(format: "[trace] reconnect at the announced 8 kHz: drift %.0f ppm, drained %.0f ms (speech %.0f), mean delay %.0f ms",
                     wrong.driftPpm, wrong.drainedMs, wrong.speechCutMs, wrong.meanDelayMs))
        XCTAssertGreaterThan(wrong.driftPpm, 900_000)
        XCTAssertGreaterThan(wrong.drainedMs, 0.8 * 434 * 20 * 0.5)

        // The fix: the frames' own rate.
        let rate = DownlinkRate.of(codec: .pcmu, announced: announced, frameBytes: frameBytes)
        XCTAssertEqual(rate, 16000, "a 320-byte µ-law frame of 20 ms carries 16 kHz")
        let right = Self.play(PlayoutBuffer(rate: Double(rate)), rate: Double(rate), frames: after, arrivals: tr2, from: 0, until: 9_000)
        print(String(format: "[trace] reconnect at the frames' 16 kHz: drift %.0f ppm, drained %.0f ms (speech %.0f), played %.1f ms, mean delay %.0f ms",
                     right.driftPpm, right.drainedMs, right.speechCutMs, right.playedMs, right.meanDelayMs))
        XCTAssertLessThan(abs(right.driftPpm), 3000, "no fake drift")
        XCTAssertLessThan(right.drainedMs, 300, "nothing of the call is thrown away")
        XCTAssertEqual(right.speechCutMs, 0)
        XCTAssertGreaterThan(right.playedMs, 434 * 20 * 0.95, "every frame is heard, at its own speed")
        XCTAssertLessThan(right.meanDelayMs, 130)
    }

    /// The same playout across a gap of seconds (the audio path kept, the frames resume after a relink): the arrival
    /// statistics start again instead of measuring every later frame against the clock from before the gap (where a
    /// seq restart or more than 50 lost frames left them all out as a stall's burst, and the target never followed the
    /// new link again).
    func testGapOfSecondsRestartsTheArrivalStatistics() {
        for (name, missingAfterGap) in [("sequence restarted", 0), ("50 frames counted lost", 50)] {
            let b = PlayoutBuffer(rate: 16000)
            let talk = Self.talk(1500 + 1000, seed: 31)
            // 30 s smooth (15 ms connection events, no hold-ups), then 6.6 s nothing, then 20 s with hold-ups of 60-90 ms
            // every ~0.7 s: the buffer must deepen for them.
            var arrivals = (0..<1500).map { k in ceil((Double(k) * 20 + 0.5) / 15) * 15 }
            let resume = 30_000 + 6_600.0
            var rng = SeededRandom(seed: 32)
            var holds: [(Double, Double)] = []
            var h = resume + 1_500
            while h < resume + 21_000 { holds.append((h, Double.random(in: 60...90, using: &rng))); h += Double.random(in: 500...900, using: &rng) }
            arrivals += (0..<1000).map { k in
                var a = resume + ceil((Double(k) * 20 + 0.5) / 15) * 15
                for (s, len) in holds where a >= s && a < s + len { a = s + len }
                return a
            }
            let r = Self.play(b, rate: 16000, frames: talk, arrivals: arrivals, missing: [1500: missingAfterGap], from: 0, until: resume + 20_000)
            print(String(format: "[trace] gap of 6.6 s (%@): floor %.0f ms, drift %.0f ppm, restarts %d, drained %.0f ms",
                         name, r.floorMs, r.driftPpm, r.restarts, r.drainedMs))
            XCTAssertEqual(r.restarts, 1, name)
            XCTAssertLessThan(abs(r.driftPpm), 3000, "\(name): no drift from the gap")
            XCTAssertGreaterThanOrEqual(r.floorMs, 60, "\(name): the arrival spread after the gap is measured (hold-ups of 60-90 ms)")
            XCTAssertEqual(r.speechCutMs, 0, name)
        }
    }

    /// Stalls of Bluetooth tethering (up to ~0.9 s, their frames then in one burst) are no reconnect: no restart.
    func testTetheringStallIsNoRestart() {
        let b = PlayoutBuffer(rate: 16000)
        let tr = PlayoutLongGapTests.tethering(frames: 1500, seed: 7, stalls: [(10_000, 880)])
        let r = Self.play(b, rate: 16000, frames: Self.talk(1500, seed: 7), arrivals: tr, from: 0, until: 32_000)
        XCTAssertEqual(r.restarts, 0)
    }

    /// After a restart the burst a relink delivers first is not measured: the floor is not pinned at its maximum.
    func testBurstAfterAGapIsNotMeasured() {
        let b = PlayoutBuffer(rate: 16000)
        // 10 s, then 5 s nothing, then 40 frames at once (queued on the way), then 15 s normal.
        func paced(_ k: Int) -> Double { ceil((Double(k) * 20 + 0.5) / 15) * 15 }
        var arrivals: [Double] = (0..<500).map(paced)
        arrivals += (0..<40).map { (k: Int) -> Double in 15_000 + Double(k) * 0.2 }
        arrivals += (0..<750).map { (k: Int) -> Double in 15_010 + paced(k) }
        let r = Self.play(b, rate: 16000, frames: Self.talk(arrivals.count, seed: 41), arrivals: arrivals, from: 0, until: 31_000)
        XCTAssertEqual(r.restarts, 1)
        XCTAssertLessThan(r.floorMs, 60, "the relink's burst did not widen the measured spread")
    }

    /// A new audio.start mid-call (the frame numbers restart at 0): the app resets the playout, which drops what was
    /// queued and starts the arrival statistics and the drift estimate again.
    func testResetStartsAFreshPlayoutState() {
        let b = PlayoutBuffer(rate: 16000)
        let tr = PlayoutLongGapTests.tethering(frames: 1500, seed: 61, drift: -0.004, stalls: [])
        _ = Self.play(b, rate: 16000, frames: Self.talk(1500, seed: 61), arrivals: tr, from: 0, until: 30_000)
        XCTAssertLessThan(b.driftPpm, -1000, "a slow far clock was measured")
        b.reset()
        XCTAssertEqual(b.buffered, 0)
        XCTAssertEqual(b.driftPpm, 0)
        XCTAssertEqual(b.arrivalRestarts, 1)
    }

    func testDownlinkRate() {
        XCTAssertEqual(DownlinkRate.of(codec: .pcmu, announced: 8000, frameBytes: 160), 8000)
        XCTAssertEqual(DownlinkRate.of(codec: .pcmu, announced: 8000, frameBytes: 320), 16000)
        XCTAssertEqual(DownlinkRate.of(codec: .pcmu, announced: 16000, frameBytes: 160), 8000)
        XCTAssertEqual(DownlinkRate.of(codec: .pcmu, announced: 8000, frameBytes: 17), 8000, "odd sizes keep the announced rate")
        XCTAssertEqual(DownlinkRate.of(codec: .opus, announced: 16000, frameBytes: 80), 16000, "Opus frames are variable length")
    }

    /// The A54 helper's 20 ms frames at 16 kHz, µ-law encoded as 1.2.6 sent them, decode to 320 samples.
    func testMuLawAt16kHzDecodesTo320Samples() {
        let pcm = Self.talk(1, seed: 51)[0]
        XCTAssertEqual(G711.decode(G711.encode(pcm)).count, 320)
    }
}
