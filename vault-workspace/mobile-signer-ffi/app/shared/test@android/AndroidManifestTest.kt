package com.mebit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store's copy counter lives in one process, so only this app's one process may write the wallet file. This pins the two
 * manifest facts that rests on: no element runs in another process, and MainActivity stays singleTask so the launcher cannot
 * stack a second one.
 */
class AndroidManifestTest {
    @Test
    fun theAppRunsInOneProcessAndMainActivityIsSingleTask() {
        val here = File(System.getProperty("user.dir")).absoluteFile
        val manifest = generateSequence(here) { it.parentFile }.map { File(it, "androidApp/src/AndroidManifest.xml") }.firstOrNull { it.isFile }
        assertNotNull(manifest, "androidApp/src/AndroidManifest.xml above $here")
        val text = manifest.readText()
        assertFalse("android:process" in text, "an element sets android:process")
        val main = Regex("<activity[^>]*>").findAll(text).single { ".MainActivity" in it.value }.value
        assertTrue("android:launchMode=\"singleTask\"" in main, "MainActivity lost launchMode singleTask")
    }
}
