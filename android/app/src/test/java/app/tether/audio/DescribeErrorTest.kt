package app.tether.audio

import app.tether.helper.CallAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #37 (Honor 200, Android 16): every self-test row said only "NullPointerException: null". */
class DescribeErrorTest {
    @Test fun anErrorWithoutAMessageSaysWhereItCameFrom() {
        val npe = NullPointerException().apply {
            stackTrace = arrayOf(StackTraceElement("android.media.AudioRecord", "native_setup", "AudioRecord.java", 812),
                StackTraceElement("app.tether.helper.CallAudio", "openCapture", "CallAudio.kt", 300),
                StackTraceElement("app.tether.helper.HelperMain", "main", "HelperMain.kt", 10))
        }
        assertEquals("NullPointerException: null at AudioRecord.native_setup:812 < CallAudio.openCapture:300", CallAudio.describe(npe))
    }

    @Test fun aMessageIsEnoughOtherwise() {
        assertEquals("IllegalStateException: no telephony output device", CallAudio.describe(RuntimeException(IllegalStateException("no telephony output device"))))
        assertTrue(CallAudio.describe(NullPointerException().apply { stackTrace = emptyArray() }).endsWith("null"))
    }
}
