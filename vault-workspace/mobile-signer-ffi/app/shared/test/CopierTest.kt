package com.mebit.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A copy button's คัดลอกแล้ว (issue 52): never before the clipboard took the text, and back to คัดลอก 1.5 s after. */
class CopierTest {
    @Test
    fun aButtonSaysCopiedOnlyOnceTheClipboardTookItsTextAndOnlyForOneAndAHalfSeconds() = runBlocking {
        val taken = CompletableDeferred<Unit>()
        val copier = Copier(this) { taken.await() }

        copier.copy(ADDRESS)
        yield()
        assertNull(copier.copied, "said copied while the clipboard had not taken the text")

        taken.complete(Unit)
        yield()
        assertEquals(ADDRESS, copier.copied)

        delay(1_600)
        assertNull(copier.copied, "still said copied 1.6 s after the copy")
    }

    private companion object {
        const val ADDRESS = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
    }
}
