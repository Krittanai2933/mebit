package com.mebit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbMuted
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbWhite
import com.mebit.app.ui.theme.MbIconWash

/** Penpot Movie Page light scaffold (Add Key / Key Source / Vault Created). */
@Composable
fun MebitScreen(
    eyebrow: String,
    title: String,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    tag: String,
    subtitle: String? = null,
    dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg = if (dark) MbNavy else MbPage
    val titleColor = if (dark) MbWhite else MbNavyInk
    val eyebrowColor = if (dark) MbGold else MbEyebrow
    val bodyColor = if (dark) MbWhite.copy(alpha = 0.72f) else MbBody

    // Background edge-bleeds under system bars; content clears status / Dynamic Island / home indicator.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 16.dp)
            .testTag(tag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (onBack != null) {
                BackChip(onClick = onBack, dark = dark)
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = eyebrow.uppercase(),
                color = eyebrowColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.testTag("eyebrow"),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
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
            content()
        }
        TextButton(
            onClick = onOpenCatalog,
            modifier = Modifier.fillMaxWidth().testTag("open_catalog"),
        ) {
            Text(
                "รายการหน้าทั้งหมด (debug)",
                color = if (dark) MbWhite.copy(alpha = 0.5f) else MbBody,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun BackChip(onClick: () -> Unit, dark: Boolean = false) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .shadow(2.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(if (dark) MbWhite.copy(alpha = 0.12f) else MbWhite)
            .clickable(onClick = onClick)
            .testTag("back"),
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

@Composable
fun OptionCard(
    title: String,
    body: String,
    tag: String,
    onClick: () -> Unit,
    chips: List<String> = emptyList(),
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
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { HwChip(it) }
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
            .height(54.dp)
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
) {
    val border = if (darkSurface) MbWhite.copy(alpha = 0.35f) else MbNavy.copy(alpha = 0.2f)
    val fg = if (darkSurface) MbWhite else MbNavy
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
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
