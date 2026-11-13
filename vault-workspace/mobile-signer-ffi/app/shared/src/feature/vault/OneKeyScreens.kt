package com.mebit.app.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.CreateWalletDraft
import com.mebit.app.data.HardwareWallet
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.SavedKey
import com.mebit.app.data.hardwareKind
import com.mebit.app.data.keyLine
import com.mebit.app.data.keySourceName
import com.mebit.app.feature.onboarding.AddressTypeRows
import com.mebit.app.feature.onboarding.DEFAULT_BADGE
import com.mebit.app.feature.onboarding.RadioRow
import com.mebit.app.navigation.MovieRoute
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletKind

/** Confirm Key's line when a one-key wallet could not be saved, in the words of Penpot T18's title. */
internal const val CREATE_FAILED_LINE = "สร้าง Wallet ไม่สำเร็จ"

/** The word the one-key boards use for a device: "Jade", or "Trezor" for a Safe 7, as in "กระเป๋า Trezor" and "เซ็นบน Trezor". */
internal fun deviceWord(kind: WalletKind): String = when (kind) {
    WalletKind.JADE -> "Jade"
    WalletKind.TREZOR_SAFE7 -> "Trezor"
}

/** [deviceWord] for a saved key's source, which is a Jade or a Safe 7 here. */
internal fun deviceWord(source: KeySourceLabel): String = deviceWord(hardwareKind(source))

/** Pure. 3.2.2's and 3.2.3's line under the rows once a device's key is picked. 3.2.1, this phone, has none. */
internal fun oneKeyNote(device: SavedKey?): String? = device?.let {
    val word = deviceWord(it.source)
    "$word จะเป็นกุญแจเดียวของกระเป๋านี้ ทุกครั้งที่ส่งต้องกดยืนยันบน $word"
}

/** Pure. 3.3.2's line under its button, naming the device the next step reads the key from. */
internal fun hardwareTypeNote(device: SavedKey?): String =
    "ตัวเลือกนี้กำหนดรูปแบบที่อยู่ของกระเป๋านี้" + (device?.let { " ขั้นถัดไป mebit จะอ่านกุญแจของแบบนี้จาก ${deviceWord(it.source)}" } ?: "")

/** Pure. 3.5.2's subtitle: the type, the first address shortened, and where the key lives. */
internal fun hardwareCreatedSubtitle(wallet: HardwareWallet): String =
    "${AddressTypeRows.first { it.type == wallet.addressType }.label} · ${wallet.address.take(4)}…${wallet.address.takeLast(4)} (ที่อยู่แรก) · กุญแจอยู่บน ${deviceWord(wallet.kind)}"

/**
 * Penpot 3.2.1 to 3.2.3, the one key of a single-sig wallet: a new key on this phone, which goes on as today ([onThisPhone]), or a
 * confirmed Jade or Safe 7 key from the Keyring ([keys], [com.mebit.app.data.oneKeyChoices]), whose device the next steps read
 * ([onDevice]). One row is always picked, this phone first. [line] says why a refused start of a phone wallet did not go on.
 */
@Composable
fun ChooseKeyScreen(
    draft: CreateWalletDraft,
    keys: List<SavedKey>,
    line: String?,
    onThisPhone: () -> Unit,
    onDevice: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val device = draft.device(keys)
    MebitScreen(
        eyebrow = MovieRoute.ChooseKey.eyebrow,
        title = MovieRoute.ChooseKey.titleTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/choose-key",
        bottom = {
            PrimaryCta("ต่อไป", "cta_choose_key_next", { if (device == null) onThisPhone() else onDevice() })
            Spacer(modifier = Modifier.height(BelowPrimary))
        },
    ) {
        Text(pickCountLine(1, 1), color = MbNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.testTag("choose_count"))
        Spacer(modifier = Modifier.height(19.dp))
        Column(Modifier.selectableGroup()) {
            PickRow("iPhone ของฉัน", keySourceName(KeySourceLabel.ThisDevice), checked = device == null, tag = "pick_this_phone", radio = true) { draft.deviceKey = null }
            keys.forEach { key ->
                Spacer(modifier = Modifier.height(10.dp))
                PickRow(key.label, keyLine(key), checked = device?.id == key.id, tag = "pick_${key.id}", radio = true) { draft.deviceKey = key.id }
            }
        }
        oneKeyNote(device)?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MbBody, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.testTag("choose_key_note"))
        }
        line?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
        }
    }
}

/**
 * Penpot 3.3.2, the address type of a one-key hardware wallet: the four types, Native SegWit the default, and under the button the line
 * that the next step reads this type's key from [device]. The rows give testnet's prefixes, as Address Type 1.5.1 does on testnet.
 */
@Composable
fun HardwareAddressTypeScreen(
    draft: CreateWalletDraft,
    device: SavedKey?,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = MovieRoute.HardwareAddressType.eyebrow,
        title = MovieRoute.HardwareAddressType.titleTh,
        subtitle = MovieRoute.HardwareAddressType.bodyTh,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/hw-address-type",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryCta("ต่อไป", "cta_hw_address_type", onContinue, enabled = device != null)
            Spacer(modifier = Modifier.height(12.dp))
            Text(hardwareTypeNote(device), color = MbSlate, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("hw_address_type_note"))
        },
    ) {
        Spacer(modifier = Modifier.height(28.dp))
        Column(modifier = Modifier.selectableGroup()) {
            AddressTypeRows.forEachIndexed { i, row ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                RadioRow(
                    label = row.label,
                    detail = row.detail(KeyNetwork.TEST),
                    badge = if (row.type == AddressType.NATIVE_SEGWIT) DEFAULT_BADGE else null,
                    selected = row.type == draft.addressType,
                    enabled = true,
                    tag = "hw_address_type_" + row.type.name.lowercase(),
                    onSelect = { draft.addressType = row.type },
                )
            }
        }
    }
}

/**
 * Penpot 3.5.2: the navy hero of 3.5 with the wallet's name, its type, its first address shortened and where its key lives. No back
 * chip, as on 3.5. The 90-day health check the board draws under the hero is not built, as on 3.5, and a one-key wallet has no BSMS file
 * to save, so the hero is all. [wallet] is null only in the debug catalog.
 */
@Composable
fun HardwareCreatedScreen(
    wallet: HardwareWallet?,
    onOpenWallets: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MbPage).testTag("screen_vault/hw-created")) {
        Box(Modifier.fillMaxWidth().height(276.dp).clipToBounds().background(MbNavy)) {
            Ring(size = 220, offsetX = 104, offsetY = -66, alpha = 0.16f)
            Ring(size = 150, offsetX = 74, offsetY = -24, alpha = 0.12f)
            Box(Modifier.offset(x = 20.dp, y = 78.dp).size(46.dp).background(MbSuccess, CircleShape)) {
                Tick(Modifier.offset(x = 16.dp, y = 17.dp).size(13.8.dp, 10.4.dp))
            }
            Text(
                "${wallet?.name ?: ""} ${MovieRoute.HardwareCreated.titleTh}".trim(),
                color = MbWhite,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 38.sp,
                modifier = Modifier.offset(x = 20.dp, y = 140.dp).testTag("title"),
            )
            wallet?.let {
                Text(
                    hardwareCreatedSubtitle(it),
                    color = MbWhite.copy(alpha = 0.7f),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 42.dp, top = 190.dp).testTag("created_subtitle"),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            PrimaryCta("เข้าใช้งานกระเป๋า", "cta_open_wallet", onOpenWallets)
            Spacer(modifier = Modifier.height(BelowPrimary))
        }
        DebugLink(onClick = onOpenCatalog)
    }
}
