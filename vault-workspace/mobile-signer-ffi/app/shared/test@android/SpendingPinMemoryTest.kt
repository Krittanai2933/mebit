package com.mebit.app.data

import androidx.compose.runtime.State
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A reader of the app's memory finds a salted hash of a Spending PIN, never its digits. JVM reflection, so Android only. */
class SpendingPinMemoryTest {
    @Test
    fun theDigitsAreNeverKeptOnlyASaltedHashOfThem() {
        val digits = "270419"
        val first = SpendingPin().apply { assertTrue(set(digits)) }
        val second = SpendingPin().apply { assertTrue(set(digits)) }
        val held = reachable(first)
        for (value in held) {
            assertFalse(value is String && digits in value, "the digits as text in $held")
            assertFalse(value is ByteArray && value.decodeToString().contains(digits), "the digits as bytes")
            assertFalse(value is CharArray && String(value).contains(digits), "the digits as chars")
        }
        val bytesFirst = held.filterIsInstance<ByteArray>()
        val bytesSecond = reachable(second).filterIsInstance<ByteArray>()
        assertTrue(bytesFirst.any { it.isNotEmpty() }, "something to check a PIN against")
        assertFalse(
            bytesFirst.size == bytesSecond.size && bytesFirst.zip(bytesSecond).all { (a, b) -> a.contentEquals(b) },
            "two sessions with the same PIN hold the same bytes, so nothing salts the hash",
        )
    }

    /** Every value [root]'s fields reach, through Compose state and the app's own holder classes. */
    private fun reachable(root: Any?, depth: Int = 0, out: MutableList<Any> = mutableListOf()): List<Any> {
        if (root == null || depth > 4) return out
        out += root
        when (root) {
            is State<*> -> reachable(root.value, depth + 1, out)
            is ByteArray, is CharArray, is String, is Number, is Boolean -> Unit
            else -> if (root.javaClass.name.startsWith("com.mebit")) {
                root.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { field ->
                    field.isAccessible = true
                    reachable(field.get(root), depth + 1, out)
                }
            }
        }
        return out
    }
}
