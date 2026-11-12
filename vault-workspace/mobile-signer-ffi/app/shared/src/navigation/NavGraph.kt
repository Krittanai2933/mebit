package com.mebit.app.navigation

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.BiometricResult
import com.mebit.app.Place
import com.mebit.app.data.AppLockState
import com.mebit.app.data.CreateWalletDraft
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.OwnerCheck
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.Replacement
import com.mebit.app.data.SaveState
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.StartNotice
import com.mebit.app.data.VaultDraft
import com.mebit.app.data.VaultSend
import com.mebit.app.data.keySigners
import com.mebit.app.data.vaultGuardKey
import com.mebit.app.data.OpenWallet
import com.mebit.app.data.WalletRemoval
import com.mebit.app.data.Wallets
import com.mebit.app.data.addRefusalLine
import com.mebit.app.data.ownKey
import com.mebit.app.data.unopenedNotice
import com.mebit.app.data.vaultsUsing
import com.mebit.app.data.ffiImportSeedDraft
import com.mebit.app.data.liveQuoteCache
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.keys.AddKeyScreen
import com.mebit.app.feature.keys.HardwareWalletScreen
import com.mebit.app.feature.keys.KeyringScreen
import com.mebit.app.feature.keys.PairMebitOtherScreen
import com.mebit.app.feature.keys.T13UnsupportedModelScreen
import com.mebit.app.feature.keys.T14VerifyMismatchScreen
import com.mebit.app.feature.keys.T15KeyringEmptyScreen
import com.mebit.app.feature.keys.T15UnverifiedFirstScreen
import com.mebit.app.feature.keys.VerifyKeyScreen
import com.mebit.app.feature.onboarding.AppLockScreen
import com.mebit.app.feature.onboarding.AddressTypeScreen
import com.mebit.app.feature.onboarding.BackupWordsScreen
import com.mebit.app.feature.onboarding.ImportSeedScreen
import com.mebit.app.feature.onboarding.KeyCreatedScreen
import com.mebit.app.feature.onboarding.KeySourceScreen
import com.mebit.app.feature.onboarding.LockMode
import com.mebit.app.feature.onboarding.PinSheet
import com.mebit.app.feature.onboarding.SpendingPinScreen
import com.mebit.app.feature.onboarding.VerifyWordsScreen
import com.mebit.app.feature.onboarding.WelcomeScreen
import com.mebit.app.feature.vault.ChooseKeysScreen
import com.mebit.app.feature.vault.PolicyScreen
import com.mebit.app.feature.vault.SpendingCard
import com.mebit.app.feature.vault.ReviewScreen
import com.mebit.app.feature.vault.ScriptTypeScreen
import com.mebit.app.feature.vault.T16InvalidMnScreen
import com.mebit.app.feature.vault.T16PolicyDecayingScreen
import com.mebit.app.feature.vault.T17CountMismatchScreen
import com.mebit.app.feature.vault.T18BackendErrorScreen
import com.mebit.app.feature.vault.T20VaultActionsScreen
import com.mebit.app.feature.vault.VaultCreatedScreen
import com.mebit.app.feature.vault.VaultDetailScreen
import com.mebit.app.feature.vault.VaultListScreen
import com.mebit.app.feature.send.keyDeleteItem
import com.mebit.app.feature.send.S1HomeSingleSigScreen
import com.mebit.app.feature.send.S2ReceiveBackupGateScreen
import com.mebit.app.feature.send.S3ReceiveScreen
import com.mebit.app.feature.send.S4SendScreen
import com.mebit.app.feature.send.S5ReviewSignScreen
import com.mebit.app.feature.send.S6SentScreen
import com.mebit.app.feature.send.S7HistoryScreen
import com.mebit.app.feature.send.S8TxDetailScreen
import com.mebit.app.feature.send.ChooseWalletScreen
import com.mebit.app.feature.send.DEMO_TX
import com.mebit.app.feature.send.VaultReceiveScreen
import com.mebit.app.feature.send.REMOVE_FAILED_LINE
import com.mebit.app.feature.send.WalletChoice
import com.mebit.app.feature.send.WalletLine
import com.mebit.app.feature.send.vaultLine
import com.mebit.app.feature.send.removeReason
import com.mebit.app.feature.send.walletDeleteItem
import com.mebit.app.feature.send.fiatLine
import com.mebit.app.feature.send.homeBalanceText
import com.mebit.app.feature.send.walletChoices
import com.mebit.app.feature.send.CollectSignaturesScreen
import com.mebit.app.feature.send.VaultReviewScreen
import com.mebit.app.feature.send.VaultSendScreen
import com.mebit.app.rememberBiometrics
import com.mebit.app.rememberIsDebugBuild
import com.mebit.app.ui.components.LocalDebugLinkLift
import com.mebit.app.ui.components.MebitBottomBar
import com.mebit.app.ui.components.bottomBarHeight
import com.mebit.app.ui.components.coversScreen
import com.mebit.app.ui.theme.MbPage
import com.mebit.ffi.signer.HotTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ImplementedRoutes = MovieRoute.entries.toSet()

/** One screen on the Back stack and the wallet it belongs to. A wallet switch is a push of a Stop with another place, and Back undoes it. */
data class Stop(val route: MovieRoute, val place: Place)

/** [WalletGate] passes the wallets, the Stop a saved wallet opens at and Welcome's [startNotice]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MebitNavHost(
    wallets: Wallets,
    start: Stop,
    startNotice: StartNotice? = null,
    vaults: SavedVaults = remember { SavedVaults.memoryOnly() },
) {
    val backStack = remember { mutableStateListOf(start) }
    val current = backStack.last()
    // The wallet in use is the wallet of the top Stop. No other state says which one it is, so nothing can disagree with the screen.
    val open = wallets.at(current.place)
    val session = open.session
    val singleSig = open.chain
    val sendDraft = open.sendDraft
    var catalogOpen by remember { mutableStateOf(false) }
    val keyring = remember(vaults, wallets) { KeyringStore(book = vaults, ownKeys = { wallets.ownKeys() }) }
    val vaultDraft = remember(vaults) { VaultDraft(vaults) }
    // One draft per wizard run: it lives while Policy is on the Back stack.
    val createDraft = remember(vaults, inCreateFlow(backStack)) { CreateWalletDraft(vaults) }
    val quotes = remember { liveQuoteCache() }
    // One send from one several-key wallet, made new by every way into 5.1, so a payment and its signatures never outlive the visit.
    var vaultSend by remember { mutableStateOf<VaultSend?>(null) }
    // Import Seed's words and passphrase. They outlive the detour to Address Type and back, and a fresh draft replaces them once
    // the import goes on to the lock step or the user leaves Import Seed by its back chip. Memory only: never rememberSaveable,
    // which Android writes to disk.
    var importDraft by remember { mutableStateOf(ffiImportSeedDraft()) }
    val phone = rememberBiometrics()
    // The gates' owner check, which is the phone's own or, on a phone without one, the Spending PIN (open item 79).
    val biometrics = remember(phone, session) { OwnerCheck(phone, session) }
    val appLock = remember { AppLockState() }
    val scope = rememberCoroutineScope()
    var addLine by remember { mutableStateOf<String?>(null) }
    var removeLine by remember { mutableStateOf<String?>(null) }
    // Starts as Keyring so the debug catalog can open Verify Key without passing Key Source.
    var keyFlow by remember { mutableStateOf(KeyFlow.Keyring) }
    var verifyRetryRoute by remember { mutableStateOf(MovieRoute.HardwareWallet) }
    var unsupportedModel by remember { mutableStateOf<String?>(null) }
    // The row a tap opened, whole, so the detail draws at once. The catalog opens the demo row until a tap.
    var openedTx by remember { mutableStateOf(DEMO_TX) }

    // More than a minute away locks the app again, once the user turned the lock on or where the Spending PIN guards it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { appLock.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { appLock.onForeground(biometrics.locksWhenAway) }

    LaunchedEffect(current) {
        addLine = null
        removeLine = null
    }
    // Keyed on words too, so a wallet added in this run is noted once its key exists.
    LaunchedEffect(current.place, session.words.isNotEmpty()) { if (session.words.isNotEmpty()) wallets.noteUsed(current.place) }
    // This host exists only once the wallets are open or there are none, so the book is read after the owner check. Keyed on the owners so keys added before a Create in this run are saved when it lands.
    LaunchedEffect(vaults, wallets.vaultOwners()) { withContext(Dispatchers.Default) { vaults.sync() } }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch(Dispatchers.Default) { vaults.sync() } }

    // The one applier of a computed Back stack. The rules live in BackStack.kt.
    fun land(next: List<Stop>) {
        if (next == backStack) return
        backStack.clear()
        backStack.addAll(next)
    }

    fun navigate(route: MovieRoute, place: Place = backStack.last().place) {
        keyFlow = keyFlowAfter(route, keyFlow)
        land(stackAfterOpen(backStack, Stop(route, place)))
    }

    fun returnToKeySource(sourceRoute: MovieRoute) {
        val stops = backStack.toList()
        // keyRetryBackStack keeps a prefix of the routes and may add one on top, so each kept route keeps its Stop's place.
        land(keyRetryBackStack(stops.map { it.route }, sourceRoute).mapIndexed { i, route -> Stop(route, stops.getOrElse(i) { stops.last() }.place) })
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // First setup picks the address type after the key is made or imported, then goes on to the lock step.
    fun chooseAddressType() = navigate(MovieRoute.AddressType)

    // Back to the latest [route] on the stack, dropping every screen above it.
    fun backTo(route: MovieRoute) {
        val at = backStack.indexOfLast { it.route == route }
        if (at >= 0) while (backStack.lastIndex > at) backStack.removeAt(backStack.lastIndex)
    }

    // Every add ends here: back to Choose Keys when the add began there, else on [otherwise].
    fun finishAdd(otherwise: MovieRoute) {
        val next = stackAfterAdd(backStack, otherwise)
        keyFlow = keyFlowAfter(next.last().route, keyFlow)
        land(next)
    }

    // App Lock is one setting for the phone, so only the wallet that made the phone's first choice runs this step.
    // After the first wallet's create or import comes App Lock on a phone with an owner check, else the Spending PIN, set before the list.
    // A later wallet's setup ends on the list here.
    fun lockStep() = when {
        wallets.listed.size >= 2 -> finishAdd(endSetup(session))
        biometrics.needsPin -> navigate(MovieRoute.SpendingPin)
        else -> navigate(MovieRoute.AppLock)
    }

    // The create flow goes on to the address type. A later backup returns to the screen that opened it.
    fun afterBackup() = land(stackAfterBackup(backStack, session.inFirstSetup))

    // A wallet that has not finished setup shows no address and no payment. Any way into it goes back to Address Type.
    fun openWallet(place: Place, route: (OpenWallet) -> MovieRoute) {
        val w = wallets.at(place)
        navigate(if (w.session.addressTypeConfirmed) route(w) else MovieRoute.AddressType, place)
    }

    fun openReceive(choice: WalletChoice) = when (choice) {
        is WalletChoice.Spending -> openWallet(choice.place) { if (it.session.backupConfirmed) MovieRoute.S3Receive else MovieRoute.S2ReceiveBackupGate }
        is WalletChoice.Vault -> {
            vaultDraft.selectVault(choice.vault.id)
            navigate(MovieRoute.VaultReceive)
        }
    }

    // A wallet the core cannot read makes no send, and 5.1 says so.
    fun startVaultSend(vault: CreatedVault?) {
        vaultSend = vault?.let { runCatching { VaultSend(it, wallets.vaultPayments) }.getOrNull() }
        navigate(MovieRoute.M1SendVault)
    }

    fun openSend(choice: WalletChoice) = when (choice) {
        is WalletChoice.Spending -> openWallet(choice.place) {
            it.sendDraft.reset() // the chosen wallet's own draft, never the one on screen
            MovieRoute.S4Send
        }
        is WalletChoice.Vault -> {
            vaultDraft.selectVault(choice.vault.id)
            startVaultSend(choice.vault)
        }
    }

    // A real key reads Demo until its first bind ends, so its balance stays a dash until then, as on Home.
    fun spendingKnown(w: OpenWallet) = w.chain.balanceKnown && !(w.session.words.size >= 12 && w.chain.mode == WalletMode.Demo)

    // Add Key's two wallet rows and Policy's single-sig start land here.
    fun addWallet(import: Boolean) {
        addLine = wallets.addRefusal()?.let(::addRefusalLine)
        val w = wallets.beginAdd() ?: return
        if (import) {
            importDraft = ffiImportSeedDraft()
            navigate(MovieRoute.ImportSeed, w.place)
        } else {
            w.session.generateThisDevice()
            if (w.session.lastError == null && w.session.words.isNotEmpty()) navigate(MovieRoute.KeyCreated, w.place) else addLine = w.session.lastError
        }
    }

    fun removeWallet(w: OpenWallet) {
        scope.launch {
            removeLine = null
            when (wallets.remove(w, vaults, removeReason(w.session.walletName)) { next ->
                // Runs in the snapshot that drops the wallet, so no frame shows a Stop whose wallet is gone. The list keeps the wallet in use unless it was the one removed.
                navigate(MovieRoute.VaultList, backStack.last().place.takeIf { it != w.place } ?: next.place)
            }) {
                WalletRemoval.Removed, is WalletRemoval.Refused -> Unit // a refused owner check leaves everything as it was
                WalletRemoval.InVault, WalletRemoval.NotErased -> removeLine = REMOVE_FAILED_LINE
            }
        }
    }

    // Once onboarding is over the words open past the Spending PIN where it guards the session, and as before elsewhere.
    fun openWords() {
        scope.launch { if (biometrics.guardWords("ดู 24 คำสำรอง")) navigate(MovieRoute.BackupWords) }
    }

    val openTx: (HotTx) -> Unit = { tx ->
        openedTx = tx
        navigate(MovieRoute.S8TxDetail)
    }

    // Posting a guarded payment's bytes again asks the owner as signing does, so a stranger cannot push out a payment the owner gave up on.
    val confirmRepost: suspend () -> Boolean = { appLock.confirm(biometrics, session.appLockEnabled, "ยืนยันการส่งรายการเดิมอีกครั้ง") }

    // A fee bump or a cancel signs a new transaction, so it asks the owner as signing does, with its own reason.
    val confirmReplacement: suspend (Replacement) -> Boolean = { kind ->
        val reason = when (kind) {
            Replacement.FeeBump -> "ยืนยันการเพิ่มค่าธรรมเนียมแล้วส่งใหม่"
            Replacement.Cancel -> "ยืนยันการยกเลิกรายการ"
        }
        appLock.confirm(biometrics, session.appLockEnabled, reason)
    }

    val onBack: (() -> Unit)? = if (backStack.size > 1) ({ goBack() }) else null
    // The link is the only caller, and it is gone in a release build. This keeps the sheet shut even if another caller appears.
    val debugBuild = rememberIsDebugBuild()
    val openCatalog = { if (debugBuild) catalogOpen = true }

    val bar = current.route.bar
    CompositionLocalProvider(LocalDebugLinkLift provides if (bar != null) 22.dp else 0.dp) {
        Box(Modifier.fillMaxSize().background(MbPage)) {
            val inset = if (bar != null) bottomBarHeight() else 0.dp
            Box(Modifier.fillMaxSize().padding(bottom = inset).consumeWindowInsets(PaddingValues(bottom = inset))) {
                // Keyed on the place, so a wallet's screens never inherit another wallet's remembered state, such as its retries, its open key details and its pull.
                key(current.place) { when (current.route) {
                    MovieRoute.Welcome -> WelcomeScreen(
                        onCreate = { navigate(MovieRoute.KeySource) },
                        onImport = { navigate(MovieRoute.ImportSeed) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                        notice = startNotice,
                    )
                    MovieRoute.KeySource -> KeySourceScreen(
                        session = session,
                        onPhone = { navigate(MovieRoute.KeyCreated) },
                        onHardware = { navigate(MovieRoute.HardwareWallet) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.ImportSeed -> ImportSeedScreen(
                        session = session,
                        draft = importDraft,
                        // An import skips backup: the user typed the words from their own record.
                        onRestored = { chooseAddressType() },
                        onBack = onBack?.let { back ->
                            {
                                importDraft = ffiImportSeedDraft()
                                back()
                            }
                        },
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.KeyCreated -> KeyCreatedScreen(
                        session = session,
                        onBackup = { navigate(MovieRoute.BackupWords) },
                        onSkip = { chooseAddressType() },
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.BackupWords -> BackupWordsScreen(
                        session = session,
                        onVerifiedReady = { navigate(MovieRoute.VerifyWords) },
                        onSkip = { afterBackup() },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.VerifyWords -> VerifyWordsScreen(
                        session = session,
                        onVerified = { afterBackup() },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.AddressType -> AddressTypeScreen(
                        session = session,
                        onContinue = {
                            importDraft = ffiImportSeedDraft()
                            lockStep()
                        },
                        // A cold start restores Address Type alone, so Backup Words is pushed when the stack does not hold it.
                        onEditPassphrase = { if (backStack.any { it.route == MovieRoute.BackupWords }) backTo(MovieRoute.BackupWords) else navigate(MovieRoute.BackupWords) },
                        onRejectImport = { backTo(MovieRoute.ImportSeed) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.AppLock -> AppLockScreen(
                        session = session,
                        biometrics = biometrics,
                        mode = LockMode.Enable,
                        // A lock removed while this screen showed leaves the phone with no owner check, so its PIN comes before the list.
                        onDone = { if (biometrics.needsPin) navigate(MovieRoute.SpendingPin) else finishAdd(endSetup(session)) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.SpendingPin -> SpendingPinScreen(
                        session = session,
                        onDone = { finishAdd(endSetup(session)) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.AddKey -> AddKeyScreen(
                        line = addLine,
                        onThisDevice = { addWallet(import = false) },
                        onMebitOther = { navigate(MovieRoute.PairMebitOther) },
                        onHardware = { navigate(MovieRoute.HardwareWallet) },
                        onImport = { addWallet(import = true) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.PairMebitOther -> PairMebitOtherScreen(
                        store = keyring,
                        onPaired = { navigate(MovieRoute.VerifyKey) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.HardwareWallet -> HardwareWalletScreen(
                        store = keyring,
                        onEnrolled = { navigate(MovieRoute.VerifyKey) },
                        onUnsupported = { model ->
                            unsupportedModel = model
                            navigate(MovieRoute.T13UnsupportedModel)
                        },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.VerifyKey -> VerifyKeyScreen(
                        store = keyring,
                        onMatched = { finishAdd(MovieRoute.Keyring) },
                        onMismatch = { source ->
                            verifyRetryRoute = routeBeforeVerify(source, keyFlow)
                            navigate(MovieRoute.T14VerifyMismatch)
                        },
                        onReconnect = { source -> returnToKeySource(routeBeforeVerify(source, keyFlow)) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.Keyring -> KeyringScreen(
                        store = keyring,
                        deleteItem = { key ->
                            keyDeleteItem(
                                ownWalletName = wallets.listed.firstOrNull { it.session.ownKey()?.id == key.id }?.session?.walletName,
                                usingWallets = if (vaults.judged) vaults.current.vaultsUsing(key.fingerprint).map { it.name } else null,
                            )
                        },
                        onAddKey = { navigate(MovieRoute.AddKey) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.Policy -> PolicyScreen(
                        draft = createDraft,
                        line = addLine,
                        onMultisig = { navigate(MovieRoute.ChooseKeys) },
                        onSingleSig = { addWallet(import = false) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.ChooseKeys -> ChooseKeysScreen(
                        draft = createDraft,
                        keys = keyring.keys,
                        wallets = vaults.current.vaults,
                        onAddKey = { navigate(MovieRoute.AddKey) },
                        onContinue = { navigate(MovieRoute.ScriptType) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.ScriptType -> ScriptTypeScreen(
                        network = session.network,
                        onContinue = { navigate(MovieRoute.Review) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.Review -> ReviewScreen(
                        draft = createDraft,
                        keys = keyring.keys,
                        onCreate = { plan ->
                            val made = createDraft.create(plan)
                            if (made == null) {
                                navigate(MovieRoute.T18BackendError)
                            } else {
                                vaultDraft.noteCreated(made)
                                land(stackAfterCreate(backStack))
                            }
                        },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.VaultCreated -> VaultCreatedScreen(
                        vault = vaultDraft.selectedVault(),
                        onOpenWallets = { navigate(MovieRoute.VaultList) },
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.VaultList -> {
                        // Keyed on the book, so a vault it loads after the list first drew, or one made since, is read on this visit.
                        LaunchedEffect(vaultDraft.created) { wallets.refreshBalances(vaultDraft.created, first = current.place) }
                        VaultListScreen(
                            draft = vaultDraft,
                            vaults = vaults,
                            spending = wallets.listed.map { w ->
                                val fingerprint = w.session.fingerprint
                                val delete = walletDeleteItem(
                                    listedWallets = wallets.listed.size,
                                    saved = w.session.saved == SaveState.Saved,
                                    usesKey = vaults.usesKey(fingerprint),
                                    vaultName = vaults.current.vaultsUsing(fingerprint).firstOrNull()?.name,
                                )
                                SpendingCard(w.place, w.session.walletName, w.chain.balanceSats, spendingKnown(w), w.session.backupConfirmed, delete)
                            },
                            vaultSats = wallets::vaultSats,
                            inUse = session,
                            unopenedNotice = unopenedNotice(wallets.unopened),
                            removeLine = removeLine,
                            onOpenSpending = { place -> openWallet(place) { MovieRoute.S1HomeSingleSig } },
                            onRemoveSpending = { place -> removeWallet(wallets.at(place)) },
                            onOpenDetail = { navigate(MovieRoute.VaultDetail) },
                            // The phone's own owner sheet, which a one-key wallet's removal shows as it opens the wallet's saved copy, and never the Spending PIN.
                            onRemoveVault = { vault ->
                                scope.launch {
                                    removeLine = null
                                    if (phone.authenticate(removeReason(vault.name), allowPasscode = true) != BiometricResult.Success) return@launch
                                    if (!vaultDraft.remove(vault.id)) removeLine = REMOVE_FAILED_LINE
                                }
                            },
                            onCreate = { navigate(MovieRoute.Policy) },
                            onOpenCatalog = openCatalog,
                        )
                    }
                    MovieRoute.VaultDetail -> VaultDetailScreen(
                        vault = vaultDraft.selectedVault(),
                        wallets = wallets,
                        keyring = keyring,
                        quotes = quotes,
                        onReceive = { navigate(MovieRoute.VaultReceive) },
                        onSend = { startVaultSend(vaultDraft.selectedVault()) },
                        // The Wallet list is a tab, so it opens as the only entry, and a detail a vault send lands on closes to the list.
                        onClose = { navigate(MovieRoute.VaultList) },
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T13UnsupportedModel -> T13UnsupportedModelScreen(
                        model = unsupportedModel,
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T14VerifyMismatch -> T14VerifyMismatchScreen(
                        onBack = { returnToKeySource(verifyRetryRoute) },
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T15KeyringEmpty -> T15KeyringEmptyScreen(
                        onAddKey = { navigate(MovieRoute.AddKey) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T15UnverifiedFirst -> T15UnverifiedFirstScreen(
                        onVerify = { navigate(MovieRoute.VerifyKey) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T16PolicyDecaying -> T16PolicyDecayingScreen(
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T16InvalidMn -> T16InvalidMnScreen(
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T17CountMismatch -> T17CountMismatchScreen(
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T18BackendError -> T18BackendErrorScreen(
                        onRetry = { navigate(MovieRoute.Review) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.T20VaultActions -> T20VaultActionsScreen(
                        draft = vaultDraft,
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S1HomeSingleSig -> S1HomeSingleSigScreen(
                        wallet = singleSig,
                        session = session,
                        quotes = quotes,
                        onReceive = { openReceive(WalletChoice.Spending(current.place)) },
                        onSend = { openSend(WalletChoice.Spending(current.place)) },
                        onVault = { navigate(MovieRoute.Keyring) },
                        onHistory = { navigate(MovieRoute.S7History) },
                        onOpenTx = openTx,
                        onOpenCatalog = openCatalog,
                        onChangePin = if (session.pin.isSet) ({ scope.launch { biometrics.changePin("เปลี่ยน PIN") } }) else null,
                    )
                    MovieRoute.S7History -> S7HistoryScreen(
                        wallet = singleSig,
                        onOpenTx = openTx,
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S8TxDetail -> S8TxDetailScreen(
                        tx = openedTx,
                        wallet = singleSig,
                        session = session,
                        quotes = quotes,
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S2ReceiveBackupGate -> S2ReceiveBackupGateScreen(
                        wallet = singleSig,
                        session = session,
                        quotes = quotes,
                        // Backed up, the gate gives its place to Receive, so Back from Receive never lands on a gate that forwards again.
                        onBackedUp = { backStack[backStack.lastIndex] = backStack.last().copy(route = MovieRoute.S3Receive) },
                        onGoBackup = { openWords() },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S3Receive -> S3ReceiveScreen(
                        wallet = singleSig,
                        session = session,
                        onSend = { openSend(WalletChoice.Spending(current.place)) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S4Send -> S4SendScreen(
                        wallet = singleSig,
                        session = session,
                        draft = sendDraft,
                        quotes = quotes,
                        confirmRepost = confirmRepost,
                        confirmReplacement = confirmReplacement,
                        onContinue = { navigate(MovieRoute.S5ReviewSign) },
                        onReceive = { openReceive(WalletChoice.Spending(current.place)) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S5ReviewSign -> S5ReviewSignScreen(
                        wallet = singleSig,
                        session = session,
                        draft = sendDraft,
                        quotes = quotes,
                        confirm = { appLock.confirm(biometrics, session.appLockEnabled, "ยืนยันการเซ็นส่งเงิน") },
                        confirmRepost = confirmRepost,
                        confirmReplacement = confirmReplacement,
                        onSigned = { navigate(MovieRoute.S6SentSlip) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S6Sent -> S6SentScreen(
                        wallet = singleSig,
                        draft = sendDraft,
                        quotes = quotes,
                        walletName = session.walletName,
                        variant = "default",
                        onHome = { navigate(MovieRoute.S1HomeSingleSig) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S6SentSlip -> S6SentScreen(
                        wallet = singleSig,
                        draft = sendDraft,
                        quotes = quotes,
                        walletName = session.walletName,
                        variant = "slip",
                        onHome = { navigate(MovieRoute.S1HomeSingleSig) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S6SentBlock -> S6SentScreen(
                        wallet = singleSig,
                        draft = sendDraft,
                        quotes = quotes,
                        walletName = session.walletName,
                        variant = "block",
                        onHome = { navigate(MovieRoute.S1HomeSingleSig) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.S6SentPoster -> S6SentScreen(
                        wallet = singleSig,
                        draft = sendDraft,
                        quotes = quotes,
                        walletName = session.walletName,
                        variant = "poster",
                        onHome = { navigate(MovieRoute.S1HomeSingleSig) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.M1SendVault -> VaultSendScreen(
                        send = vaultSend,
                        quotes = quotes,
                        confirmRepost = confirmRepost,
                        otherGuardKeys = { vaultDraft.created.filter { it.address != vaultSend?.vault?.address }.map(::vaultGuardKey) },
                        onReview = { navigate(MovieRoute.M2CollectSignatures) },
                        onReceive = { vaultSend?.vault?.let { openReceive(WalletChoice.Vault(it)) } },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.M2CollectSignatures -> CollectSignaturesScreen(
                        send = vaultSend,
                        signers = vaultSend?.let { keySigners(it.vault, wallets.listed.map { w -> w.session }, keyring.keys) }.orEmpty(),
                        keyring = keyring.keys,
                        quotes = quotes,
                        // The owner check of the wallet in use, once per signature, as S5 asks before it signs.
                        confirmSign = { appLock.confirm(biometrics, session.appLockEnabled, "ยืนยันการเซ็นส่งเงิน") },
                        onReview = { navigate(MovieRoute.M8ReviewVault) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.M8ReviewVault -> VaultReviewScreen(
                        send = vaultSend,
                        // The balance the list or the detail last read, so the review asks the chain nothing.
                        balanceSats = vaultSend?.vault?.let(wallets::vaultSats),
                        quotes = quotes,
                        onSent = {
                            vaultSend?.vault?.let { sent ->
                                wallets.forgetVaultRead(sent)
                                vaultDraft.selectVault(sent.id)
                                land(stackAfterVaultSend(backStack))
                            }
                        },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                    MovieRoute.ChooseWalletReceive, MovieRoute.ChooseWalletSend -> {
                        val send = current.route == MovieRoute.ChooseWalletSend
                        val quote = rememberFiatQuote(quotes)
                        LaunchedEffect(vaultDraft.created) { wallets.refreshBalances(vaultDraft.created) }
                        ChooseWalletScreen(
                            send = send,
                            choices = walletChoices(wallets.listed.map { it.place }, vaultDraft.created),
                            line = { choice ->
                                when (choice) {
                                    is WalletChoice.Spending -> {
                                        val w = wallets.at(choice.place)
                                        WalletLine(
                                            w.session.walletName,
                                            homeBalanceText(w.chain.balanceSats, spendingKnown(w), loading = false),
                                            baht = if (w.chain.mode == WalletMode.Live && w.chain.balanceKnown) fiatLine(w.chain.balanceSats, quote) else null,
                                        )
                                    }
                                    is WalletChoice.Vault -> vaultLine(choice.vault, wallets.vaultSats(choice.vault), quote)
                                }
                            },
                            onChoose = { if (send) openSend(it) else openReceive(it) },
                            onSwitch = { backStack[backStack.lastIndex] = current.copy(route = if (send) MovieRoute.ChooseWalletReceive else MovieRoute.ChooseWalletSend) },
                            onBack = onBack,
                            onOpenCatalog = openCatalog,
                        )
                    }
                    MovieRoute.VaultReceive -> VaultReceiveScreen(
                        vault = vaultDraft.selectedVault(),
                        onSend = { startVaultSend(vaultDraft.selectedVault()) },
                        onBack = onBack,
                        onOpenCatalog = openCatalog,
                    )
                } }
            }
            if (bar != null) {
                MebitBottomBar(
                    selected = bar,
                    onWallets = { navigate(MovieRoute.VaultList) },
                    onKeys = { navigate(MovieRoute.Keyring) },
                    onReceiveSend = { navigate(MovieRoute.ChooseWalletReceive) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (catalogOpen) {
        ModalBottomSheet(
            onDismissRequest = { catalogOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag("route_catalog"),
        ) {
            Text(
                text = "MOVIE PAGE ROUTES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.padding(bottom = 32.dp)) {
                items(MovieRoute.entries, key = { it.path }) { route ->
                    Text(
                        text = route.eyebrow + " · " + route.titleTh +
                            if (route in ImplementedRoutes) " ✓" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                catalogOpen = false
                                navigate(route)
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .testTag("catalog_" + route.path),
                    )
                }
            }
        }
    }

    if (appLock.locked) {
        // A phone the Spending PIN guards unlocks only through the PIN sheet drawn over this cover, never through a check it lacks.
        val pinLock = biometrics.usesPin
        // Covers everything, swallows taps, and goes away only on Face ID, the device passcode or the PIN.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MbPage)
                .coversScreen(),
        ) {
            if (!pinLock) {
                AppLockScreen(
                    session = session,
                    biometrics = biometrics,
                    mode = LockMode.Unlock,
                    onDone = { appLock.unlock() },
                    onBack = null,
                    onOpenCatalog = {},
                )
            }
        }
        if (pinLock) LaunchedEffect(Unit) { if (biometrics.unlock("ปลดล็อก mebit")) appLock.unlock() }
    }

    biometrics.asking?.let { PinSheet(it, session.pin) }
}
