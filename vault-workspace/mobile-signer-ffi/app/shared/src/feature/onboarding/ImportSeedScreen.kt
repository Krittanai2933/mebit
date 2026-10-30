package com.mebit.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.ImportSeedDraft
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SeedStatus
import com.mebit.app.data.ffiImportSeedDraft
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_check
import com.mebit.app.resources.ic_eye
import com.mebit.app.resources.ic_warning
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbPick
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

// Penpot Organize > 1B.1 to 1B.4.5. The grid is 3 columns of 8 slots, numbered down each column.
private const val ROWS = 8
private const val ROW_PITCH = 36 // a 30dp slot and a 6dp gap
private const val GRID_HEIGHT = 320
private const val DROPDOWN_ROW = 36
private const val LAST_SLOT = 23

private enum class ImportSheet { Passphrase, Fingerprint }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSeedScreen(
    session: OnboardingSession,
    onRestored: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val draft = remember { ffiImportSeedDraft() }
    var engaged by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<ImportSheet?>(null) }
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // A half-typed word is not an error while its slot has the keyboard.
    LaunchedEffect(keyboardUp, engaged) { draft.typing = keyboardUp && engaged }
    // After the 24th word the keyboard goes away, so the warning or the button shows.
    LaunchedEffect(draft.committed) {
        if (draft.committed) {
            keyboard?.hide() // on iOS clearing the focus alone leaves the keyboard up
            focusManager.clearFocus(force = true)
        }
    }

    fun restore() {
        session.restoreFromWords(draft.words(), draft.passphrase)
        when {
            session.lastError != null -> Unit // shown under the grid
            draft.passphrase.isEmpty() -> onRestored()
            // A mistyped passphrase is still a valid one, so the fingerprint is the only check.
            else -> sheet = ImportSheet.Fingerprint
        }
    }

    MebitScreen(
        eyebrow = "IMPORT SEED",
        title = "นำเข้า 24 คำ",
        subtitle = "พิมพ์หรือวาง 24 คำตามลำดับ แล้วกู้กุญแจเข้าเครื่องนี้",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/import-seed",
        bottom = {
            // While words are typed the grid needs the room. Once the 24th is committed the button is
            // wanted even if the keyboard has not gone away.
            if (keyboardUp && !draft.committed) {
                // The warning under the grid is behind the keyboard, so it shows here while words are typed.
                SeedStatusRow(draft, pinned = true) { sheet = ImportSheet.Passphrase }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryCta("นำเข้ากุญแจ", "cta_import_seed", ::restore, enabled = draft.canImport)
                if (draft.passphrase.isEmpty()) {
                    TextButton(
                        onClick = { sheet = ImportSheet.Passphrase },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp).testTag("seed_passphrase"),
                    ) {
                        Text("ใส่ passphrase", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) {
        // Penpot sets the grid 59dp under the subtitle (1B.4 y=236); the scaffold leaves 20.
        Spacer(modifier = Modifier.height(39.dp))
        SeedGrid(draft, engaged, taps) {
            draft.focus(it)
            engaged = true
            taps += 1
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (!keyboardUp || draft.committed) SeedStatusRow(draft) { sheet = ImportSheet.Passphrase }
        session.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }

    when (sheet) {
        ImportSheet.Passphrase -> PassphraseSheet(draft) { sheet = null }
        ImportSheet.Fingerprint -> FingerprintSheet(
            session = session,
            onConfirm = {
                sheet = null
                onRestored()
            },
            onReject = {
                session.clear()
                sheet = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun SeedGrid(draft: ImportSeedDraft, engaged: Boolean, taps: Int, onSlot: (Int) -> Unit) {
    // Once the 24th word is accepted nothing is being edited: no active slot, no list, no field.
    val editing = engaged && !draft.committed
    val dropdown = if (editing) draft.dropdown else emptyList()
    val row = draft.active % ROWS
    val dropdownTop = 50 + ROW_PITCH * row
    val dropdownBottom = dropdownTop + DROPDOWN_ROW * dropdown.size
    val focus = remember { FocusRequester() }
    val reveal = remember { BringIntoViewRequester() }
    val density = LocalDensity.current.density

    LaunchedEffect(draft.active, editing, taps) {
        if (editing) runCatching { focus.requestFocus() }
    }
    // Keep the active row and its list above the keyboard.
    LaunchedEffect(draft.active, dropdown.size) {
        if (editing) {
            val rowTop = (16 + ROW_PITCH * row) * density
            reveal.bringIntoView(Rect(0f, rowTop, 1f, maxOf(rowTop + 30 * density, dropdownBottom * density)))
        }
    }

    // The box grows to hold the list, so its rows are inside the box's bounds and can be tapped.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxOf(GRID_HEIGHT, dropdownBottom).dp)
            .bringIntoViewRequester(reveal)
            .testTag("seed_grid"),
    ) {
        // The card pads 16dp each side and the three columns are 8dp apart.
        val slotWidth = (maxWidth - 48.dp) / 3
        val card = RoundedCornerShape(20.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRID_HEIGHT.dp)
                .clip(card)
                .background(MbWhite)
                .border(1.dp, MbBorder, card)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) { column ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(ROWS) { r ->
                        val i = column * ROWS + r
                        SeedSlot(
                            number = i + 1,
                            word = draft.slots[i],
                            active = editing && draft.active == i,
                            flagged = draft.flaggedSlot == i,
                            onTap = { onSlot(i) },
                            modifier = Modifier.testTag("seed_slot_$i"),
                        )
                    }
                }
            }
        }
        if (editing) {
            // One field serves every slot and only moves: the keyboard is never torn down between words.
            val column = draft.active / ROWS
            SeedInput(
                word = draft.slots[draft.active],
                slot = draft.active,
                taps = taps,
                flagged = draft.flaggedSlot == draft.active,
                focus = focus,
                onEdit = { draft.edit(it) },
                onNext = draft::next,
                onFocused = draft::onFieldFocused,
                modifier = Modifier
                    .offset(x = 16.dp + (slotWidth + 8.dp) * column + WORD_START, y = (16 + ROW_PITCH * row).dp)
                    .size(width = slotWidth - WORD_START - 6.dp, height = 30.dp),
            )
        }
        if (dropdown.isNotEmpty()) {
            SeedDropdown(dropdown, draft::accept, Modifier.padding(top = dropdownTop.dp))
        }
    }
}

// A slot's word starts after 10dp of padding, a 14dp number and a 6dp gap.
private val WORD_START = 30.dp

// Penpot sets the word at 14.5 in Anuphan. Kanit is wider, and an 8-letter word must not be cut off.
private fun wordStyle(ink: Color) = TextStyle(color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun SeedSlot(
    number: Int,
    word: String,
    active: Boolean,
    flagged: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val frame = when {
        flagged -> Modifier.border(1.dp, MbEyebrow, shape)
        active -> Modifier.border(1.5.dp, MbNavy, shape)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(shape)
            .background(if (flagged) MbIconWash else if (active) MbWhite else MbPage)
            .then(frame)
            .clickable(onClick = onTap)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            color = MbSlate,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        // The active slot's word is the input on top of it.
        if (!active) Text(text = word, style = wordStyle(if (flagged) MbEyebrow else MbNavyInk), maxLines = 1)
    }
}

@Composable
private fun SeedInput(
    word: String,
    slot: Int,
    taps: Int,
    flagged: Boolean,
    focus: FocusRequester,
    onEdit: (String) -> Unit,
    onNext: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A slot, or a tap on it, starts with its whole word selected: typing replaces it.
    var value by remember(slot, taps) { mutableStateOf(TextFieldValue(word, TextRange(0, word.length))) }
    // Typing writes the field and the draft in one event, so they only differ here when the draft
    // acted alone (a tapped suggestion on the 24th slot). This is a check, not an effect: an effect
    // would run later with a stale word and overwrite the keystrokes typed in between.
    if (!value.text.equals(word, ignoreCase = true)) value = TextFieldValue(word, TextRange(word.length))
    // Words must not reach a keyboard dictionary: no autocorrect, an ASCII keyboard.
    BasicTextField(
        value = value,
        onValueChange = {
            // A caret move or a focus change reports the same text: that is not an edit, and it
            // must not end the entry or use up the replace-on-first-key.
            val changed = it.text != value.text
            value = it
            if (changed) onEdit(it.text)
        },
        singleLine = true,
        textStyle = wordStyle(if (flagged) MbEyebrow else MbNavyInk),
        cursorBrush = SolidColor(MbNavy),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            // The last slot ends the entry, so its key says Done.
            imeAction = if (slot == LAST_SLOT) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
        decorationBox = { field ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { field() }
        },
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .testTag("seed_input"),
    )
}

@Composable
private fun SeedDropdown(words: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .dropShadow(shape, Shadow(radius = 24.dp, color = Color.Black, offset = DpOffset(0.dp, 8.dp), alpha = 0.12f))
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape),
    ) {
        words.forEachIndexed { n, word ->
            // The first row is what Next would pick.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DROPDOWN_ROW.dp)
                    .background(if (n == 0) MbPick else if (n % 2 == 1) MbPage else MbWhite)
                    .clickable { onPick(word) }
                    .padding(horizontal = 14.dp)
                    .testTag("seed_suggestion_$n"),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(word, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SeedStatusRow(draft: ImportSeedDraft, pinned: Boolean = false, onEditPassphrase: () -> Unit) {
    val problem = when (draft.status) {
        is SeedStatus.InvalidWord -> "ไม่อยู่ในรายการคำ BIP39 · ตรวจการสะกดแล้วลองใหม่"
        SeedStatus.InvalidChecksum -> "Checksum ไม่ถูกต้อง"
        else -> null
    }
    val card = RoundedCornerShape(14.dp)
    when {
        problem != null -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (pinned) 8.dp else 0.dp)
                .clip(card)
                .background(MbIconWash)
                .padding(horizontal = 16.dp, vertical = 13.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("seed_warning"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(problem, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        draft.passphrase.isNotEmpty() && !pinned -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(card)
                .background(MbWhite)
                .border(1.dp, MbBorder, card)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("seed_passphrase_chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(MbGreenWash), contentAlignment = Alignment.Center) {
                Image(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Passphrase · ตั้งแล้ว", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "แก้ไข",
                color = MbNavy,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onEditPassphrase).padding(4.dp).testTag("seed_passphrase_edit"),
            )
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassphraseSheet(draft: ImportSeedDraft, onDone: () -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        modifier = Modifier.testTag("sheet_passphrase"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).imePadding()) {
            Text("Passphrase (คำที่ 25)", color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "ถ้าตอนสร้างกระเป๋าเดิมคุณตั้ง passphrase ไว้ ต้องใส่ให้ตรงทุกตัวอักษร ถ้าไม่ได้ตั้ง กดข้ามได้เลย",
                color = MbBody,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            val field = RoundedCornerShape(16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(field)
                    .background(MbPage)
                    .border(1.5.dp, MbNavy, field)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft.passphrase,
                    onValueChange = draft::editPassphrase,
                    singleLine = true,
                    textStyle = TextStyle(color = MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(MbNavy),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (draft.passphrase.isNotEmpty()) onDone() }),
                    modifier = Modifier.weight(1f).testTag("seed_passphrase_field"),
                )
                Image(
                    painterResource(Res.drawable.ic_eye),
                    contentDescription = "แสดง passphrase",
                    modifier = Modifier.size(24.dp).clickable { reveal = !reveal }.testTag("seed_passphrase_eye"),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "ใส่ได้เฉพาะตัวอักษรอังกฤษ ตัวเลข และสัญลักษณ์",
                color = if (draft.passphraseRejected) MbEyebrow else MbBody,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.testTag("seed_passphrase_hint"),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "ผิดแม้ตัวเดียวจะได้กระเป๋าอีกใบที่ว่างเปล่า · เงินไม่หาย แต่ mebit ตรวจให้ไม่ได้",
                color = MbEyebrow,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MbIconWash)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryCta("ใช้ passphrase นี้", "cta_passphrase_use", onDone, enabled = draft.passphrase.isNotEmpty())
            TextButton(
                onClick = {
                    draft.clearPassphrase()
                    onDone()
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cta_passphrase_none"),
            ) {
                Text("ไม่มี passphrase", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FingerprintSheet(session: OnboardingSession, onConfirm: () -> Unit, onReject: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onReject,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        modifier = Modifier.testTag("sheet_fingerprint"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("ตรวจ fingerprint ก่อนไปต่อ", color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MbPage).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = session.fingerprint.uppercase(),
                    color = MbNavy,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.testTag("fingerprint"),
                )
                Text(session.derivationPath, color = MbSlate, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "เทียบกับกระเป๋าเดิมของคุณ (ถ้ามีค่านี้) ถ้าไม่ตรง แปลว่า passphrase หรือ 24 คำยังไม่ถูก",
                color = MbBody,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryCta("ตรงกัน · ไปต่อ", "cta_fingerprint_ok", onConfirm)
            TextButton(
                onClick = onReject,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cta_fingerprint_reject"),
            ) {
                Text("ไม่ตรง · แก้ไข", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
