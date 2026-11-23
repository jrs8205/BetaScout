package org.jarsi.betascout

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInRequestTest {

    @After
    fun reset() {
        SignInRequests.consume()
    }

    @Test
    fun `no request is pending until one is made`() {
        assertFalse(SignInRequests.pending.value)
    }

    @Test
    fun `a request stays pending until it is consumed`() {
        SignInRequests.request()
        assertTrue(SignInRequests.pending.value)
        // Still there for whoever comes to read it — the activity may be
        // recreated between the tap and the navigation.
        assertTrue(SignInRequests.pending.value)

        SignInRequests.consume()
        assertFalse(SignInRequests.pending.value)
    }

    @Test
    fun `a second tap before the first was handled is one request, not two`() {
        SignInRequests.request()
        SignInRequests.request()

        SignInRequests.consume()

        assertFalse(SignInRequests.pending.value)
    }
}
