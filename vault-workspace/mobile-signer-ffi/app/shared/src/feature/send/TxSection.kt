package com.mebit.app.feature.send

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite

// Four and a half rows at the 67 dp pitch, so a fifth row peeks out to show the box scrolls.
internal val TxListMaxHeight = 301.5.dp

/**
 * A wallet page's transactions: the header, the gap a pull opens under it, then [rows] (Home 4.1.x, a several-key wallet's detail
 * 3.7.x). ดูทั้งหมด shows when [hasMore] and stays while a status shows, left of it, as 3.7.4 and 3.7.5 draw it. The status is drawn
 * over the header row, so it moves nothing below. [tag] starts the header's test tags.
 */
@Composable
internal fun TxSection(
    title: String,
    tag: String,
    hasMore: Boolean,
    historyTag: String,
    onHistory: () -> Unit,
    status: TxStatus,
    pull: RowsPullState,
    rows: @Composable () -> Unit,
) {
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, modifier = Modifier.weight(1f).testTag("${tag}_tx_label"))
            // Holds the row's height. While a status shows, the link is drawn beside it instead.
            if (hasMore) {
                SeeAll(if (status == TxStatus.None) Modifier.clickable(role = Role.Button, onClick = onHistory).testTag(historyTag) else Modifier.alpha(0f).clearAndSetSemantics { })
            }
        }
        if (status != TxStatus.None) {
            Row(
                modifier = Modifier.matchParentSize().wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasMore) {
                    SeeAll(Modifier.clickable(role = Role.Button, onClick = onHistory).testTag(historyTag))
                    Spacer(modifier = Modifier.width(10.dp))
                }
                if (status == TxStatus.Refreshing) {
                    RefreshSpinner()
                    Spacer(modifier = Modifier.width(11.dp))
                }
                Text("กำลังอัปเดต…", color = MbBody, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.2.sp, modifier = Modifier.testTag("${tag}_refresh_status"))
            }
        }
    }
    PullGap(pull, "${tag}_pull_badge")
    rows()
}

/** The header's link to the whole history. Its padding sets the header row's height. */
@Composable
private fun SeeAll(modifier: Modifier) {
    Text("ดูทั้งหมด", color = MbNavy, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, modifier = modifier.padding(vertical = 8.dp))
}

/**
 * How far the rows are pulled open, in Material's threshold units: 0 at rest and 1 where letting go refreshes. A refresh shows in the
 * header (4.1.4, 3.7.5), so it closes the gap to 0 and ignores the snaps Material's node makes after every scroll while it holds the offset at
 * the threshold. On release past the threshold the node calls onRefresh and animateToHidden at once, then recomposition calls animateToThreshold.
 */
internal class RowsPullState : PullToRefreshState {
    private val fraction = Animatable(0f)
    private var refreshing = false
    override val distanceFraction: Float get() = fraction.value
    override val isAnimating: Boolean get() = fraction.isRunning
    override suspend fun snapTo(targetValue: Float) { if (!refreshing) fraction.snapTo(targetValue) }
    override suspend fun animateToThreshold() { refreshing = true; fraction.animateTo(0f) }
    override suspend fun animateToHidden() { refreshing = false; fraction.animateTo(0f) }
}

/** The refresh status at the right of the transactions header: spinner and text while a pull refreshes (4.1.4, 3.7.5), the text alone once letting go would refresh (4.1.3, 3.7.4). */
internal enum class TxStatus { None, Armed, Refreshing }

internal fun txStatus(refreshing: Boolean, armed: Boolean): TxStatus = when {
    refreshing -> TxStatus.Refreshing
    armed -> TxStatus.Armed
    else -> TxStatus.None
}

/** The gap under the header that the pull opens, with its badge centred in it (4.1.3, 3.7.4). */
@Composable
private fun PullGap(pull: RowsPullState, tag: String) {
    val fraction = pull.distanceFraction
    Box(modifier = Modifier.fillMaxWidth().height(16.dp + 64.dp * fraction), contentAlignment = Alignment.Center) {
        if (fraction > 0f) {
            val grown = minOf(fraction, 1f)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { alpha = grown; scaleX = grown; scaleY = grown }
                    .background(MbWhite, CircleShape)
                    .border(0.75.dp, MbBorder, CircleShape)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                PullArc(boxSize = 24.dp, circle = 18.dp, sweep = 270f * grown)
            }
        }
    }
}

/** The refresh spinner (4.1.4, 3.7.5), turning once a second. The turn is read in the layer, so it redraws without recomposing the page. */
@Composable
private fun RefreshSpinner() {
    val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing)))
    PullArc(boxSize = 18.dp, circle = 13.5.dp, sweep = 270f, modifier = Modifier.graphicsLayer { rotationZ = turn })
}

/** The track circle with the navy arc on it, clockwise from 12 o'clock. */
@Composable
private fun PullArc(boxSize: Dp, circle: Dp, sweep: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(boxSize)) {
        val stroke = 2.5.dp.toPx()
        val diameter = circle.toPx()
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        drawArc(MbBorder, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        drawArc(MbNavy, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
