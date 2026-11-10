package com.mebit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.rememberIsDebugBuild
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbMuted
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import com.mebit.app.ui.theme.MbIconWash

/** Penpot Movie Page light scaffold (Add Key / Key Source / Vault Created). */
@Composable
fun MebitScreen(
    eyebrow: String?,
    title: String?,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    tag: String,
    subtitle: String? = null,
    dark: Boolean = false,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    headerContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = if (dark) MbNavy else MbPage
    val titleColor = if (dark) MbWhite else MbNavyInk
    val eyebrowColor = if (dark) MbGold else MbEyebrow
    val bodyColor = if (dark) MbWhite.copy(alpha = 0.72f) else MbBody
    // Background edge-bleeds under system bars; content clears status / Dynamic Island / home indicator.
    Box(modifier = Modifier.fillMaxSize().background(bg).testTag(tag)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The row keeps the back chip's height with or without the chip, so the title sits where the boards put it.
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) {
                if (onBack != null) {
                    BackChip(onClick = onBack, dark = dark)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (headerContent != null) {
                    headerContent()
                } else if (eyebrow != null) {
                    Text(
                        text = eyebrow.uppercase(),
                        color = eyebrowColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.testTag("eyebrow"),
                    )
                }
            }
            Spacer(modifier = Modifier.height(if (title == null) 13.dp else 16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 34.sp,
                        modifier = Modifier.testTag("title"),
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = subtitle,
                            color = bodyColor,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Light,
                            lineHeight = 20.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
                content()
            }
            bottom?.invoke(this)
        }
        DebugLink(onClick = onOpenCatalog, dark = dark)
    }
}

@Composable
fun BackChip(onClick: () -> Unit, dark: Boolean = false, tag: String = "back") {
    Box(
        modifier = Modifier
            .size(36.dp)
            .shadow(2.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(if (dark) MbWhite.copy(alpha = 0.12f) else MbWhite)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "‹",
            color = if (dark) MbWhite else MbNavy,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Penpot's mark for a key on this phone: one phone outline, for [OptionCard]'s leading slot. */
@Composable
fun PhoneMark() = Box(Modifier.size(44.dp)) {
    Box(Modifier.offset(15.dp, 10.dp).size(15.dp, 23.dp).border(2.dp, MbNavy, RoundedCornerShape(4.dp)))
}

/** Penpot 2.1.1's mark for mebit on another phone: two phone outlines and an orange link between them. */
@Composable
fun TwoPhonesMark() = Box(Modifier.size(44.dp)) {
    Box(Modifier.offset(12.dp, 14.dp).size(8.dp, 15.dp).border(2.dp, MbNavy, RoundedCornerShape(2.dp)))
    Box(Modifier.offset(24.dp, 14.dp).size(8.dp, 15.dp).border(2.dp, MbNavy, RoundedCornerShape(2.dp)))
    Box(Modifier.offset(21.dp, 19.5.dp).size(2.dp, 4.dp).background(MbBtcOrange))
}

/** Penpot's mark for a hardware wallet: a device outline with an orange light. */
@Composable
fun HardwareMark() = Box(Modifier.size(44.dp)) {
    Box(Modifier.offset(11.dp, 16.dp).size(22.dp, 14.dp).border(2.dp, MbNavy, RoundedCornerShape(3.dp)))
    Box(Modifier.offset(27.dp, 21.dp).size(5.dp).background(MbBtcOrange, CircleShape))
}

@Composable
fun OptionCard(
    title: String,
    body: String,
    tag: String,
    onClick: () -> Unit,
    chips: List<String> = emptyList(),
    chipsTrailing: String? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp), clip = false)
            .clip(RoundedCornerShape(20.dp))
            .background(MbWhite)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MbIconWash),
            contentAlignment = Alignment.Center,
        ) {
            if (leading != null) leading()
            else Text("·", color = MbNavy, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MbNavyInk,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                color = MbBody,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 16.sp,
            )
            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { HwChip(it) }
                    if (chipsTrailing != null) {
                        Text(text = chipsTrailing, color = MbSlate, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HwChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(MbChip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = MbMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun PrimaryCta(
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    darkSurface: Boolean = false,
    height: Dp = 54.dp,
) {
    val bg = when {
        !enabled -> MbChip
        darkSurface -> MbWhite
        else -> MbNavy
    }
    val fg = when {
        !enabled -> MbBody
        darkSurface -> MbNavy
        else -> MbWhite
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryCta(
    label: String,
    tag: String,
    onClick: () -> Unit,
    darkSurface: Boolean = false,
    height: Dp = 54.dp,
) {
    val border = if (darkSurface) MbWhite.copy(alpha = 0.35f) else MbNavy.copy(alpha = 0.2f)
    val fg = if (darkSurface) MbWhite else MbNavy
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .border(1.5.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoCard(
    eyebrow: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(22.dp), clip = false)
            .clip(RoundedCornerShape(22.dp))
            .background(MbWhite)
            .padding(16.dp),
    ) {
        Text(eyebrow.uppercase(), color = MbBody, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, color = MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(body, color = MbBody, fontSize = 11.5.sp, fontWeight = FontWeight.Light, lineHeight = 16.sp)
    }
}

/** Penpot 4.5.1: the orange banner for a refused or failed send. Its height is a minimum so a wrapped message grows the box instead of clipping. */
@Composable
fun IssueBanner(title: String, message: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MbIconWash)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Text(title, color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(message, color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp, modifier = Modifier.testTag(tag))
    }
}

/** The pair pinned at the foot of a screen: the primary pill and a text link under it. A null [onLink] leaves the link as plain text. */
@Composable
fun BottomActions(
    primaryLabel: String,
    primaryTag: String,
    onPrimary: () -> Unit,
    linkLabel: String,
    linkTag: String,
    onLink: (() -> Unit)?,
    enabled: Boolean = true,
    linkColor: Color = MbNavy,
) {
    Spacer(modifier = Modifier.height(12.dp))
    PrimaryCta(primaryLabel, primaryTag, onPrimary, enabled)
    Spacer(modifier = Modifier.height(9.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.dp)
            .then(if (onLink != null) Modifier.clickable(role = Role.Button, onClick = onLink) else Modifier)
            .testTag(linkTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(linkLabel, color = linkColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * For a layer over the whole screen until the user answers it, as the PIN sheet and the 60-second lock are. It takes every tap meant
 * for the screen behind, and screen readers meet it as a dialog, which keeps the screen behind out of their reach without making the
 * layer one button, so its texts read as text and only its own controls as buttons.
 */
fun Modifier.coversScreen(): Modifier = semantics { dialog() }.pointerInput(Unit) {}

/** How far the debug link rises, so the bottom bar's orange button does not cover it. */
val LocalDebugLinkLift = compositionLocalOf { 0.dp }

/** Debug-only link floating in the home-indicator inset, so it takes no layout space: the bottom block then ends where the boards end it. Nothing in a release build. */
@Composable
fun BoxScope.DebugLink(onClick: () -> Unit, dark: Boolean = false) {
    if (!rememberIsDebugBuild()) return
    Text(
        text = "รายการหน้าทั้งหมด (debug)",
        color = if (dark) MbWhite.copy(alpha = 0.5f) else MbBody,
        fontSize = 11.sp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(bottom = LocalDebugLinkLift.current)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("open_catalog"),
    )
}
