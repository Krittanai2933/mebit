package com.mebit.app.feature.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ExportOutcome
import com.mebit.app.data.CreateWalletDraft
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.Preset
import com.mebit.app.data.ReadyPlan
import com.mebit.app.data.SamePhrase
import com.mebit.app.data.SavedKey
import com.mebit.app.data.Signing
import com.mebit.app.data.keyLine
import com.mebit.app.feature.onboarding.AddressTypeRows
import com.mebit.app.feature.onboarding.DEFAULT_BADGE
import com.mebit.app.feature.onboarding.RadioRow
import com.mebit.app.navigation.MovieRoute
import com.mebit.app.rememberTextFileExport
import com.mebit.app.safeShareName
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbDisabledFill
import com.mebit.app.ui.theme.MbDisabledInk
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbProgress
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWarmTint
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork

/** What Penpot leaves under the primary button on every wizard board, so the button sits at y 732. */
internal val BelowPrimary = 38.dp

/** Penpot 3.1.1's eyebrow, for the three steps of a one-key wallet: Policy, Choose Key (3.2.1 to 3.2.3) and the address type. */
internal const val SINGLE_SIG_POLICY_EYEBROW = "POLICY · ขั้น 1/3"

/** Penpot 3.1 and 3.1.1. Multisig is selected when the screen opens. Single-sig goes on to Choose Key, and [line] says why a refused start did not. */
@Composable
fun PolicyScreen(
    draft: CreateWalletDraft,
    line: String?,
    onMultisig: () -> Unit,
    onSingleSig: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val single = draft.signing == Signing.SingleSig
    MebitScreen(
        eyebrow = if (single) SINGLE_SIG_POLICY_EYEBROW else MovieRoute.Policy.eyebrow,
        title = MovieRoute.Policy.titleTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/policy",
        bottom = {
            PrimaryCta("ต่อไป เลือกกุญแจ", "cta_policy_next", { if (single) onSingleSig() else onMultisig() })
            Spacer(modifier = Modifier.height(BelowPrimary))
        },
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Column(modifier = Modifier.selectableGroup()) {
            SigningCard(
                title = "กระเป๋าเดี่ยว",
                kind = "SINGLE-SIG",
                body = "กุญแจเดียวเซ็นได้เลย ใช้จ่ายคล่อง เหมาะกับเงินที่ใช้ประจำ",
                keys = 1,
                selected = single,
                tag = "policy_single",
                onClick = { draft.signing = Signing.SingleSig },
            )
            Spacer(modifier = Modifier.height(12.dp))
            SigningCard(
                title = "หลายลายเซ็น",
                kind = "MULTISIG",
                body = "ต้องใช้หลายกุญแจร่วมกันเซ็น กุญแจหายใบเดียวเงินยังปลอดภัย",
                keys = 2,
                selected = !single,
                tag = "policy_multi",
                onClick = { draft.signing = Signing.Multisig },
            )
        }
        if (!single) {
            Spacer(modifier = Modifier.height(30.dp))
            Text("ต้องใช้กี่กุญแจในการเซ็น", color = MbNavyInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                Preset.entries.forEach { preset ->
                    PresetChip(
                        title = preset.label,
                        sub = preset.sub,
                        selected = draft.preset == preset,
                        enabled = true,
                        tag = "preset_${preset.m}_${preset.n}",
                        onClick = { draft.choose(preset) },
                    )
                }
                // Penpot draws a custom M / N chip but no screen for it, so it is shown off and is no value.
                PresetChip(title = "กำหนดเอง", sub = "M / N", selected = false, enabled = false, tag = "preset_custom", onClick = {})
            }
        }
        line?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
        }
    }
}

/** A 362 by 122 card: [keys] key outlines, the [title], the [kind] line and the [body]. Selection is only the fill and the stroke. */
@Composable
private fun SigningCard(title: String, kind: String, body: String, keys: Int, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(122.dp)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(if (selected) MbWarmTint else MbWhite)
            .border(if (selected) 2.dp else 1.dp, if (selected) MbBtcOrange else MbBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        repeat(keys) { i ->
            Box(Modifier.offset(x = (20 + 17 * i).dp, y = (24 + 2 * i).dp).size(13.dp, 22.dp).border(2.dp, MbNavy, RoundedCornerShape(4.dp)))
        }
        Text(title, color = MbNavyInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, modifier = Modifier.offset(x = 66.dp, y = 17.dp))
        Text(kind, color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, modifier = Modifier.offset(x = 66.dp, y = 47.dp))
        Text(body, color = MbBody, fontSize = 12.5.sp, fontWeight = FontWeight.Light, lineHeight = 17.5.sp, modifier = Modifier.padding(start = 66.dp, top = 69.dp, end = 26.dp))
    }
}

@Composable
private fun RowScope.PresetChip(title: String, sub: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val ink = when {
        !enabled -> MbDisabledInk
        selected -> MbWhite
        else -> MbNavyInk
    }
    val subInk = when {
        !enabled -> MbDisabledInk
        selected -> MbWhite.copy(alpha = 0.7f)
        else -> MbSlate
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .clip(shape)
            .background(if (!enabled) MbDisabledFill else if (selected) MbNavy else MbWhite)
            .border(1.dp, if (selected) MbNavy else MbBorder, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, color = ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(sub, color = subInk, fontSize = 9.5.sp, lineHeight = 16.sp)
    }
}

/**
 * Penpot 3.2: one row per confirmed key, the counter and its bar, and the way to add another key. "ต่อไป ตรวจสอบ" is on exactly when the picks
 * make a [ReadyPlan] that no saved wallet in [wallets] already is, and that wallet's name is shown instead. The screen decides nothing about
 * navigation, and a key added from here comes back unticked.
 */
@Composable
fun ChooseKeysScreen(
    draft: CreateWalletDraft,
    keys: List<SavedKey>,
    wallets: List<CreatedVault>,
    onAddKey: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val plan = draft.ready(keys)
    val duplicate = plan?.duplicateOf(wallets)
    val blocked = draft.blocked(keys)
    MebitScreen(
        eyebrow = MovieRoute.ChooseKeys.eyebrow,
        title = MovieRoute.ChooseKeys.titleTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/choose-keys",
        bottom = {
            PrimaryCta("ต่อไป ตรวจสอบ", "cta_choose_next", onContinue, enabled = plan != null && duplicate == null)
            Spacer(modifier = Modifier.height(BelowPrimary))
        },
    ) {
        PickCounter(draft.livePicks(keys).size, draft.preset.n)
        samePhraseLines(blocked).forEach {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MbEyebrow, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.testTag("choose_same_phrase"))
        }
        if (duplicate != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(duplicateLine(duplicate.name), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("choose_duplicate"))
        }
        Spacer(modifier = Modifier.height(19.dp))
        // A skipped backup leaves a phone's key unconfirmed, and it stays off the list until it is backed up.
        keys.filter { it.verified }.forEach { key ->
            PickRow(key.label, keyLine(key), checked = key.id in draft.picks, enabled = key.id !in blocked, tag = "pick_${key.id}") { draft.toggle(key.id, keys) }
            Spacer(modifier = Modifier.height(10.dp))
        }
        SecondaryCta("เพิ่มกุญแจอีก", "cta_choose_add_key", onAddKey)
    }
}

@Composable
private fun PickCounter(picked: Int, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pickCountLine(picked, n), color = MbNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.width(142.dp).testTag("choose_count"))
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MbBorder)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((picked.toFloat() / n)).background(MbProgress))
        }
    }
}

/**
 * An [enabled] false row is a key another pick rules out: greyed, and a tap does nothing. A [radio] row is one choice of several, as
 * 3.2.1 to 3.2.3 draw it, with a round mark in place of the box.
 */
@Composable
internal fun PickRow(name: String, line: String, checked: Boolean, tag: String, enabled: Boolean = true, radio: Boolean = false, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(if (enabled) 4.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(if (checked) MbWarmTint else if (enabled) MbWhite else MbDisabledFill)
            .border(if (checked) 2.dp else 1.dp, if (checked) MbBtcOrange else MbBorder, shape)
            .then(
                if (radio) Modifier.selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = onToggle)
                else Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }),
            )
            .testTag(tag),
    ) {
        val box = if (radio) CircleShape else RoundedCornerShape(7.dp)
        Box(
            Modifier
                .offset(x = 16.dp, y = 20.dp)
                .size(22.dp)
                .clip(box)
                .background(if (checked) MbNavy else MbWhite)
                .border(if (checked) 2.dp else 1.5.dp, if (checked) MbNavy else MbDisabledInk, box),
        ) {
            if (checked) Tick(Modifier.offset(x = 6.dp, y = 6.dp).size(9.8.dp, 7.7.dp))
        }
        Column(Modifier.padding(start = 52.dp, top = 12.dp, end = 16.dp)) {
            Text(name, color = if (enabled) MbNavyInk else MbDisabledInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(line, color = if (enabled) MbSlate else MbDisabledInk, fontSize = 11.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Pure. One line under the counter per pick that rules keys out, each naming the pick and the key, in keyring order. */
internal fun samePhraseLines(blocked: Map<String, SamePhrase>): List<String> = blocked.values.map { it.line }.distinct()

/** A white check drawn corner to corner of its box: down to the low point a third in, then up to the far corner. */
@Composable
internal fun Tick(modifier: Modifier) {
    Canvas(modifier) {
        val tick = Path().apply {
            moveTo(0f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width, 0f)
        }
        drawPath(tick, MbWhite, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Penpot 3.3's rows. Only Native SegWit is built, because vault-core makes wsh(sortedmulti) alone, so Nested SegWit is drawn greyed
 * and cannot be chosen.
 */
internal enum class MultisigScript(val label: String, val script: String, private val prefixOf: AddressType) {
    NestedSegwit("Nested SegWit", "P2SH-P2WSH", AddressType.NESTED_SEGWIT),
    NativeSegwit("Native SegWit", "P2WSH", AddressType.NATIVE_SEGWIT),
    ;

    /** "BIP48 · P2WSH · เริ่มด้วย tb1q" on testnet and "... bc1q" on mainnet. The prefixes are Address Type's, so the two screens agree. */
    fun detail(network: KeyNetwork): String = "BIP48 · $script · เริ่มด้วย ${AddressTypeRows.first { it.type == prefixOf }.prefix(network)}"
}

/** Penpot 3.3. The script is fixed to Native SegWit, so a tap on it changes nothing. */
@Composable
fun ScriptTypeScreen(
    network: KeyNetwork,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = MovieRoute.ScriptType.eyebrow,
        title = MovieRoute.ScriptType.titleTh,
        subtitle = MovieRoute.ScriptType.bodyTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/script-type",
        bottom = {
            PrimaryCta("ต่อไป", "cta_script_next", onContinue)
            Spacer(modifier = Modifier.height(BelowPrimary))
        },
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Column(Modifier.selectableGroup()) {
            MultisigScript.entries.forEachIndexed { i, script ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                val native = script == MultisigScript.NativeSegwit
                RadioRow(
                    label = script.label,
                    detail = script.detail(network),
                    badge = if (native) DEFAULT_BADGE else null,
                    selected = native,
                    enabled = native,
                    tag = if (native) "script_native" else "script_nested",
                    onSelect = {},
                )
            }
        }
    }
}

/** Penpot 3.4, and 3.4.1 while the descriptor is open. Everything shown comes from the draft and the live keys, and nothing here calls the core. */
@Composable
fun ReviewScreen(
    draft: CreateWalletDraft,
    keys: List<SavedKey>,
    onCreate: (ReadyPlan) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val ready = draft.ready(keys)
    var descriptorOpen by remember { mutableStateOf(false) }
    MebitScreen(
        eyebrow = MovieRoute.Review.eyebrow,
        title = MovieRoute.Review.titleTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/review",
        bottom = {
            PrimaryCta("สร้าง Wallet", "cta_create_vault", { ready?.let(onCreate) }, enabled = ready != null)
            Spacer(modifier = Modifier.height(17.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(18.dp).clickable(role = Role.Button) { descriptorOpen = !descriptorOpen }.testTag("cta_review_descriptor"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (descriptorOpen) "ซ่อน descriptor" else "ดู descriptor แบบเต็ม", color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light)
            }
            Spacer(modifier = Modifier.height(3.dp))
        },
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        SummaryCard(draft.preset)
        Spacer(modifier = Modifier.height(14.dp))
        ReviewRowsCard(reviewRows(draft.preset, ready?.keys.orEmpty().map { it.label }))
        if (descriptorOpen && ready != null) {
            Spacer(modifier = Modifier.height(16.dp))
            DescriptorBox(ready.descriptor)
        }
    }
}

@Composable
private fun SummaryCard(preset: Preset) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MbNavy)
            .testTag("review_summary"),
    ) {
        Box(Modifier.offset(x = 22.dp, y = 25.dp).size(8.dp).background(MbBtcOrange, CircleShape))
        Text("POLICY สรุป", color = MbWhite.copy(alpha = 0.7f), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.offset(x = 36.dp, y = 20.dp))
        Text(reviewHeadline(preset), color = MbWhite, fontSize = 18.sp, lineHeight = 26.sp, modifier = Modifier.padding(start = 22.dp, top = 52.dp, end = 22.dp))
        Text(reviewSpare(preset), color = MbWhite.copy(alpha = 0.6f), fontSize = 11.5.sp, fontWeight = FontWeight.Light, lineHeight = 16.sp, modifier = Modifier.padding(start = 22.dp, top = 138.dp, end = 22.dp))
    }
}

@Composable
private fun ReviewRowsCard(rows: List<Pair<String, String>>) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp)
            .testTag("review_rows"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 18.dp)) {
                Text(label, color = MbSlate, fontSize = 11.5.sp, fontWeight = FontWeight.Light, lineHeight = 18.sp, modifier = Modifier.width(130.dp))
                Text(value, color = MbNavyInk, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Penpot 3.4.1's box. [descriptor] is one unbroken string that wraps where the box ends, and the box grows with it. */
@Composable
private fun DescriptorBox(descriptor: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .clip(shape)
            .background(MbPage)
            .border(1.dp, MbBorder, shape)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)
            .testTag("review_descriptor"),
    ) {
        Text(DESCRIPTOR_LABEL, color = MbSlate, fontSize = 9.5.sp, fontWeight = FontWeight.Light, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(descriptor, color = MbNavyInk, fontSize = 12.sp, lineHeight = 16.2.sp)
    }
}

/**
 * Penpot 3.5: no back chip, no eyebrow and no bar, a navy hero over the top of the screen and the card that exports the BSMS file. The 90-day
 * health check Penpot draws between them is not built, so the backup card moves up into its place. [vault] is null only in the debug catalog.
 */
@Composable
fun VaultCreatedScreen(
    vault: CreatedVault?,
    onOpenWallets: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    var line by remember { mutableStateOf<String?>(null) }
    // Cancelled and Done say nothing, because the system sheet already showed what happened.
    val export = rememberTextFileExport { outcome -> line = if (outcome is ExportOutcome.Failed) BSMS_EXPORT_FAILED else null }
    Box(Modifier.fillMaxSize().background(MbPage).testTag("screen_vault/created")) {
        Box(Modifier.fillMaxWidth().height(276.dp).clipToBounds().background(MbNavy)) {
            Ring(size = 220, offsetX = 104, offsetY = -66, alpha = 0.16f)
            Ring(size = 150, offsetX = 74, offsetY = -24, alpha = 0.12f)
            Box(Modifier.offset(x = 20.dp, y = 78.dp).size(46.dp).background(MbSuccess, CircleShape)) {
                Tick(Modifier.offset(x = 16.dp, y = 17.dp).size(13.8.dp, 10.4.dp))
            }
            Text(MovieRoute.VaultCreated.titleTh, color = MbWhite, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp, modifier = Modifier.offset(x = 20.dp, y = 140.dp).testTag("title"))
            Text(
                vault?.let { createdSubtitle(it.address) } ?: MultisigScript.NativeSegwit.label,
                color = MbWhite.copy(alpha = 0.7f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 24.sp,
                modifier = Modifier.offset(x = 20.dp, y = 190.dp).testTag("created_subtitle"),
            )
        }
        Column(Modifier.offset(y = 258.dp).padding(horizontal = 20.dp)) {
            BackupCard(
                enabled = vault != null,
                onSave = { vault?.let { export.save(bsmsFileName(it.name), it.bsms) } },
                onShare = { vault?.let { export.share(bsmsFileName(it.name), it.bsms) } },
            )
            line?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            PrimaryCta("เข้าใช้งาน Wallet", "cta_open_vaults", onOpenWallets)
            Spacer(modifier = Modifier.height(BelowPrimary))
        }
        DebugLink(onClick = onOpenCatalog)
    }
}

/** One of the hero's two decorative rings, [offsetX] past the screen's right edge, clipped by the hero. */
@Composable
internal fun BoxScope.Ring(size: Int, offsetX: Int, offsetY: Int, alpha: Float) {
    Box(Modifier.align(Alignment.TopEnd).offset(x = offsetX.dp, y = offsetY.dp).size(size.dp).border(1.dp, MbProgress.copy(alpha = alpha), CircleShape))
}

@Composable
private fun BackupCard(enabled: Boolean, onSave: () -> Unit, onShare: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape),
    ) {
        Box(Modifier.offset(x = 18.dp, y = 21.dp).size(8.dp).background(MbBtcOrange, CircleShape))
        Text("BACKUP / EXPORT", color = MbBody, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.offset(x = 30.dp, y = 17.dp))
        Text("สำรองข้อมูล Wallet", color = MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, modifier = Modifier.offset(x = 18.dp, y = 46.dp))
        Text(
            "ส่งออกเป็นไฟล์ BSMS (ไม่มี seed) เก็บไว้นอกแอปเพื่อกู้คืน",
            color = MbBody,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 18.dp, top = 73.dp, end = 24.dp),
        )
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 116.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FileButton("บันทึกไฟล์ BSMS", "cta_bsms_save", enabled, onSave)
            FileButton("แชร์ไฟล์ BSMS", "cta_bsms_share", enabled, onShare)
        }
    }
}

@Composable
private fun RowScope.FileButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(30.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MbChip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) MbNavy else MbDisabledInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun pickCountLine(picked: Int, n: Int): String = "เลือกแล้ว $picked / $n ใบ"

internal fun duplicateLine(walletName: String): String = "Wallet นี้อยู่ในเครื่องนี้แล้ว · $walletName"

internal fun reviewHeadline(preset: Preset): String = "ต้องใช้กุญแจ ${preset.m} ใบจากทั้งหมด ${preset.n} ใบ เซ็นร่วมกัน จึงจะใช้จ่ายจาก Wallet นี้ได้"

internal fun reviewSpare(preset: Preset): String = "กุญแจหายได้ถึง ${preset.n - preset.m} ใบโดยเงินยังปลอดภัย"

/** Penpot 3.4's four rows. The mebit backup key and the timelock are not built, so their values are fixed. */
internal fun reviewRows(preset: Preset, keyNames: List<String>): List<Pair<String, String>> = listOf(
    "Policy" to "${preset.label} · ${MultisigScript.NativeSegwit.label}",
    "กุญแจที่ใช้" to keyNames.joinToString(", "),
    "กุญแจสำรอง mebit" to "ไม่ใช้",
    "เงื่อนไขเวลา" to "ไม่มี",
)

internal const val DESCRIPTOR_LABEL = "DESCRIPTOR · BIP48 / P2WSH"

/** "Native SegWit · tb1q…7k2m (address 1)": the first address, shortened. */
internal fun createdSubtitle(address: String): String = "Native SegWit · ${address.take(4)}…${address.takeLast(4)} (address 1)"

/** "Wallet 2" is saved as "Wallet_2.bsms", a name every file system and the FileProvider take. */
internal fun bsmsFileName(walletName: String): String = safeShareName(walletName) + ".bsms"

/** Draft copy, no board draws an export failure. */
internal const val BSMS_EXPORT_FAILED = "บันทึกหรือแชร์ไฟล์ BSMS ไม่สำเร็จ ลองอีกครั้ง"
