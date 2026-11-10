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
 * vault may use its key, so its passphrase and address type are final. Lands on the Wallet list, which [stackAfterOpen] makes the only entry.
 */
internal fun endSetup(session: OnboardingSession): MovieRoute {
    session.endFirstSetup()
    return MovieRoute.VaultList
}

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
