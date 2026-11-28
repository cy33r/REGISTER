package app.tether.ui

import android.app.Activity
import app.tether.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialerRoleResultTest {
    @Test fun rejectionOrCancellationKeepsSetupIncomplete() {
        assertEquals(R.string.dialer_role_not_granted, dialerRoleMessage(false, Activity.RESULT_CANCELED))
    }

    @Test fun successResultAloneDoesNotConfirmTheRole() {
        assertEquals(R.string.dialer_role_unconfirmed, dialerRoleMessage(false, Activity.RESULT_OK))
    }

    @Test fun actualRoleHolderClearsTheRequestMessage() {
        assertNull(dialerRoleMessage(true, Activity.RESULT_OK))
        assertNull(dialerRoleMessage(true, Activity.RESULT_CANCELED))
    }
}
