package com.mebit.app.data

/** How often the export page moves to the next UR part. */
internal const val PART_INTERVAL_MS = 250L

/** Pure. Penpot 3.8's "ส่วน x / N" for the part at [index] (0-based) of [count]. Null for one part, which shows no counter. */
internal fun partCounterText(index: Int, count: Int): String? = if (count <= 1) null else "ส่วน ${index + 1} / $count"

/** Pure. One bar per part, true for the part on screen: the orange one, the rest grey. */
internal fun partBars(index: Int, count: Int): List<Boolean> = List(count) { it == index }

/** Pure. The part on screen [elapsedMs] after the page opened, cycling the [count] parts every [PART_INTERVAL_MS]. */
internal fun partIndexAt(elapsedMs: Long, count: Int): Int = if (count <= 1) 0 else ((elapsedMs / PART_INTERVAL_MS) % count).toInt()

/** Pure. Penpot 3.9.2's progress line while UR parts arrive. */
internal fun scanCounterText(received: Int, expected: Int): String = "ได้แล้ว $received / $expected ส่วน · ถือค้างไว้"

/** What the shared picture holds: one plain QR of [qrText], then the wallet's [name] and the [warning] line under it. */
internal data class ExportPicture(val qrText: String, val name: String, val warning: String)

internal const val EXPORT_PICTURE_WARNING = "QR นี้ไม่มี seed แต่มี xpub ของทุกกุญแจ · ใครสแกนได้จะเห็นยอดเงินและประวัติ"

/** Pure. The picture for the tab showing [tabText] of wallet [name]: the whole text as plain text, never as UR. Null when there is no text. */
internal fun exportPicture(name: String, tabText: String?): ExportPicture? =
    tabText?.let { ExportPicture(it, name, EXPORT_PICTURE_WARNING) }
