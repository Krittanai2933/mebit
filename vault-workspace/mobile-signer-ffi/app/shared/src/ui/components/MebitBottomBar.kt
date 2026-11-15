package com.mebit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.navigation.BarTab
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_tab_bitcoin
import com.mebit.app.resources.ic_tab_key
import com.mebit.app.resources.ic_tab_wallet
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** From the bar's straight top edge to the screen bottom. */
@Composable
fun bottomBarHeight(): Dp {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 40 dp or more is Android's three-button bar, which the bar must clear whole. The iPhone's 34 pt home indicator keeps the board's 11 dp.
    return 60.dp + if (inset >= 40.dp) inset else 11.dp
}

/** Penpot's Bitcoin bottom bar: two tabs and the orange รับ / ส่ง button in a dip at the centre. */
@Composable
fun MebitBottomBar(
    selected: BarTab,
    onWallets: () -> Unit,
    onKeys: () -> Unit,
    onReceiveSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().height(18.dp + bottomBarHeight())) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = 1.dp.toPx()
            val cx = size.width / 2
            val path = Path().apply {
                moveTo(0f, 18 * u)
                lineTo(cx - 45 * u, 18 * u)
                cubicTo(cx - 39 * u, 18 * u, cx - 37 * u, 26 * u, cx - 33 * u, 38 * u)
                cubicTo(cx - 27 * u, 56 * u, cx - 15 * u, 62 * u, cx, 62 * u)
                cubicTo(cx + 15 * u, 62 * u, cx + 27 * u, 56 * u, cx + 33 * u, 38 * u)
                cubicTo(cx + 37 * u, 26 * u, cx + 39 * u, 18 * u, cx + 45 * u, 18 * u)
                lineTo(size.width, 18 * u)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, MbWhite)
            drawPath(path, MbBorder, style = Stroke(width = u))
        }
        BarTabItem("กระเป๋า", Res.drawable.ic_tab_wallet, selected == BarTab.Wallets, "tab_wallets", onWallets, Modifier.align(Alignment.TopStart).padding(start = 50.dp, top = 26.dp))
        BarTabItem("กุญแจ", Res.drawable.ic_tab_key, selected == BarTab.Keys, "tab_keys", onKeys, Modifier.align(Alignment.TopEnd).padding(end = 50.dp, top = 26.dp))
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 84.dp, height = 86.dp)
                .clickable(role = Role.Button, onClick = onReceiveSend)
                .testTag("tab_receive_send"),
        ) {
            Box(Modifier.offset(x = 12.dp).size(60.dp).background(MbPage, CircleShape))
            Box(
                Modifier
                    .offset(x = 16.dp, y = 4.dp)
                    .size(52.dp)
                    .shadow(4.dp, CircleShape, clip = false, ambientColor = MbBtcOrange.copy(alpha = 0.16f), spotColor = MbBtcOrange.copy(alpha = 0.16f))
                    .background(MbBtcOrange, CircleShape),
            )
            Image(painterResource(Res.drawable.ic_tab_bitcoin), contentDescription = null, modifier = Modifier.offset(x = 25.9.dp, y = 14.dp).size(26.dp), colorFilter = ColorFilter.tint(MbWhite))
            Box(Modifier.offset(y = 65.dp).fillMaxWidth().height(15.dp), contentAlignment = Alignment.Center) {
                Text("รับ / ส่ง", color = MbNavy, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BarTabItem(label: String, icon: DrawableResource, selected: Boolean, tag: String, onClick: () -> Unit, modifier: Modifier) {
    val ink = if (selected) MbNavy else MbBody
    Box(
        modifier = modifier
            .size(width = 76.dp, height = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag),
    ) {
        if (selected) Box(Modifier.offset(x = 22.dp, y = 3.dp).size(32.dp).background(MbIconWash, CircleShape))
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.offset(x = 27.dp, y = 8.dp).size(22.dp), colorFilter = ColorFilter.tint(ink))
        Box(Modifier.offset(y = 34.dp).fillMaxWidth().height(15.dp), contentAlignment = Alignment.Center) {
            Text(label, color = ink, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center)
        }
        if (selected) Box(Modifier.offset(x = 21.5.dp, y = 50.dp).size(width = 33.dp, height = 2.dp).background(MbBtcOrange, RoundedCornerShape(1.5.dp)))
    }
}
