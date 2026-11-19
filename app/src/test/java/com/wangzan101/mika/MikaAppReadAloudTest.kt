package com.wangzan101.mika

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MikaAppReadAloudTest {
    @Test fun applicationEntryPropagatesSynthesisFailureAndAllowsRetry() = runTest {
        val app = MikaApp()
        val failure = IOException("voice service offline")
        var attempts = 0
        val synthesize: suspend (String) -> Unit = {
            if (++attempts == 1) throw failure
        }

        try {
            app.speak("first", synthesize)
            fail("The chat error handler must receive synthesis failures")
        } catch (e: IOException) {
            assertSame(failure, e)
        }
        app.speak("retry", synthesize)
        assertEquals(2, attempts)
    }

    @Test fun applicationEntryPreservesCancellation() = runTest {
        val app = MikaApp()
        val cancellation = CancellationException("stopped")
        try {
            app.speak("answer") { throw cancellation }
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }
}
