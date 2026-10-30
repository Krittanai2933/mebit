package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_send
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

private val SwitchInk = Color(0xFF264D68)
private val SwitchGold = Color(0xFFFFC66C)

/** Penpot 4.3 / 4.4: the รับ / ส่ง switch under the header. Both screens carry it. */
@Composable
fun ReceiveSendSwitch(sendSelected: Boolean, onReceive: () -> Unit, onSend: () -> Unit) {
    val pill = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(6.dp, pill, clip = false)
            .clip(pill)
            .background(MbWhite)
            .border(1.dp, Color(0xFFC9D8E4), pill)
            .padding(6.dp)
            .testTag("receive_send_switch"),
    ) {
        Segment("รับ", send = false, selected = !sendSelected, onClick = onReceive, modifier = Modifier.weight(1f).testTag("switch_receive"))
        Segment("ส่ง", send = true, selected = sendSelected, onClick = onSend, modifier = Modifier.weight(1f).testTag("switch_send"))
    }
}

@Composable
private fun Segment(label: String, send: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .then(if (selected) Modifier.background(MbNavy) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.ic_send),
            contentDescription = null,
            modifier = Modifier.size(21.dp).rotate(if (send) 0f else 180f),
            colorFilter = ColorFilter.tint(if (selected) SwitchGold else SwitchInk),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = if (selected) MbWhite else SwitchInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
