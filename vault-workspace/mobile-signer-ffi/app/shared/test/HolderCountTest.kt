package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HolderCountTest {
    private val count = HolderCount()

    @Test
    fun onlyTheFirstAcquireTurnsTheProtectionOn() {
        assertTrue(count.acquire())
        assertFalse(count.acquire())
    }

    @Test
    fun onlyTheLastReleaseTurnsItOff() {
        count.acquire()
        count.acquire()
        assertFalse(count.release())
        assertTrue(count.release())
    }

    @Test
    fun aReleaseWithNothingHeldIsIgnored() {
        assertFalse(count.release())
        assertTrue(count.acquire())
        assertTrue(count.release())
        assertFalse(count.release())
        assertTrue(count.acquire())
    }
}
