package com.mebit.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every window the app opens closes while the no-screen-lock block or the App Lock covers the app. A window is named by its package, so an
 * import, an import alias and a fully qualified call all match, and comments and string text never do. Every module's src folders are read.
 */
class CoverableWindowsRuleTest {
    private class Rule(val what: String, val allowedIn: String?, val fix: String, val patterns: List<Regex>)

    private val composeWindows = "shared/src/ui/components/CoverableWindows.kt"
    private val iosSheets = "shared/src@ios/NativeSheets.ios.kt"
    private val androidSheets = "shared/src@android/NativeSheets.android.kt"

    private val rules = listOf(
        Rule(
            "opens a Compose window",
            composeWindows,
            "Open it through CoverablePopup, CoverableSheet or CoverableDialog in $composeWindows, or add a wrapper there.",
            listOf(
                // Deny by default: everything in the package but the settings types a wrapper takes and the iOS entry point.
                named("androidx.compose.ui.window", """(?!(?:PopupProperties|PopupPositionProvider|DialogProperties|SecureFlagPolicy|ComposeUIViewController)(?!\w))\w+"""),
                // Material3 1.5.0-alpha22 functions whose own file calls Popup, Dialog or a tooltip box (javap of the resolved material3-android jar).
                named(
                    "androidx.compose.material3",
                    "AlertDialog|BasicAlertDialog|DatePickerDialog|TimePickerDialog|ModalBottomSheet|ModalWideNavigationRail|DropdownMenu|" +
                        "DropdownMenuPopup|ExposedDropdownMenuBox|TooltipBox|Label|SearchBar|DockedSearchBar|TopSearchBar|AppBarWithSearch|" +
                        "ExpandedFullScreenSearchBar|ExpandedFullScreenContainedSearchBar|ExpandedDockedSearchBar|ExpandedDockedSearchBarWithGap|" +
                        "AppBarRow|AppBarColumn|ButtonGroup|DatePicker|Snackbar|SnackbarHost",
                ),
                named("androidx.compose.foundation", "BasicTooltipBox"),
                named("androidx.compose.foundation.contextmenu", """\w+"""),
                // A star import would let any of the names above in unseen.
                named("androidx.compose.ui.window", """\*"""),
                named("androidx.compose.material3", """\*"""),
                named("androidx.compose.foundation", """\*"""),
            ),
        ),
        Rule(
            "presents a UIKit controller",
            iosSheets,
            "Present it through NativeSheets.present in $iosSheets, which dismisses it as a cancel under a block.",
            listOf(
                token("presentViewController|presentModalViewController|showViewController|showDetailViewController"),
                named("platform.UIKit", """UIAlertController|UIWindow|\*"""),
            ),
        ),
        Rule(
            "opens Android's share sheet",
            androidSheets,
            "Open it through startChooser in $androidSheets, which ends it under a block.",
            listOf(token("createChooser|ACTION_CHOOSER|ShareCompat")),
        ),
        Rule(
            "opens an Android platform window",
            null,
            "Draw it in Compose and open it through a wrapper in $composeWindows.",
            listOf(
                named("android.app", """Dialog|AlertDialog|DatePickerDialog|TimePickerDialog|ProgressDialog|Presentation|DialogFragment|\*"""),
                named("android.widget", """PopupWindow|ListPopupWindow|PopupMenu|Toast|\*"""),
                named("androidx.appcompat.app", """AlertDialog|AppCompatDialog|\*"""),
                named("androidx.activity", """ComponentDialog|\*"""),
                named("androidx.fragment.app", """DialogFragment|\*"""),
                named("com.google.android.material.bottomsheet", """\w+|\*"""),
                named("com.google.android.material.dialog", """\w+|\*"""),
            ),
        ),
    )

    @Test
    fun onlyTheCoverableHelpersOpenAWindow() {
        val app = appRoot()
        val sources = app.listFiles { f -> File(f, "module.yaml").isFile }.orEmpty()
            .flatMap { module -> module.listFiles { f -> f.isDirectory && f.name.startsWith("src") }.orEmpty().toList() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val offenders = sources.flatMap { file ->
            val path = file.relativeTo(app).invariantSeparatorsPath
            val code = codeOnly(file.readText())
            rules.filter { it.allowedIn != path }.flatMap { rule ->
                rule.patterns.flatMap { pattern -> pattern.findAll(code).toList() }.map { match ->
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    "$path:$line ${rule.what} (${match.value.replace(Regex("""\s+"""), "")}). ${rule.fix}"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "A block that covers the app must close every window above it, so windows open only through the coverable helpers:\n" +
                offenders.joinToString("\n"),
        )
        assertTrue(sources.any { it.relativeTo(app).invariantSeparatorsPath == composeWindows }, "$composeWindows under $app")
    }

    @Test
    fun codeOnlyBlanksCommentsAndStringsButKeepsCodeAndLines() {
        val source = "a /* x\n */ b // c\n\"d \${e} \\\" f\" '\"' \"\"\"g\n\"\"\" h /* /* nested */ i */ j"
        val code = codeOnly(source)
        assertEquals(source.length, code.length)
        assertEquals(source.count { it == '\n' }, code.count { it == '\n' })
        assertEquals(listOf("a", "b", "e", "h", "j"), Regex("""\w+""").findAll(code).map { it.value }.toList())
    }

    private fun appRoot(): File {
        val here = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        val app = generateSequence(here) { it.parentFile }.firstOrNull { File(it, "shared/src").isDirectory }
        return assertNotNull(app, "an app folder holding shared/src above $here")
    }

    private companion object {
        private const val DOT = """\s*\.\s*"""

        /** [pkg].<one of [names]>, however it is spaced or backticked. */
        fun named(pkg: String, names: String): Regex {
            val path = pkg.split('.').joinToString(DOT) { "`?$it`?" }
            return Regex("""(?<![\w.`])$path$DOT`?(?:$names)`?(?!\w)""")
        }

        fun token(names: String): Regex = Regex("""(?<![\w`])`?(?:$names)`?(?!\w)""")

        /**
         * [source] with comments, nested ones too, and the text of string and char literals turned to spaces. Newlines stay, so an offset
         * keeps its line, and a template's `${...}` stays code.
         */
        fun codeOnly(source: String): String {
            val out = StringBuilder(source.length)
            // Innermost first: an open string's quote, or the brace depth of a template expression inside one.
            val open = ArrayDeque<Any>()
            var i = 0
            fun blank(n: Int) {
                for (k in i until minOf(i + n, source.length)) out.append(if (source[k] == '\n') '\n' else ' ')
                i = minOf(i + n, source.length)
            }
            fun keep() {
                out.append(source[i])
                i++
            }
            fun at(s: String) = source.startsWith(s, i)
            while (i < source.length) {
                val top = open.lastOrNull()
                if (top is String) {
                    when {
                        top == "\"" && at("\\") -> blank(2)
                        top == "\"\"\"" && at("\"\"\"\"") -> blank(1)
                        at(top) -> {
                            blank(top.length)
                            open.removeLast()
                        }
                        at("\${") -> {
                            keep()
                            keep()
                            open.addLast(1)
                        }
                        else -> blank(1)
                    }
                    continue
                }
                when {
                    at("//") -> blank((source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length) - i)
                    at("/*") -> {
                        var depth = 0
                        var j = i
                        do {
                            when {
                                source.startsWith("/*", j) -> { depth++; j += 2 }
                                source.startsWith("*/", j) -> { depth--; j += 2 }
                                else -> j++
                            }
                        } while (depth > 0 && j < source.length)
                        blank(j - i)
                    }
                    at("\"\"\"") -> {
                        blank(3)
                        open.addLast("\"\"\"")
                    }
                    at("\"") -> {
                        blank(1)
                        open.addLast("\"")
                    }
                    at("'") -> {
                        var j = i + 1
                        if (source.getOrNull(j) == '\\') j += 2
                        while (j < source.length && source[j] != '\'') j++
                        blank(j + 1 - i)
                    }
                    top is Int && at("{") -> {
                        open[open.lastIndex] = top + 1
                        keep()
                    }
                    top is Int && at("}") -> {
                        if (top == 1) open.removeLast() else open[open.lastIndex] = top - 1
                        keep()
                    }
                    else -> keep()
                }
            }
            return out.toString()
        }
    }
}
