package com.mebit.app.navigation

import com.mebit.app.Place
import com.mebit.app.data.OnboardingSession

/**
 * Pure. The Back stack after [stop] opens. A bar tab ([MovieRoute.tab], the Wallet list and the Keyring) becomes the only entry however it
 * was opened, so it never shows a back chip and nothing of a finished flow stays under it. The รับ / ส่ง switch's other side takes the
 * place of the screen it switches from ([SwitchSides]), so Back leaves the pair, however often it switched, instead of replaying each
 * switch. Any other screen goes on top, once. Returns [stack] itself when nothing changes, else a new list. [stack] is never empty.
 */
internal fun stackAfterOpen(stack: List<Stop>, stop: Stop): List<Stop> = when {
    stop.route.tab -> listOf(stop)
    stack.last() == stop -> stack
    switches(stack.last(), stop) -> stack.dropLast(1) + stop
    else -> stack + stop
}

/** The two sides of each รับ / ส่ง switch: a phone, hardware or several-key wallet's Receive (or its backup gate) and Send. */
private val SwitchSides = listOf(
    setOf(MovieRoute.S2ReceiveBackupGate, MovieRoute.S3Receive, MovieRoute.S4Send),
    setOf(MovieRoute.HardwareReceive, MovieRoute.HardwareSend),
    setOf(MovieRoute.VaultReceive, MovieRoute.M1SendVault),
)

/** Whether [to] is the other side of [from]'s switch, of the same wallet. Opening another wallet's Receive or Send stays a push. */
private fun switches(from: Stop, to: Stop): Boolean =
    from.place == to.place && from.hardware == to.hardware && SwitchSides.any { from.route in it && to.route in it }

/**
 * Pure. The Stop a plain open of [route] from [top] makes: a hardware wallet's screen ([MovieRoute.hardware]) stays on the wallet [top]
 * names, and any other screen names none, so no phone screen ever carries a hardware wallet along.
 */
internal fun stopFor(top: Stop, route: MovieRoute, place: Place = top.place): Stop = Stop(route, place, top.hardware.takeIf { route.hardware })

/**
 * Pure. Wallet Created 3.5.2 alone, for the hardware wallet [id] just saved. The wizard leaves the stack, as [stackAfterCreate] has it for
 * a several-key wallet, so nothing can reach Confirm Key and save the device's key again.
 */
internal fun stackAfterHardwareCreate(stack: List<Stop>, id: String): List<Stop> = listOf(Stop(MovieRoute.HardwareCreated, stack.last().place, id))

/** The screens of one payment, from the form to the signature, by the slip that takes their place once the payment is out. */
private val PaymentScreens = mapOf(
    MovieRoute.S6SentSlip to setOf(MovieRoute.S4Send, MovieRoute.S5ReviewSign),
    MovieRoute.HardwareSent to setOf(MovieRoute.HardwareSend, MovieRoute.HardwareReview, MovieRoute.HardwareSign),
)

/**
 * Pure. The [slip] 4.6 of the phone or hardware wallet on top of [stack], once its payment is out. The payment's screens leave, so Back
 * from the slip lands where the payment began, Home or a chooser, and never on a screen that could sign the same payment again.
 */
internal fun stackAfterSent(stack: List<Stop>, slip: MovieRoute): List<Stop> {
    val top = stack.last()
    val kept = stack.dropLastWhile { it.route in PaymentScreens.getValue(slip) }.ifEmpty { listOf(Stop(MovieRoute.VaultList, top.place)) }
    return kept + stopFor(top, slip)
}

/** Pure. The slip's way to its wallet's [home]: Back when that Home is right under it, else that Home on top. */
internal fun stackAfterSlipHome(stack: List<Stop>, home: MovieRoute): List<Stop> {
    val stop = stopFor(stack.last(), home)
    return if (stack.getOrNull(stack.lastIndex - 1) == stop) stack.dropLast(1) else stackAfterOpen(stack, stop)
}

/**
 * The end of every phone wallet's setup, the first wallet's after App Lock or the Spending PIN and a later wallet's after Address Type,
 * backed up or not, begun on the Keyring or anywhere else. The wallet leaves first setup here, because the list shows it from now on and a
 * vault may use its key, so its passphrase and address type are final. Names the Wallet list, which [stackAfterAdd] opens as the only entry
 * unless the add began on Choose Keys.
 */
internal fun endSetup(session: OnboardingSession): MovieRoute {
    session.endFirstSetup()
    return MovieRoute.VaultList
}

/**
 * Pure. Whether the create-wallet wizard is open, which is while its first screen, Policy, is on [stack]. NavGraph keeps one
 * CreateWalletDraft exactly while this holds: the + starts a fresh one, the Add Key detour from Choose Keys keeps it, and Back,
 * a tab and [stackAfterCreate] drop Policy and so the draft.
 */
internal fun inCreateFlow(stack: List<Stop>): Boolean = stack.any { it.route == MovieRoute.Policy }

/**
 * Pure. The Back stack once an add ends, by Confirm Key's match ([otherwise] is the Keyring) or by the end of a phone wallet's setup
 * ([otherwise] is the Wallet list). An add opened from Choose Keys, the Stop right under the last Add Key, returns there with every
 * Stop above it gone and each kept Stop on its own place, so the wizard is on screen again with its picks. Any other add opens
 * [otherwise], a tab, as before. [stack] is never empty.
 */
internal fun stackAfterAdd(stack: List<Stop>, otherwise: MovieRoute): List<Stop> {
    require(otherwise.tab) { "${otherwise.name} is not a tab" }
    val add = stack.indexOfLast { it.route == MovieRoute.AddKey }
    return if (add > 0 && stack[add - 1].route == MovieRoute.ChooseKeys) stack.take(add)
    else stackAfterOpen(stack, Stop(otherwise, stack.last().place))
}

/**
 * Pure. The Back stack once a several-key wallet's payment is out: every send screen, the review included, gone, and the slip (Penpot 5.9)
 * on the wallet's detail, so กลับหน้าหลัก and the system Back, which both go Back from the slip, land on the detail. A detail under the
 * send stays and the stack is cut back to it, else the detail goes on top of what opened the send, so nothing returns to a payment that
 * went. [stack] is never empty.
 */
internal fun stackAfterVaultSend(stack: List<Stop>): List<Stop> {
    val place = stack.last().place
    val below = stack.dropLastWhile { it.route == MovieRoute.M1SendVault || it.route == MovieRoute.M2CollectSignatures || it.route == MovieRoute.M3SignOnDevice || it.route == MovieRoute.M3SignOnPhone || it.route == MovieRoute.M8ReviewVault }
    val detail = below.indexOfLast { it.route == MovieRoute.VaultDetail }
    val home = if (detail >= 0) below.take(detail + 1) else stackAfterOpen(below.ifEmpty { listOf(Stop(MovieRoute.VaultList, place)) }, Stop(MovieRoute.VaultDetail, place))
    return home + Stop(MovieRoute.M9SentVault, place)
}

/**
 * Pure. Wallet Created alone, at the place the wizard ended on. The wizard leaves the stack, so Wallet Created has no back chip (Penpot 3.5
 * draws none) and nothing on the stack can reach Review and run "สร้าง Wallet" again.
 */
internal fun stackAfterCreate(stack: List<Stop>): List<Stop> = listOf(Stop(MovieRoute.VaultCreated, stack.last().place))

/**
 * Pure. The Back stack once Backup Words or Verify Words ends, by a verify or a skip. In first setup the flow goes on to Address Type.
 * A later backup returns to the screen that opened it, every Stop above it gone. With none under the backup screens it ends on the list.
 */
internal fun stackAfterBackup(stack: List<Stop>, inFirstSetup: Boolean): List<Stop> {
    val top = stack.last()
    if (inFirstSetup) return stackAfterOpen(stack, Stop(MovieRoute.AddressType, top.place))
    val opener = stack.indexOfLast { it.route != MovieRoute.BackupWords && it.route != MovieRoute.VerifyWords }
    return if (opener >= 0) stack.take(opener + 1) else stackAfterOpen(stack, Stop(MovieRoute.VaultList, top.place))
}
