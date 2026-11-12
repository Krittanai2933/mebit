package com.mebit.app.navigation

import com.mebit.app.data.OnboardingSession

/**
 * Pure. The Back stack after [stop] opens. A bar tab ([MovieRoute.tab], the Wallet list and the Keyring) becomes the only entry however it
 * was opened, so it never shows a back chip and nothing of a finished flow stays under it. Any other screen goes on top, once.
 * Returns [stack] itself when nothing changes, else a new list. [stack] is never empty.
 */
internal fun stackAfterOpen(stack: List<Stop>, stop: Stop): List<Stop> = when {
    stop.route.tab -> listOf(stop)
    stack.last() == stop -> stack
    else -> stack + stop
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
 * Pure. The Back stack once a several-key wallet's payment is out: every send screen gone and the wallet's detail on top. A detail under
 * the send stays and the stack is cut back to it, else the detail goes on top of what opened the send, so Back never returns to a payment
 * that went. [stack] is never empty.
 */
internal fun stackAfterVaultSend(stack: List<Stop>): List<Stop> {
    val place = stack.last().place
    val below = stack.dropLastWhile { it.route == MovieRoute.M1SendVault || it.route == MovieRoute.M2CollectSignatures }
    val detail = below.indexOfLast { it.route == MovieRoute.VaultDetail }
    if (detail >= 0) return below.take(detail + 1)
    return stackAfterOpen(below.ifEmpty { listOf(Stop(MovieRoute.VaultList, place)) }, Stop(MovieRoute.VaultDetail, place))
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
