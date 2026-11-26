package com.mebit.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.NodeSetting
import com.mebit.app.data.BtcUnit
import com.mebit.app.data.NodeSettings
import com.mebit.app.data.passes
import com.mebit.app.data.UnitSettings
import com.mebit.app.feature.vault.PickRow
import com.mebit.app.ui.components.IssueBanner
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.keepsKeyboard
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbDisabledInk
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.amountFontFamily
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.app.navigation.MovieRoute
import com.mebit.ffi.signer.NodeCheck
import com.mebit.ffi.signer.NodeStatus
import com.mebit.ffi.signer.chainCheckNode
import com.mebit.ffi.signer.chainNodeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The node's name on 7.1's row and the choice cards on 7.2. */
internal fun NodeSetting.label(): String = when (this) {
    NodeSetting.Auto -> "อัตโนมัติ"
    NodeSetting.Blockstream -> "blockstream.info"
    NodeSetting.MempoolSpace -> "mempool.space"
    is NodeSetting.Own -> "โหนดของฉัน"
}

/** 7.2's line under the test field: what the last test found, in Thai. */
internal fun checkLine(found: NodeCheck?): String = when (found) {
    null -> "เลือกตัวเลือกนี้ได้เมื่อทดสอบผ่าน"
    NodeCheck.Testnet4 -> "ผ่าน · เป็นเชน Testnet4"
    NodeCheck.OtherChain -> "ไม่ผ่าน · โหนดนี้ไม่ใช่เชน Testnet4"
    NodeCheck.Mainnet -> "ผ่าน · เป็นเชน Mainnet"
    NodeCheck.BadUrl -> "ใช้ https:// เท่านั้น (http:// ได้เฉพาะที่อยู่ Tailscale)"
    is NodeCheck.Unreachable -> "ไม่ผ่าน · ต่อโหนดไม่ได้"
}

/** What a typed URL is compared and kept as: the core drops spaces and a closing slash the same way. */
internal fun typedBase(text: String): String = text.trim().trimEnd('/')

/**
 * Penpot 7.1: the Bitcoin unit, then the connection group. Left out: language and theme, which are features of their own (every string
 * is inline Thai and there is one light palette). The network is Testnet4 with Mainnet drawn disabled.
 */
@Composable
fun SettingsScreen(
    node: NodeSettings,
    unit: UnitSettings,
    onOpenNode: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var status by remember { mutableStateOf<NodeStatus?>(null) }
    LaunchedEffect(node.current) {
        status = null
        status = withContext(Dispatchers.IO) { chainNodeStatus(node.network) }
    }
    MebitScreen(
        eyebrow = MovieRoute.Settings.eyebrow,
        title = MovieRoute.Settings.titleTh,
        subtitle = MovieRoute.Settings.bodyTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_settings",
    ) {
        UnitCard(unit)
        Spacer(modifier = Modifier.height(20.dp))
        Text("การเชื่อมต่อ", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp).testTag("settings_group"))
        Spacer(modifier = Modifier.height(9.dp))
        SettingsCard(Modifier.height(82.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(82.dp).padding(start = 16.dp, end = 18.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("เครือข่าย", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                    Text("เงินทดสอบ ไม่มีมูลค่าจริง", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 16.sp, modifier = Modifier.testTag("settings_net_line"))
                }
                TwoOptionSwitch(
                    options = listOf(SwitchOption("Testnet4", "settings_net_testnet4"), SwitchOption("Mainnet", "settings_net_mainnet", enabled = false)),
                    pickedAt = 0,
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingsCard(Modifier.height(82.dp).clickable(role = Role.Button, onClick = onOpenNode).testTag("settings_node")) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(82.dp).padding(start = 16.dp, end = 18.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("โหนด", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    NodeStatusLine(status)
                }
                Text(node.current.label(), color = MbNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("settings_node_value"))
                Text("›", color = MbBody, fontSize = 24.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape)) { content() }
}

/** The dot, the host in use and whether it answered. A dot that has not heard yet is grey. */
@Composable
private fun NodeStatusLine(status: NodeStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("settings_node_status")) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (status == null) MbDisabledInk else if (status.reachable) MbSuccess else MbSlate))
        Spacer(modifier = Modifier.width(6.dp))
        val line = when {
            status == null -> "กำลังตรวจ…"
            status.reachable -> "${status.host} · ต่อได้"
            else -> "${status.host} · ต่อไม่ได้"
        }
        Text(line, color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 16.sp, maxLines = 1)
    }
}

/** 7.1's unit card: "sat" or "BTC", and every amount in the app follows it. */
@Composable
private fun UnitCard(unit: UnitSettings) {
    SettingsCard(Modifier.height(82.dp).testTag("settings_unit")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(82.dp).padding(start = 16.dp, end = 18.dp)) {
            Text("หน่วย Bitcoin", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TwoOptionSwitch(
                options = BtcUnit.entries.map { SwitchOption(it.label, "settings_unit_" + it.label.lowercase()) { unit.choose(it) } },
                pickedAt = if (unit.current == BtcUnit.Sat) 0 else 1,
            )
        }
    }
}

/** One side of a [TwoOptionSwitch]. With no [onPick] it cannot be tapped, and a disabled one is drawn grey. */
private class SwitchOption(val label: String, val tag: String, val enabled: Boolean = true, val onPick: (() -> Unit)? = null)

/** 7.1's switch, shared by the network card (Testnet4 picked, Mainnet disabled) and the unit card: a 126 by 38 track, radius 13, the picked side a navy pill inset 2 with radius 11. */
@Composable
private fun TwoOptionSwitch(options: List<SwitchOption>, pickedAt: Int) {
    val pill = RoundedCornerShape(11.dp)
    Row(modifier = Modifier.width(126.dp).height(38.dp).clip(RoundedCornerShape(13.dp)).background(MbPage).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { at, option ->
            val picked = at == pickedAt
            val tap = option.onPick?.let { Modifier.clickable(role = Role.RadioButton, onClick = it) } ?: Modifier
            Box(
                modifier = (if (picked) Modifier.clip(pill).background(MbNavy) else Modifier).then(tap).weight(1f).fillMaxHeight()
                    .semantics { selected = picked; if (!option.enabled) disabled() }.testTag(option.tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    color = if (picked) MbWhite else if (option.enabled) MbNavy else MbDisabledInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = amountFontFamily(),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Penpot 7.2: four radio cards and the warning. The user's own node can be chosen only after this exact URL passed the test, so a
 * URL edited since the test needs another. Choosing the card puts the core on the node and keeps it.
 */
@Composable
fun NodeScreen(
    node: NodeSettings,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val stored = node.current as? NodeSetting.Own
    var text by remember { mutableStateOf(stored?.base.orEmpty()) }
    // The URL that passed. A node kept earlier passed on this phone, so it starts as the tested one.
    var tested by remember { mutableStateOf(stored?.base) }
    var found by remember { mutableStateOf<NodeCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val typed = typedBase(text)
    val passed = typed.isNotEmpty() && typed == tested
    MebitScreen(
        eyebrow = MovieRoute.SettingsNode.eyebrow,
        title = MovieRoute.SettingsNode.titleTh,
        subtitle = MovieRoute.SettingsNode.bodyTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_settings/node",
    ) {
        PickRow("อัตโนมัติ (แนะนำ)", "ใช้ blockstream.info ก่อน ถ้าต่อไม่ได้ใช้ mempool.space", node.current == NodeSetting.Auto, "node_auto", radio = true) { node.choose(NodeSetting.Auto) }
        Spacer(modifier = Modifier.height(10.dp))
        PickRow("blockstream.info", "ใช้โหนดนี้เท่านั้น", node.current == NodeSetting.Blockstream, "node_blockstream", radio = true) { node.choose(NodeSetting.Blockstream) }
        Spacer(modifier = Modifier.height(10.dp))
        PickRow("mempool.space", "ใช้โหนดนี้เท่านั้น", node.current == NodeSetting.MempoolSpace, "node_mempool", radio = true) { node.choose(NodeSetting.MempoolSpace) }
        Spacer(modifier = Modifier.height(10.dp))
        OwnNodeCard(
            checked = node.current is NodeSetting.Own,
            canChoose = passed,
            text = text,
            onText = { text = it },
            line = checkLine(found),
            testing = testing,
            onChoose = { node.choose(NodeSetting.Own(typed)) },
            onTest = {
                val asked = typed
                testing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { chainCheckNode(asked, node.network) }
                    found = result
                    tested = if (result.passes(node.network)) asked else null
                    testing = false
                }
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
        IssueBanner(
            title = "เลือกโหนดที่คุณไว้ใจ",
            message = "ก่อนใช้โหนดใด แอปตรวจว่าเป็นเชน Testnet4 จริง\nแต่โหนดที่ไม่สุจริตยังแสดงยอดผิดได้",
            tag = "node_warning",
        )
    }
}

@Composable
private fun OwnNodeCard(
    checked: Boolean,
    canChoose: Boolean,
    text: String,
    onText: (String) -> Unit,
    line: String,
    testing: Boolean,
    onChoose: () -> Unit,
    onTest: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier.fillMaxWidth().shadow(4.dp, shape, clip = false).clip(shape).background(MbWhite)
            .border(if (checked) 2.dp else 1.dp, if (checked) MbNavy else MbBorder, shape)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .semantics { selected = checked; if (!canChoose) disabled() }
                .clickable(enabled = canChoose, role = Role.RadioButton, onClick = onChoose)
                .testTag("node_own"),
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (checked) MbNavy else MbWhite)
                    .border(if (checked) 2.dp else 1.5.dp, if (checked) MbNavy else MbDisabledInk, CircleShape),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("โหนดของฉัน", color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                Text("Esplora ของ Testnet4 ที่คุณดูแลเอง", color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(MbChip).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    textStyle = TextStyle(color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("https://", color = MbSlate, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("node_own_url"),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, MbNavy, RoundedCornerShape(14.dp))
                    .clickable(enabled = !testing && text.isNotBlank(), role = Role.Button, onClick = onTest)
                    .padding(horizontal = 18.dp)
                    .testTag("node_own_test"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (testing) "…" else "ทดสอบ", color = MbNavy, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(line, color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.Light, lineHeight = 14.sp, modifier = Modifier.testTag("node_own_line"))
    }
}
