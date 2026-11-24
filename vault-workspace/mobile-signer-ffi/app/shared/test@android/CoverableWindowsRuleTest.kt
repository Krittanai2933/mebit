package com.mebit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A popup, sheet or dialog is a window of its own, drawn above everything in the app's window, so above the no-screen-lock block and
 * the App Lock too. Only the wrappers in CoverableWindows.kt close one while a block covers the app, so no other file may open one.
 */
class CoverableWindowsRuleTest {
    @Test
    fun onlyTheCoverableWrappersOpenAWindow() {
        val here = File(System.getProperty("user.dir")).absoluteFile
        val shared = generateSequence(here) { it.parentFile }.map { File(it, "shared") }.firstOrNull { File(it, "src").isDirectory }
        assertNotNull(shared, "shared/src above $here")
        val call = Regex("""(?<!\w)(Popup|ModalBottomSheet|Dialog|AlertDialog|BasicAlertDialog|DropdownMenu)\s*\(""")
        val offenders = shared.listFiles { f -> f.isDirectory && f.name.startsWith("src") }.orEmpty()
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CoverableWindows.kt" } }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trimStart()
                    if (code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
                    call.find(line)?.let { "${file.relativeTo(shared)}:${i + 1} calls ${it.groupValues[1]}(" }
                }
            }
        assertTrue(
            offenders.isEmpty(),
            "Open windows through CoverablePopup, CoverableSheet or CoverableDialog in ui/components/CoverableWindows.kt, which close them " +
                "while the no-screen-lock block or the App Lock covers the app:\n" + offenders.joinToString("\n"),
        )
    }
}
