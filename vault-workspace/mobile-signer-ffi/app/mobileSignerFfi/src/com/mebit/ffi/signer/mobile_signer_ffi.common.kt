

@file:Suppress("RemoveRedundantBackticks")

package com.mebit.ffi.signer

// Common helper code.
//
// Ideally this would live in a separate .kt file where it can be unittested etc
// in isolation, and perhaps even published as a re-useable package.
//
// However, it's important that the details of how this helper code works (e.g. the
// way that different builtin types are passed across the FFI) exactly match what's
// expected by the Rust code on the other side of the interface. In practice right
// now that means coming from the exact some version of `uniffi` that was used to
// compile the Rust component. The easiest way to ensure this is to bundle the Kotlin
// helpers directly inline like we're doing here.

public class InternalException(message: String) : kotlin.Exception(message)

// Public interface members begin here.


// Interface implemented by anything that can contain an object reference.
//
// Such types expose a `destroy()` method that must be called to cleanly
// dispose of the contained objects. Failure to call this method may result
// in memory leaks.
//
// The easiest way to ensure this method is called is to use the `.use`
// helper method to execute a block and destroy the object at the end.
@OptIn(ExperimentalStdlibApi::class)
public interface Disposable : AutoCloseable {
    public fun destroy()
    override fun close(): Unit = destroy()
    public companion object {
        internal fun destroy(vararg args: Any?) {
            for (arg in args) {
                when (arg) {
                    is Disposable -> arg.destroy()
                    is ArrayList<*> -> {
                        for (idx in arg.indices) {
                            val element = arg[idx]
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                    is Map<*, *> -> {
                        for (element in arg.values) {
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                    is Array<*> -> {
                        for (element in arg) {
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                    is Iterable<*> -> {
                        for (element in arg) {
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(kotlin.contracts.ExperimentalContracts::class)
public inline fun <T : Disposable?, R> T.use(block: (T) -> R): R {
    kotlin.contracts.contract {
        callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
    }
    return try {
        block(this)
    } finally {
        try {
            // N.B. our implementation is on the nullable type `Disposable?`.
            this?.destroy()
        } catch (e: Throwable) {
            // swallow
        }
    }
}

/** Used to instantiate an interface without an actual pointer, for fakes in tests, mostly. */
public object NoPointer













/**
 * Asks the user for the six digits a Trezor Safe 7 shows while pairing (not its PIN).
 * Return null if the user gives up, and never throw: an exception cannot be mapped, so the
 * connect fails with `InternalException`. It may be asked again on one connect when an answer
 * is not six digits. A Jade never asks.
 */
public interface PairingCodes {
    
    public suspend fun `pairingCode`(): kotlin.String?
    
    public companion object
}


/**
 * Asks the user for the six digits a Trezor Safe 7 shows while pairing (not its PIN).
 * Return null if the user gives up, and never throw: an exception cannot be mapped, so the
 * connect fails with `InternalException`. It may be asked again on one connect when an answer
 * is not six digits. A Jade never asks.
 */
public expect open class PairingCodesImpl: Disposable, PairingCodes {
    /**
     * This constructor can be used to instantiate a fake object. Only used for tests. Any
     * attempt to actually use an object constructed this way will fail as there is no
     * connected Rust object.
     */
    @Suppress("ConvertSecondaryConstructorToPrimary")
    public constructor(noPointer: NoPointer)

    

    override fun destroy()
    override fun close()

    
    public override suspend fun `pairingCode`(): kotlin.String?
    

    
    public companion object
}




/**
 * A link to one wallet that `scan_wallets` found. Make it, call `connect`, close it when done.
 *
 * A task owns the device and this object sends it requests, so a cancelled call never leaves
 * the device half-way through an exchange. Closing this object starts the disconnect, once no
 * call is still running on it, and returns before the link is down: cancel the call first, and
 * use `disconnect()` to wait. A link connects once: after a failed or cancelled `connect`,
 * make a new one.
 *
 * A call made on a coroutine that is already cancelled throws before it starts and leaves
 * nothing behind, so `close()` still releases the link. Wrap a call that must run after a
 * cancel, such as `disconnect()` to wait for the link to be down, in `withContext(NonCancellable)`.
 */
public interface WalletLinkInterface {
    
    /**
     * Connects to the wallet, pairs if it must, and checks the wallet is what it says.
     * Cancelling while it runs leaves nothing that closing the link cannot end: measured with a
     * Jade, unit-tested with a fake central for a Safe 7. A Jade that fails disconnects in the
     * background, a Safe 7 before this returns.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `connect`(`codes`: PairingCodes)
    
    /**
     * Ends the link and waits until it is down, also when a failure or another call is
     * already ending it. A request in flight finishes first. It does nothing until `connect`
     * has returned: cancel a `connect` that is still running instead.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `disconnect`()
    
    /**
     * Name, firmware and what only this vendor reports. Cancelling stops only the wait:
     * the wallet finishes the exchange and the link stays usable.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `info`(): WalletInfo
    
    public companion object
}


/**
 * A link to one wallet that `scan_wallets` found. Make it, call `connect`, close it when done.
 *
 * A task owns the device and this object sends it requests, so a cancelled call never leaves
 * the device half-way through an exchange. Closing this object starts the disconnect, once no
 * call is still running on it, and returns before the link is down: cancel the call first, and
 * use `disconnect()` to wait. A link connects once: after a failed or cancelled `connect`,
 * make a new one.
 *
 * A call made on a coroutine that is already cancelled throws before it starts and leaves
 * nothing behind, so `close()` still releases the link. Wrap a call that must run after a
 * cancel, such as `disconnect()` to wait for the link to be down, in `withContext(NonCancellable)`.
 */
public expect open class WalletLink: Disposable, WalletLinkInterface {
    /**
     * This constructor can be used to instantiate a fake object. Only used for tests. Any
     * attempt to actually use an object constructed this way will fail as there is no
     * connected Rust object.
     */
    /**
     * Makes a link that is not connected yet. It exists before `connect` starts, so a
     * `connect` cancelled at any moment leaves nothing that cannot be closed.
     */
    public constructor(noPointer: NoPointer)

    
    /**
     * Makes a link that is not connected yet. It exists before `connect` starts, so a
     * `connect` cancelled at any moment leaves nothing that cannot be closed.
     */
    public constructor(`wallet`: FoundWallet)

    override fun destroy()
    override fun close()

    
    /**
     * Connects to the wallet, pairs if it must, and checks the wallet is what it says.
     * Cancelling while it runs leaves nothing that closing the link cannot end: measured with a
     * Jade, unit-tested with a fake central for a Safe 7. A Jade that fails disconnects in the
     * background, a Safe 7 before this returns.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `connect`(`codes`: PairingCodes)
    
    /**
     * Ends the link and waits until it is down, also when a failure or another call is
     * already ending it. A request in flight finishes first. It does nothing until `connect`
     * has returned: cancel a `connect` that is still running instead.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `disconnect`()
    
    /**
     * Name, firmware and what only this vendor reports. Cancelling stops only the wait:
     * the wallet finishes the exchange and the link stays usable.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `info`(): WalletInfo
    

    
    public companion object
}




/**
 * A wallet a scan saw. Pass it to `WalletLink` unchanged.
 */

public data class FoundWallet (
    var `kind`: WalletKind, 
    /**
     * The platform's handle for the device, stable while the app runs. Use it as a list key.
     */
    var `id`: kotlin.String, 
    /**
     * The advertised name, e.g. `Jade 1a2b3c`.
     */
    var `name`: kotlin.String
) {
    public companion object
}



/**
 * Who answered on a connected link.
 */

public data class WalletInfo (
    var `name`: kotlin.String, 
    /**
     * The firmware version, e.g. `1.0.41`.
     */
    var `firmware`: kotlin.String, 
    var `details`: WalletDetails
) {
    public companion object
}





/**
 * Every failure a Bluetooth export returns.
 *
 * Flat, like `MobileSignerError`: Kotlin branches on the subclass, and `message` is the
 * vendor crate's own text, which says what to do. Kotlin reads variants by position, so
 * append new ones and run `scripts/ffi.sh build`.
 */
public sealed class BleException(message: String): kotlin.Exception(message) {
    
    /**
     * This build or this process cannot use Bluetooth. On Android, `System.loadLibrary`
     * did not run before the first Bluetooth call.
     */
    public class Unsupported(message: String) : BleException(message)
    
    /**
     * The OS refused Bluetooth to this app. Android: ask for the permissions again.
     * iOS: Settings.
     */
    public class PermissionDenied(message: String) : BleException(message)
    
    /**
     * There is no usable adapter: on Android, Bluetooth is fully off or missing, and the scanner
     * is null. An Android adapter that stays in `BLE_ON` after the switch is turned off scans
     * nothing and gives `NotFound` instead. iOS never reports this (read from btleplug's source,
     * not run): with Bluetooth off, a scan finds nothing and fails as `NotFound`.
     */
    public class BluetoothOff(message: String) : BleException(message)
    
    /**
     * No wallet of that kind was seen. The message says whether anything was seen at all.
     * With Bluetooth off, iOS ends up here too (read from btleplug's source, not run), and so
     * does an Android adapter in `BLE_ON`. On Android 11 and below, so does a missing location
     * permission or Location switched off.
     */
    public class NotFound(message: String) : BleException(message)
    
    /**
     * Not a wallet this app supports: another Trezor model, older firmware, no wallet service.
     */
    public class WrongDevice(message: String) : BleException(message)
    
    /**
     * Nobody typed the pairing code, or the wallet's own cancel button ended pairing.
     */
    public class PairingCancelled(message: String) : BleException(message)
    
    /**
     * Pairing did not finish. The Safe 7 refused the code, or the OS pairing was declined or not
     * confirmed in time, which iOS reports as this error for a Safe 7. Elsewhere that case
     * arrives as `Disconnected` while connecting, or as `Timeout` after a Jade's stalled service
     * discovery, with a hint in the text. A Jade on Android holds its pairing request in the
     * first request after connect, and a declined one ends that request as `Disconnected` or
     * `Timeout` with the same hint. A Jade that lost power gives the same errors.
     */
    public class PairingFailed(message: String) : BleException(message)
    
    /**
     * The wallet forgot this phone's Bluetooth bond (a Safe 7 that was restored or removed the
     * phone, a Jade that paired other hosts): forget it in Settings and connect again. Android
     * can re-pair by itself instead of raising this.
     */
    public class BondRemoved(message: String) : BleException(message)
    
    /**
     * The request was refused, by the wallet (declined, locked, busy, not set up) or before
     * it was sent. The link stays usable.
     */
    public class Refused(message: String) : BleException(message)
    
    /**
     * A step took too long. For a Jade, a service discovery that stalls, or on Android a first
     * request that stalls, can be a pairing request that nobody answered, and the text says so.
     */
    public class Timeout(message: String) : BleException(message)
    
    /**
     * The link is down or closed. Connect again. While connecting, or in a Jade's first request
     * on Android, it can mean a pairing request that nobody confirmed in time, and the text says
     * so. A Jade that lost power drops the link the same way, so the text is a hint, not a
     * diagnosis.
     */
    public class Disconnected(message: String) : BleException(message)
    
    /**
     * Any other Bluetooth failure: connecting, MTU, GATT.
     */
    public class Link(message: String) : BleException(message)
    
    /**
     * A bug, or a wallet out of step with this app. Connect again, and report it.
     */
    public class Protocol(message: String) : BleException(message)
    
}






public enum class JadeState {
    
    /**
     * No wallet on the device.
     */
    UNINIT,
    /**
     * A recovery phrase entered but not saved behind a PIN yet.
     */
    UNSAVED,
    /**
     * A saved wallet, not unlocked over this link.
     */
    LOCKED,
    /**
     * Unlocked over this link.
     */
    READY,
    /**
     * A Temporary Signer wallet, forgotten on reboot.
     */
    TEMP;
    public companion object
}







/**
 * Every failure an export returns.
 *
 * Flat on purpose: Kotlin gets `sealed class MobileSignerException` with one
 * subclass per variant, and `message` is exactly this type's `Display` text.
 * Callers branch on the subclass; fields are not exposed, so anything a UI
 * needs (the list index) is in the message.
 *
 * A message about a key names its list position, never the key's text.
 */
public sealed class MobileSignerException(message: String): kotlin.Exception(message) {
    
    /**
     * `sign_psbt` input is not a JSON `vault_core::psbt::UnsignedPsbt`.
     */
    public class InvalidPsbtJson(message: String) : MobileSignerException(message)
    
    /**
     * The string at `index` is not a base58 `xpub` or `tpub`, or is too long or too
     * deep to derive addresses from. SLIP-132 forms (`zpub`, `Zpub`, `vpub`, ...)
     * land here as "unknown version magic bytes".
     */
    public class InvalidXpub(message: String) : MobileSignerException(message)
    
    /**
     * Key `index` and key 0 belong to different networks (an `xpub` mixed with
     * a `tpub`).
     */
    public class NetworkMismatch(message: String) : MobileSignerException(message)
    
    /**
     * Key `index` has the same public key as key `first`.
     */
    public class DuplicateXpub(message: String) : MobileSignerException(message)
    
    /**
     * The threshold does not fit the key list (k = 0, k > n, n = 0 or n > 20).
     * The text is vault-core's own, e.g. "invalid multisig threshold: invalid threshold 4-of-3; cannot have k > n".
     */
    public class InvalidThreshold(message: String) : MobileSignerException(message)
    
    /**
     * Any other vault-core failure. Not expected for valid keys: report it as a bug.
     * The text is `VaultCoreError`'s own `Display`, which already names its source.
     */
    public class VaultCore(message: String) : MobileSignerException(message)
    
}




/**
 * What only one vendor reports. The variant is the vendor.
 */

public sealed class WalletDetails {
    
    public data class Jade(
        /**
         * `JADE_V2C` is a Jade Core.
         */
        val `board`: kotlin.String,
        val `state`: JadeState,
        /**
         * `MAIN` or `TEST` once the wallet is pinned to a network type, `ALL` before.
         */
        val `networks`: kotlin.String,
    ) : WalletDetails() {
    }
    
    /**
     * Read once, during connect.
     */
    public data class TrezorSafe7(
        /**
         * Always `T3W1`.
         */
        val `internalModel`: kotlin.String,
        /**
         * Random per device, regenerated by a wipe.
         */
        val `deviceId`: kotlin.String?,
        val `label`: kotlin.String?,
        /**
         * The link's ATT MTU, at least 247.
         */
        val `mtu`: kotlin.UShort,
    ) : WalletDetails() {
    }
    
}








public enum class WalletKind {
    
    JADE,
    TREZOR_SAFE7;
    public companion object
}









/**
 * MOCK, legacy three-party (borrower/platform/lender) 2-of-3 lending vault.
 * Returns `"tb1q"` + hex of a Rust `DefaultHasher` digest: not a Bitcoin
 * address, and not stable across Rust releases. For a real address use
 * `compute_vault_address_multisig`.
 */
public expect fun `computeVaultAddress`(`borrowerPubkey`: kotlin.String, `platformPubkey`: kotlin.String, `lenderPubkey`: kotlin.String): kotlin.String

/**
 * First receive address of a `threshold`-of-`xpubs.len()` P2WSH multisig: address
 * 0 of vault-core's sorted-multisig descriptor over `<xpub>/0/<index>` for every key.
 *
 * - `xpubs`: base58 `xpub` (mainnet) or `tpub` (test networks), any order
 * (sortedmulti), all from one network, no public key twice.
 * - Network is inferred from the version bytes, because the fixed two-argument
 * signature has no network parameter: `xpub` gives `bc1...`; `tpub` gives
 * `tb1...` (built as `Network::Testnet4`; the string is identical for testnet3
 * and signet). Regtest (`bcrt1...`) cannot be produced by this function.
 * - Index is fixed at 0 for the same reason. Other indexes and the change
 * chain (derivation path `/1/<index>`) need a new export, not a new argument here.
 * - `threshold`: 1 <= threshold <= xpubs.len() <= 20.
 */
@Throws(MobileSignerException::class)
public expect fun `computeVaultAddressMultisig`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): kotlin.String

/**
 * MOCK. vault-core's `derive_child_pubkey` is a format string: this returns
 * `"{account_xpub}/m/48'/0'/0'/2'/{index}"`, not a public key.
 */
public expect fun `deriveBorrowerPubkey`(`accountXpub`: kotlin.String, `index`: kotlin.UInt): kotlin.String

/**
 * How many `WalletLink` objects exist in this process. For tests, not a stable API: a link
 * that a call kept alive after `close()` still counts here.
 */
public expect fun `liveWalletLinks`(): kotlin.ULong

/**
 * Scans for a few seconds and lists the wallets of `kind` in range.
 * Cancelling stops the scan.
 */
@Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
public expect suspend fun `scanWallets`(`kind`: WalletKind): List<FoundWallet>

/**
 * MOCK. Returns `"MOCK_SIG[{loan_id}:{output count}:{borrower_pubkey}]"`. Signs
 * nothing and runs no policy check (`PolicyEngine::authorize` is not called).
 */
@Throws(MobileSignerException::class)
public expect fun `signPsbt`(`psbtJson`: kotlin.String, `borrowerPubkey`: kotlin.String): kotlin.String

