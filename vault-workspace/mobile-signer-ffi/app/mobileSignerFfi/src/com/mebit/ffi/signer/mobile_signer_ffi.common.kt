

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
 * MOCK. Returns `"MOCK_SIG[{loan_id}:{output count}:{borrower_pubkey}]"`. Signs
 * nothing and runs no policy check (`PolicyEngine::authorize` is not called).
 */
@Throws(MobileSignerException::class)
public expect fun `signPsbt`(`psbtJson`: kotlin.String, `borrowerPubkey`: kotlin.String): kotlin.String

