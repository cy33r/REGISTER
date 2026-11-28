package app.tether.client

import app.tether.client.calls.DownlinkRate
import app.tether.client.calls.PlayoutBuffer
import app.tether.core.AudioFrame
import app.tether.core.G711
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * TetherCore's PlayoutReconnectTests, ported (same traces, same printed numbers): a mid-call reconnect, the owner's call
 * of 2026-11-28 ("drained 8826 ms (speech 70 ms), lost 0 of 434 frames, drift 996668 ppm" after it).
 */
class PlayoutReconnectTest {
    class Run(val driftPpm: Double, val drainedMs: Double, val speechCutMs: Double, val playedMs: Double, val meanDelayMs: Double,
              val floorMs: Double, val restarts: Int)

    private fun play(b: PlayoutBuffer, rate: Int, frames: List<ShortArray>, arrivals: DoubleArray, missing: Map<Int, Int> = emptyMap(),
                     from: Double, until: Double): Run {
        val pull = rate / 100
        val out = FloatArray(pull)
        var next = 0; var t = from; var real = 0; var delaySum = 0.0; var pulls = 0
        while (t < until) {
            while (next < arrivals.size && arrivals[next] <= t) {
                b.push(frames[next], at = ((arrivals[next] + 1_000_000) * 1e6).toLong(), missing = missing[next] ?: 0)
                next++
            }
            real += b.pull(out, pull)
            delaySum += b.buffered.toDouble() / rate * 1000; pulls++
            t += 10
        }
        return Run(b.driftPpm, b.trimmed.toDouble() / rate * 1000, b.speechTrimmed.toDouble() / rate * 1000, real.toDouble() / rate * 1000,
            delaySum / maxOf(1, pulls), b.jitterFloor.toDouble() / rate * 1000, b.arrivalRestarts)
    }

    private fun talk(n: Int, seed: Long) = PlayoutLongGapTest.talk(n, seed).map { it.pcm }

    @Test fun reconnectWithMuLawAt16kHzPlaysAtTheRateTheFramesCarry() {
        val first = play(PlayoutBuffer(16000), 16000, talk(1500, 21), PlayoutLongGapTest.tethering(1500, 21, drift = -0.000032, stalls = emptyList()),
            from = 0.0, until = 30_000.0)
        assertTrue("before the drop the drift estimate is sane", abs(first.driftPpm) < 1000)

        val after = talk(434, 22)
        val tr2 = PlayoutLongGapTest.tethering(434, 22, drift = -0.000019, stalls = emptyList())
        val announced = 8000; val frameBytes = after[0].size
        assertEquals(320, frameBytes)

        val wrong = play(PlayoutBuffer(announced), announced, after, tr2, from = 0.0, until = 9_000.0)
        val wrongLine = String.format(Locale.ROOT, "reconnect at the announced 8 kHz: drift %.0f ppm, drained %.0f ms (speech %.0f), mean delay %.0f ms",
            wrong.driftPpm, wrong.drainedMs, wrong.speechCutMs, wrong.meanDelayMs)
        println("[trace] $wrongLine")
        assertTrue(wrong.driftPpm > 900_000)
        // What the Swift run prints.
        assertEquals("reconnect at the announced 8 kHz: drift 1000151 ppm, drained 8590 ms (speech 1680), mean delay 466 ms", wrongLine)

        val rate = DownlinkRate.of(AudioFrame.PCMU, announced, frameBytes)
        assertEquals("a 320-byte µ-law frame of 20 ms carries 16 kHz", 16000, rate)
        val right = play(PlayoutBuffer(rate), rate, after, tr2, from = 0.0, until = 9_000.0)
        val rightLine = String.format(Locale.ROOT, "reconnect at the frames' 16 kHz: drift %.0f ppm, drained %.0f ms (speech %.0f), played %.1f ms, mean delay %.0f ms",
            right.driftPpm, right.drainedMs, right.speechCutMs, right.playedMs, right.meanDelayMs)
        println("[trace] $rightLine")
        assertTrue("no fake drift", abs(right.driftPpm) < 3000)
        assertTrue("nothing of the call is thrown away", right.drainedMs < 300)
        assertEquals(0.0, right.speechCutMs, 0.0)
        assertTrue("every frame is heard", right.playedMs > 434 * 20 * 0.95)
        assertEquals("reconnect at the frames' 16 kHz: drift -0 ppm, drained 88 ms (speech 0), played 8592.5 ms, mean delay 51 ms", rightLine)
    }

    @Test fun gapOfSecondsRestartsTheArrivalStatistics() {
        for ((name, missingAfterGap) in listOf("sequence restarted" to 0, "50 frames counted lost" to 50)) {
            val b = PlayoutBuffer(16000)
            val talk = talk(2500, 31)
            val resume = 30_000 + 6_600.0
            val rng = PlayoutLongGapTest.SeededRandom(32)
            val holds = ArrayList<Pair<Double, Double>>()
            var h = resume + 1_500
            while (h < resume + 21_000) { holds.add(h to rng.double(60.0, 90.0)); h += rng.double(500.0, 900.0) }
            val arrivals = DoubleArray(2500) { i ->
                if (i < 1500) ceil((i * 20.0 + 0.5) / 15) * 15
                else {
                    var a = resume + ceil(((i - 1500) * 20.0 + 0.5) / 15) * 15
                    for ((s, len) in holds) if (a >= s && a < s + len) a = s + len
                    a
                }
            }
            val r = play(b, 16000, talk, arrivals, mapOf(1500 to missingAfterGap), from = 0.0, until = resume + 20_000)
            val line = String.format(Locale.ROOT, "gap of 6.6 s (%s): floor %.0f ms, drift %.0f ppm, restarts %d, drained %.0f ms",
                name, r.floorMs, r.driftPpm, r.restarts, r.drainedMs)
            println("[trace] $line")
            assertEquals(name, 1, r.restarts)
            assertTrue("$name: no drift from the gap", abs(r.driftPpm) < 3000)
            assertTrue("$name: the arrival spread after the gap is measured", r.floorMs >= 60)
            assertEquals(name, 0.0, r.speechCutMs, 0.0)
            assertEquals("gap of 6.6 s ($name): floor 95 ms, drift -0 ppm, restarts 1, drained 85 ms", line)
        }
    }

    @Test fun tetheringStallIsNoRestart() {
        val r = play(PlayoutBuffer(16000), 16000, talk(1500, 7), PlayoutLongGapTest.tethering(1500, 7, stalls = listOf(10_000.0 to 880.0)),
            from = 0.0, until = 32_000.0)
        assertEquals(0, r.restarts)
    }

    @Test fun burstAfterAGapIsNotMeasured() {
        fun paced(k: Int) = ceil((k * 20.0 + 0.5) / 15) * 15
        val arrivals = DoubleArray(500) { paced(it) } + DoubleArray(40) { 15_000 + it * 0.2 } + DoubleArray(750) { 15_010 + paced(it) }
        val r = play(PlayoutBuffer(16000), 16000, talk(arrivals.size, 41), arrivals, from = 0.0, until = 31_000.0)
        assertEquals(1, r.restarts)
        assertTrue("the relink's burst did not widen the measured spread", r.floorMs < 60)
    }

    /** A new audio.start mid-call: the playout's reset drops the queue and starts the statistics and drift again. */
    @Test fun resetStartsAFreshPlayoutState() {
        val b = PlayoutBuffer(16000)
        play(b, 16000, talk(1500, 61), PlayoutLongGapTest.tethering(1500, 61, drift = -0.004, stalls = emptyList()), from = 0.0, until = 30_000.0)
        assertTrue("a slow far clock was measured", b.driftPpm < -1000)
        b.reset()
        assertEquals(0, b.buffered)
        assertEquals(0.0, b.driftPpm, 0.0)
        assertEquals(1, b.arrivalRestarts)
    }

    @Test fun downlinkRate() {
        assertEquals(8000, DownlinkRate.of(AudioFrame.PCMU, 8000, 160))
        assertEquals(16000, DownlinkRate.of(AudioFrame.PCMU, 8000, 320))
        assertEquals(8000, DownlinkRate.of(AudioFrame.PCMU, 16000, 160))
        assertEquals(8000, DownlinkRate.of(AudioFrame.PCMU, 8000, 17))
        assertEquals(16000, DownlinkRate.of(AudioFrame.OPUS, 16000, 80))
        assertEquals(320, G711.decode(G711.encode(talk(1, 51)[0])).size)
    }
}
