

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
     * The account extended public key at `derivation_path`.
     *
     * `derivation_path` is a BIP-32 path such as `m/48'/1'/0'/2'`. An unreadable
     * path is refused and the link stays up. A Jade must be unlocked first.
     * The master fingerprint is 8 lowercase hex characters. Cancelling stops
     * only the wait. A refusal leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `accountXpub`(`derivationPath`: kotlin.String): HwAccountKey
    
    /**
     * Connects to the wallet, pairs if it must, and checks the wallet is what it says.
     * Cancelling while it runs leaves nothing that closing the link cannot end: measured with a
     * Jade, unit-tested with a fake central for a Safe 7. A Jade that fails disconnects in the
     * background, a Safe 7 before this returns.
     *
     * `pairing` is a Safe 7 credential an earlier connect returned, so the Safe 7 asks only to
     * allow the connection. Unreadable bytes are dropped and the Safe 7 then pairs with a code.
     * Returns the credential to keep when this connect paired with a code (the first pairing, or a
     * kept one the Safe 7 no longer knows), else null. It is a secret: keep it in the Keychain or
     * Keystore. A Jade ignores `pairing` and returns null.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `connect`(`codes`: PairingCodes, `pairing`: kotlin.ByteArray?): kotlin.ByteArray?
    
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
    
    /**
     * Has the hardware wallet sign `psbt_base64` for `signer_fingerprint`.
     *
     * `psbt_base64` is a BIP-174 PSBT. `signer_fingerprint` is 8 lowercase hex
     * characters (the vault key's master fingerprint). Jade must be unlocked
     * first. Returns the signed PSBT as base64. Callers must run spend policy
     * before this (vault-core policy against real PSBTs is still Term-2 work;
     * the app checks destination and amount at the UI boundary today).
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `signPsbt`(`psbtBase64`: kotlin.String, `signerFingerprint`: kotlin.String): kotlin.String
    
    /**
     * Signs `psbt_base64`, a payment of the wallet `descriptor` whose unsigned id is
     * `unsigned_txid` (the payment the user was shown), with the key of that wallet this device
     * holds, and returns what the PSBT says after it. Before anything reaches the device, it
     * refuses a descriptor `wallet_describe` refuses, a PSBT `wallet_review` refuses, and a PSBT
     * whose id is another ("changed since shown"). `WrongKey` means the device holds no key of the
     * wallet, and nothing showed on it. When that key has already signed, the review comes back
     * without asking the device to sign. Else the device shows the payment, and its answer is
     * refused unless that key then signed every input. A Jade must be unlocked first. Cancelling
     * stops only the wait: the device finishes the request.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `signWalletPsbt`(`descriptor`: kotlin.String, `psbtBase64`: kotlin.String, `unsignedTxid`: kotlin.String): SpendReview
    
    /**
     * Unlocks the wallet for this connection.
     *
     * A Jade asks for its PIN on the device, unless it is a Temporary Signer.
     * A Safe 7 has no separate unlock. Connect already opened the session, so
     * this returns immediately. Cancelling stops only the wait. A refusal
     * leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun `unlock`()
    
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
     * The account extended public key at `derivation_path`.
     *
     * `derivation_path` is a BIP-32 path such as `m/48'/1'/0'/2'`. An unreadable
     * path is refused and the link stays up. A Jade must be unlocked first.
     * The master fingerprint is 8 lowercase hex characters. Cancelling stops
     * only the wait. A refusal leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `accountXpub`(`derivationPath`: kotlin.String): HwAccountKey
    
    /**
     * Connects to the wallet, pairs if it must, and checks the wallet is what it says.
     * Cancelling while it runs leaves nothing that closing the link cannot end: measured with a
     * Jade, unit-tested with a fake central for a Safe 7. A Jade that fails disconnects in the
     * background, a Safe 7 before this returns.
     *
     * `pairing` is a Safe 7 credential an earlier connect returned, so the Safe 7 asks only to
     * allow the connection. Unreadable bytes are dropped and the Safe 7 then pairs with a code.
     * Returns the credential to keep when this connect paired with a code (the first pairing, or a
     * kept one the Safe 7 no longer knows), else null. It is a secret: keep it in the Keychain or
     * Keystore. A Jade ignores `pairing` and returns null.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `connect`(`codes`: PairingCodes, `pairing`: kotlin.ByteArray?): kotlin.ByteArray?
    
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
    
    /**
     * Has the hardware wallet sign `psbt_base64` for `signer_fingerprint`.
     *
     * `psbt_base64` is a BIP-174 PSBT. `signer_fingerprint` is 8 lowercase hex
     * characters (the vault key's master fingerprint). Jade must be unlocked
     * first. Returns the signed PSBT as base64. Callers must run spend policy
     * before this (vault-core policy against real PSBTs is still Term-2 work;
     * the app checks destination and amount at the UI boundary today).
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `signPsbt`(`psbtBase64`: kotlin.String, `signerFingerprint`: kotlin.String): kotlin.String
    
    /**
     * Signs `psbt_base64`, a payment of the wallet `descriptor` whose unsigned id is
     * `unsigned_txid` (the payment the user was shown), with the key of that wallet this device
     * holds, and returns what the PSBT says after it. Before anything reaches the device, it
     * refuses a descriptor `wallet_describe` refuses, a PSBT `wallet_review` refuses, and a PSBT
     * whose id is another ("changed since shown"). `WrongKey` means the device holds no key of the
     * wallet, and nothing showed on it. When that key has already signed, the review comes back
     * without asking the device to sign. Else the device shows the payment, and its answer is
     * refused unless that key then signed every input. A Jade must be unlocked first. Cancelling
     * stops only the wait: the device finishes the request.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `signWalletPsbt`(`descriptor`: kotlin.String, `psbtBase64`: kotlin.String, `unsignedTxid`: kotlin.String): SpendReview
    
    /**
     * Unlocks the wallet for this connection.
     *
     * A Jade asks for its PIN on the device, unless it is a Temporary Signer.
     * A Safe 7 has no separate unlock. Connect already opened the session, so
     * this returns immediately. Cancelling stops only the wait. A refusal
     * leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public override suspend fun `unlock`()
    

    
    public companion object
}




/**
 * Baht per bitcoin and the phone clock at the moment of the call.
 */

public data class BtcPrice (
    /**
     * Baht for one bitcoin, between 1,000 and 1,000,000,000.
     */
    var `thbPerBtc`: kotlin.Double, 
    /**
     * Unix seconds on the phone's clock when the call started. Bitkub sends no time of its own.
     */
    var `fetchedAt`: kotlin.ULong
) {
    public companion object
}



/**
 * The fee tiers the Send screen offers, in sat/vB. `slow_sat_vb` aims at confirmation in about an
 * hour, `normal_sat_vb` in about half an hour and `fast_sat_vb` in the next block or two. `live` is
 * true when they come from the explorer's view of the mempool and false when they are the fixed
 * fallback of 4, 8 and 15.
 */

public data class FeeTiers (
    var `slowSatVb`: kotlin.ULong, 
    var `normalSatVb`: kotlin.ULong, 
    var `fastSatVb`: kotlin.ULong, 
    var `live`: kotlin.Boolean
) {
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




public data class HotSendResult (
    var `txid`: kotlin.String
) {
    public companion object
}



/**
 * One transaction of the hot wallet, seen from the wallet's side.
 */

public data class HotTx (
    var `txid`: kotlin.String, 
    /**
     * Sats the wallet gained (positive) or lost (negative), fee included: the outputs paying any
     * of the wallet's addresses minus the inputs that spent from them. A payment to yourself, and
     * change, read as minus the fee.
     */
    var `netSats`: kotlin.Long, 
    /**
     * 0 while in the mempool, 1 in the newest block, and so on. Never 0 once confirmed.
     */
    var `confirmations`: kotlin.UInt, 
    /**
     * Unix seconds of the block that holds it. Null while unconfirmed, and when the server gives
     * 0 or a time after the start of 2100, which the app could not print.
     */
    var `blockTime`: kotlin.ULong?
) {
    public companion object
}




public data class HotTxDetail (
    /**
     * The row read again from this answer by `hot_wallet_history`'s rule, so the confirmations are as of now.
     */
    var `tx`: HotTx, 
    /**
     * None while unconfirmed.
     */
    var `blockHeight`: kotlin.ULong?, 
    /**
     * In the transaction's order.
     */
    var `inputs`: List<HotTxo>, 
    /**
     * In the transaction's order.
     */
    var `outputs`: List<HotTxo>, 
    /**
     * Inputs minus outputs, from the values above. 0 for a coinbase, which pays none.
     */
    var `feeSats`: kotlin.ULong, 
    var `sizeBytes`: kotlin.ULong, 
    /**
     * Weight / 4, rounded up.
     */
    var `vsize`: kotlin.ULong, 
    /**
     * True when an input's sequence is below 0xfffffffe (BIP-125). Says nothing about full-RBF nodes.
     */
    var `signalsRbf`: kotlin.Boolean
) {
    public companion object
}




public data class HotTxo (
    /**
     * None for a script with no address (OP_RETURN) and for a coinbase input.
     */
    var `address`: kotlin.String?, 
    var `valueSats`: kotlin.ULong, 
    var `owner`: TxoOwner
) {
    public companion object
}




public data class HotWalletSnapshot (
    var `receiveAddress`: kotlin.String, 
    /**
     * Confirmed plus mempool, over every address of both chains: what the wallet holds, once `scan` is
     * `Complete`. Before that, only what the scan has found so far, which no screen may show as the balance.
     */
    var `balanceSats`: kotlin.ULong, 
    /**
     * What a payment could spend now: the confirmed coins of receive addresses and the wallet's
     * own change, confirmed or not. The same coins `hot_prepare_send_all` spends, so a receive
     * still in the mempool counts in `balance_sats` and not here. 0 unless `scan` is `Complete`.
     */
    var `spendableSats`: kotlin.ULong, 
    var `network`: KeyNetwork, 
    var `scan`: HotScan
) {
    public companion object
}



/**
 * An account extended public key read from a hardware wallet.
 */

public data class HwAccountKey (
    /**
     * `xpub` on mainnet, `tpub` on test networks. Not a SLIP-132 form.
     */
    var `xpub`: kotlin.String, 
    /**
     * Master key fingerprint, 8 lowercase hex characters.
     */
    var `masterFingerprint`: kotlin.String, 
    /**
     * The path the key was derived on, such as `m/48'/1'/0'/2'`.
     */
    var `derivationPath`: kotlin.String
) {
    public companion object
}



/**
 * A signed payment that has not been posted anywhere.
 */

public data class PreparedSend (
    /**
     * Computed from the signed transaction here, never read from a server.
     */
    var `txid`: kotlin.String, 
    /**
     * The signed transaction, consensus-encoded, for `hot_broadcast`.
     */
    var `hex`: kotlin.String, 
    /**
     * The fee the transaction pays, in sats, its inputs less its outputs: its virtual size times the
     * rate asked for, rounded up, plus `dust_change_sats`, or less where that would pass the
     * network's 10,000 sat/vB and change at its dust floor takes the rest. A fee bump or a cancel
     * pays what `hot_prepare_fee_bump` says.
     */
    var `feeSats`: kotlin.ULong, 
    /**
     * What `destination` is paid, in sats: the amount asked for, or for `hot_prepare_send_all`
     * the whole balance less `fee_sats`, which is only known once the coins are. For a fee bump it
     * is what the earlier payment's destination gets, and for a cancel what comes back to this wallet.
     */
    var `amountSats`: kotlin.ULong, 
    /**
     * What was left for change but could not be an output, in sats: change below the change
     * address's dust floor, which the network would refuse, or too little to pay for a change output
     * at the rate asked. It is part of `fee_sats` instead. 0 when there was none.
     */
    var `dustChangeSats`: kotlin.ULong = 0uL, 
    /**
     * How much less a fee bump's destination gets than in the payment it replaces, in sats, because
     * that payment had no change and no confirmed coin could keep its amount, so the new fee came out
     * of the amount. 0 for every other answer.
     */
    var `amountCutSats`: kotlin.ULong = 0uL
) {
    public companion object
}



/**
 * What `hot_quote_send` reads: the figures of the payment the signer would make now.
 */

public data class QuotedSend (
    /**
     * `PreparedSend::fee_sats` of that payment.
     */
    var `feeSats`: kotlin.ULong, 
    /**
     * `PreparedSend::amount_sats`: the amount asked for, or for send all the balance less `fee_sats`.
     */
    var `amountSats`: kotlin.ULong, 
    /**
     * `PreparedSend::dust_change_sats`.
     */
    var `dustChangeSats`: kotlin.ULong, 
    /**
     * The payment's virtual size once signed, in vbytes.
     */
    var `vsize`: kotlin.ULong
) {
    public companion object
}



/**
 * A 24-word BIP-39 mnemonic and the BIP-48 P2WSH account key derived from it.
 *
 * `mnemonic_words` is the one copy of the secret that crosses into Kotlin.
 * Store it, then drop this record.
 */

public data class SoftwareAccountKey (
    var `mnemonicWords`: List<kotlin.String>, 
    /**
     * Master key fingerprint, 8 lowercase hex characters.
     */
    var `masterFingerprint`: kotlin.String, 
    var `xpub`: kotlin.String, 
    /**
     * `m/48'/coin'/account'/2'`, coin 0 on `Main` and coin 1 on `Test`.
     */
    var `derivationPath`: kotlin.String
) {
    public companion object
}




public data class SpendOutput (
    var `address`: kotlin.String, 
    var `sats`: kotlin.ULong
) {
    public companion object
}



/**
 * A payment of a described wallet as its PSBT says: what it pays and who has signed it.
 */

public data class SpendReview (
    /**
     * Base64 BIP-174, the whole state of the payment: the next signer and `wallet_finish` take it as it is.
     */
    var `psbt`: kotlin.String, 
    /**
     * The id of the unsigned transaction. Every signature commits to it, and `wallet_finish` refuses a PSBT
     * whose id is another. It is also the sent transaction's id when every input is native SegWit or
     * Taproot; a Legacy or Nested SegWit signature goes into the transaction itself, so that id differs.
     */
    var `unsignedTxid`: kotlin.String, 
    /**
     * Every output that is not this wallet's change, in order. A draft's first is the destination.
     */
    var `payments`: List<SpendOutput>, 
    /**
     * This wallet's change, 0 for none.
     */
    var `changeSats`: kotlin.ULong, 
    /**
     * The inputs, as their parent transactions prove them, less the outputs.
     */
    var `feeSats`: kotlin.ULong, 
    /**
     * The master fingerprints, 8 lowercase hex, of the wallet's keys with a valid signature on every input,
     * each once, in the descriptor's order.
     */
    var `signed_`: List<kotlin.String>, 
    /**
     * How many keys must sign: 1 for one key.
     */
    var `needed`: kotlin.UByte
) {
    public companion object
}



/**
 * One payment from a several-key wallet, from `vault_prepare_send` to `vault_finalize_psbt`. The app holds the latest one
 * and passes it back whole. Every field but `psbt` is what the user confirmed or what the core read from `psbt` once it
 * passed every check, so the record never says anything its PSBT does not.
 */

public data class VaultPsbt (
    /**
     * The PSBT, base64.
     */
    var `psbt`: kotlin.String, 
    /**
     * The id of the finished transaction. A segwit id leaves the signatures out, so it is fixed from the unsigned PSBT on,
     * and the app's pay-twice guard names the payment by it.
     */
    var `txid`: kotlin.String, 
    /**
     * What the first output pays, as the user typed it.
     */
    var `destination`: kotlin.String, 
    var `amountSats`: kotlin.ULong, 
    /**
     * Inputs less outputs. Every input's value is read from its parent transaction, which the PSBT carries.
     */
    var `feeSats`: kotlin.ULong, 
    /**
     * The one output back to the wallet's change chain, 1/i. 0 when there is none.
     */
    var `changeSats`: kotlin.ULong, 
    /**
     * The highest change index the payment may pay its change to: the one the wallet handed out next when the payment was
     * prepared, so no PSBT parks change past the addresses a scan reads.
     */
    var `maxChangeIndex`: kotlin.UInt, 
    /**
     * The finished transaction's virtual size: exact for the phone's signatures, an upper bound for shorter ones.
     */
    var `vsize`: kotlin.ULong, 
    /**
     * Positions in `xpubs` of the keys with a valid SIGHASH_ALL signature on every input, ascending, each once.
     */
    var `signedKeys`: List<kotlin.UInt>
) {
    public companion object
}



/**
 * A several-key wallet's balance as one sync of both its chains found it. Not `HotWalletSnapshot`, because a vault's
 * spendable figure is `vault_spendable`'s, read apart so a list that shows balances pays for no coin list, and a next
 * unused receive address would contradict the Receive screen, which shows address 0.
 */

public data class VaultSnapshot (
    /**
     * Confirmed plus mempool over every address of both chains the scan counts. Before `scan` is `Complete`, only
     * what the scan has found so far, which no screen may show as the balance.
     */
    var `balanceSats`: kotlin.ULong, 
    var `scan`: HotScan
) {
    public companion object
}



/**
 * What a payment from a several-key wallet could spend now, as a one-key wallet's `spendable_sats` is read.
 */

public data class VaultSpendable (
    /**
     * The coins `vault_prepare_send` would consider: every confirmed coin of both chains and the wallet's own
     * unconfirmed change. 0 unless `scan` is `Complete`.
     */
    var `spendableSats`: kotlin.ULong, 
    var `scan`: HotScan
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




public data class WalletKey (
    /**
     * The master key's fingerprint, 8 lowercase hex characters.
     */
    var `fingerprint`: kotlin.String, 
    /**
     * The account's path, as `m/84'/1'/0'`.
     */
    var `path`: kotlin.String, 
    var `xpub`: kotlin.String
) {
    public companion object
}



/**
 * What `wallet_describe` tells of a wallet.
 */

public data class WalletShape (
    /**
     * How many keys must sign a payment: 1 for one key.
     */
    var `needed`: kotlin.UByte, 
    /**
     * In the descriptor's order.
     */
    var `keys`: List<WalletKey>, 
    /**
     * Receive address 0.
     */
    var `firstAddress`: kotlin.String, 
    /**
     * The virtual size of one of its coins paying an address of its own type, with change: what the Send
     * screen prices a payment at before Review reads the real one.
     */
    var `typicalVsize`: kotlin.ULong
) {
    public companion object
}




/**
 * How the hot wallet's addresses look. One wallet has one type for life: every key of an account
 * carries the account's type, so no transaction mixes two. The default is the type every wallet
 * saved before this setting had.
 */


public enum class AddressType {
    
    /**
     * BIP-44 P2PKH. The signature and the key go in the script_sig, over the legacy sighash.
     */
    LEGACY,
    /**
     * BIP-49 P2SH-P2WPKH. The script_sig pushes the P2WPKH script; the witness is as Native SegWit's.
     */
    NESTED_SEGWIT,
    /**
     * BIP-84 P2WPKH.
     */
    NATIVE_SEGWIT,
    /**
     * BIP-86 P2TR key path with no script tree: one 64-byte Schnorr signature, SIGHASH_DEFAULT.
     */
    TAPROOT;
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
    
    /**
     * The device holds no key of the wallet it was asked to sign for: no key with its master
     * fingerprint, or another key at that key's path. Nothing showed on the device, and the link
     * stays usable.
     */
    public class WrongKey(message: String) : BleException(message)
    
}




/**
 * How far the scan behind a snapshot got.
 */


public enum class HotScan {
    
    /**
     * Every address a restore finds was asked, so the figures are the wallet's.
     */
    COMPLETE,
    /**
     * The scan stopped short and goes on at the next call, after a host's request limit or a payment that
     * no block holds yet.
     */
    CONTINUING,
    /**
     * A host lied about the wallet's history, so the figures are not the wallet's.
     */
    UNVERIFIED;
    public companion object
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
 * Bitcoin mainnet, or testnet4. Testnet4 matches the network the Bluetooth
 * link already uses.
 */


public enum class KeyNetwork {
    
    MAIN,
    TEST;
    public companion object
}






/**
 * What `check_mnemonic` found. `index` and `count` are positions and sizes of the
 * list the caller passed, never the words themselves.
 */

public sealed class MnemonicCheck {
    
    
    public object Valid : MnemonicCheck() 
    
    
    /**
     * The word at `index` (0-based) is not in the BIP-39 English list.
     */
    public data class UnknownWord(
        val `index`: kotlin.UInt,
    ) : MnemonicCheck() {
    }
    
    /**
     * BIP-39 mnemonics have 12, 15, 18, 21 or 24 words.
     */
    public data class BadWordCount(
        val `count`: kotlin.UInt,
    ) : MnemonicCheck() {
    }
    
    /**
     * Every word is in the list, but the checksum does not match.
     */
    
    public object InvalidChecksum : MnemonicCheck() 
    
    
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
    
    /**
     * The words are not a BIP-39 mnemonic. The text names a word index or a count,
     * never the words.
     */
    public class InvalidMnemonic(message: String) : MobileSignerException(message)
    
    /**
     * A network call failed or answered something unusable. The text names the cause. Kotlin
     * reads variants by position, so any new one goes below this.
     */
    public class Network(message: String) : MobileSignerException(message)
    
    /**
     * `hot_broadcast` posted a payment but cannot tell whether the explorer took it: the answer was
     * lost or unclear, and the transaction was still not found after the last lookup. The text
     * carries the txid. The payment may be out, so repeating it may pay twice. Kotlin reads
     * variants by position, so any new one goes below this.
     */
    public class BroadcastUnknown(message: String) : MobileSignerException(message)
    
}




/**
 * Who owns an output, whether the transaction creates it or an input spends it.
 */


public enum class TxoOwner {
    
    /**
     * The wallet's receive chain, `0/i`.
     */
    RECEIVE,
    /**
     * The wallet's change chain, `1/i`.
     */
    CHANGE,
    /**
     * Anyone else's, or a script with no address.
     */
    OTHER;
    public companion object
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
 * The words that turn `first_words` into a valid mnemonic, in list order. For 23
 * words that is 8: the last word carries 3 entropy bits and 8 checksum bits.
 * Empty when a word is not in the list or the count cannot become 12, 15, 18, 21
 * or 24 words.
 */
public expect fun `bip39LastWordCandidates`(`firstWords`: List<kotlin.String>): List<kotlin.String>

/**
 * Up to `limit` BIP-39 English words that start with `prefix`, in list order.
 * An empty prefix gives none.
 */
public expect fun `bip39Suggest`(`prefix`: kotlin.String, `limit`: kotlin.UInt): List<kotlin.String>

/**
 * Is `words` a valid BIP-39 English mnemonic, and if not, why? Nothing is retained.
 */
@Throws(MobileSignerException::class)
public expect fun `checkMnemonic`(`words`: List<kotlin.String>): MnemonicCheck

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
 * Baht per bitcoin from Bitkub, or from CoinGecko when Bitkub fails or answers nonsense.
 * Neither publishes terms for use inside an app: fine for this testnet build, not for production.
 *
 * Blocks for up to 20 seconds when both sources hang, so call it off the main thread. Fails with
 * `Network`, which names both reasons, when neither source gives a price.
 */
@Throws(MobileSignerException::class)
public expect fun `fetchBtcThb`(): BtcPrice

/**
 * A new 24-word BIP-39 mnemonic and its BIP-48 P2WSH account key.
 *
 * `passphrase` may be empty. `Main` derives an `xpub` at `m/48'/0'/account'/2'`.
 * `Test` derives a `tpub` at `m/48'/1'/account'/2'` on testnet4.
 * The words are returned once, in `mnemonic_words`.
 */
@Throws(MobileSignerException::class)
public expect fun `generateSoftwareMultisigKey`(`passphrase`: kotlin.String, `network`: KeyNetwork, `account`: kotlin.UInt): SoftwareAccountKey

/**
 * The account path the hot wallet derives `address_type`'s keys from, `m/{purpose}'/1'/0'` on either
 * network, as the fingerprint sheets print it.
 */
public expect fun `hotAccountPath`(`addressType`: AddressType): kotlin.String

/**
 * Whether a wallet on `network` can take `address_type`, as every call that derives one of its keys
 * enforces: every type on testnet4, Native SegWit alone on mainnet.
 */
public expect fun `hotAddressTypeEnabled`(`addressType`: AddressType, `network`: KeyNetwork): kotlin.Boolean

/**
 * Post a transaction from `hot_prepare_send_at_rate` once. `txid` must be the id of the transaction in
 * `hex`, else it refuses before posting, so nothing else can go out under a saved guard.
 *
 * When the explorer's answer leaves it unclear whether the payment went out, it looks `txid` up a
 * few times and fails with `BroadcastUnknown` if it is still not found. The payment may be out
 * then, so the app must not offer it as a failure to retry. A silent explorer can hold this call
 * for about two minutes, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotBroadcast`(`network`: KeyNetwork, `hex`: kotlin.String, `txid`: kotlin.String): HotSendResult

/**
 * The fee tiers for the Send screen, from the testnet4 host in use. One network call in the usual
 * case, answered from memory for five minutes after. Never fails: when no host answers or the
 * answer is unusable the fixed tiers 4, 8 and 15 come back with `live` false.
 */
public expect fun `hotFeeTiers`(`network`: KeyNetwork): FeeTiers

/**
 * Forgets every wallet's scan, in memory and in the folder `hot_use_state_dir` named. Call it when the
 * wallet is erased.
 */
public expect fun `hotForgetScanState`()

/**
 * Scan, then sign a cancel of `earlier_hex`, the signed payment the app's guard keeps, and post
 * nothing. It spends exactly that payment's inputs in its order and pays them back to this wallet in
 * one output, to its change address when it had change and else to the next unused one. The fee follows the rules of
 * `hot_prepare_fee_bump` for that one output, and `amount_sats` is what comes back. Until a block holds
 * one of the two, the earlier payment may still confirm instead. The same refusals as
 * `hot_prepare_fee_bump`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotPrepareCancel`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `earlierHex`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend

/**
 * Scan, then sign a fee bump of `earlier_hex`, the signed payment the app's guard keeps, and post
 * nothing. It spends every input of that payment in its order, so at most one of the two can ever
 * confirm, and its first output stays as it was. More confirmed coins join only when the earlier
 * inputs cannot pay the new fee, change goes to the same change address, or for a payment with no
 * change to the next unused one, as Bitcoin Core's bumpfee does, and change below its dust floor goes
 * into the fee. Where that would pass 10,000 sat/vB on the bump's size, the change stays at exactly its
 * floor and the fee is the rest, under the rate asked but within rules 3, 4 and 6, so the amount is
 * kept. Only a payment with no change (a send all, or one whose change went into its fee)
 * whose amount no confirmed coin can keep gets one output worth its own inputs less the new fee, and
 * `amount_cut_sats` says how much less the destination gets. The fee meets BIP-125 rules 3, 4 and 6
 * against that payment. It is at
 * least that payment's fee plus the new size at 1 sat/vB, at a rate strictly above its rate, so the
 * rate can be above `fee_rate_sat_vb`. Refuses, before any lookup, bytes that are not one transaction with inputs and one
 * or two outputs whose first is at least its dust floor, and a rate outside 1 to 10,000 sat/vB. After the scan
 * it refuses, after `replace: `, a second output that is not this wallet's change, inputs that are not
 * all coins this wallet can spend now (`coins spent:`, most likely because that payment is out), an
 * input without this wallet's own SIGHASH_ALL signature for its coin, a rate that would pass 10,000
 * sat/vB, on the rate asked or on the signed bytes, what is left below the dust floor of its
 * output, and a bump above the network's 100,000 vB (`too many coins:`), and without `replace: ` a
 * payment with change whose amount the confirmed coins cannot keep
 * (`insufficient funds:`). The same scan as `hot_prepare_send_at_rate`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotPrepareFeeBump`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `earlierHex`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend

/**
 * Scan, spend every coin `hot_prepare_send_at_rate` would consider, and sign one output paying
 * `destination` the whole balance less the fee, with no change, and post nothing. The fee is the
 * size of that transaction times `fee_rate_sat_vb`, so it and the amount are only known once the
 * coins are: the answer's `amount_sats` is what leaves. Refuses, before signing, a rate outside
 * 1 to 10,000 sat/vB, a wallet with nothing confirmed to spend, coins that need more than the
 * network's 100,000 vB in one transaction (`too many coins:`), and a balance that would leave
 * less than the destination's dust floor after the fee, and after signing what
 * `hot_prepare_send_at_rate` refuses past the network's limit. The same scan as
 * `hot_prepare_send_at_rate`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotPrepareSendAll`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `destination`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend

/**
 * Scan, select coins, check and sign a payment at `fee_rate_sat_vb`, and post nothing. The only
 * network use is the Esplora lookups of the scan. The app saves the `txid` as its payment guard
 * before it calls `hot_broadcast`, so a kill between the two can never lead to a second payment.
 *
 * Coins are added largest first, one at a time, until they pay the amount and the fee of the
 * transaction they make, so any payment some of the coins can make is signed. The fee is the size
 * of the transaction as built times the rate, so a payment that needs more coins pays more, never a
 * fixed sum that falls under the rate. The answer's `fee_sats` is that fee, the one `hot_quote_send`
 * reads without signing on the same chain state. Refuses, before
 * signing, a rate outside 1 to 10,000 sat/vB (the network's limit), an amount below the dust floor
 * of the destination's script (294 sats for P2WPKH up to 546 for P2PKH, the outputs the network
 * refuses), an amount that overflows u64 with its fee, coins that cannot pay it (`insufficient
 * funds:`) and a payment that needs more than the network's 100,000 vB (`too many coins:`), and
 * after signing a fee above 10,000 sat/vB times the signed transaction's virtual size. Change below
 * the change address's dust floor would be refused by the network too, and change too small to pay
 * for its own output costs more than it returns, so either goes into the fee and the answer's
 * `dust_change_sats` names it. Where that would pass 10,000 sat/vB, the change stays at exactly its
 * floor and the fee is the rest, under the rate asked. A fee above the amount is signed, because
 * the fee is paid on top and the destination still gets the amount, so the app asks the user before
 * it posts.
 * Confirmed coins pay first, and the wallet's own unconfirmed change only when they fall short. A
 * coin someone else sent is spent only once confirmed. About 40 Esplora calls for a fresh wallet,
 * so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotPrepareSendAtRate`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `destination`: kotlin.String, `amountSats`: kotlin.ULong, `feeRateSatVb`: kotlin.ULong): PreparedSend

/**
 * What `hot_prepare_send_at_rate` with `amount_sats`, or `hot_prepare_send_all` when it is null, would
 * sign for the same arguments on the chain as this call reads it: the same scan, coin lists, coin
 * selection, sizes and refusals, and no signature, so no key signs. A Legacy coin's value is proven
 * from its parent only when the payment is signed, so a host that lists one at another value fails
 * that payment, never this call. The same scan as the payment, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `hotQuoteSend`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `destination`: kotlin.String, `amountSats`: kotlin.ULong?, `feeRateSatVb`: kotlin.ULong): QuotedSend

/**
 * Receive address `index` (usually 0) of `address_type`'s account. `passphrase` is the
 * BIP-39 passphrase the key was restored with, empty for none: a different
 * passphrase is a different wallet. Mainnet takes Native SegWit only.
 */
@Throws(MobileSignerException::class)
public expect fun `hotReceiveAddress`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `index`: kotlin.UInt): kotlin.String

/**
 * One transaction of the wallet in full, or None when the explorer does not know `txid`.
 */
@Throws(MobileSignerException::class)
public expect fun `hotTxDetail`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `txid`: kotlin.String): HotTxDetail?

/**
 * The virtual size the Send screen prices a payment of an `address_type` wallet at, before Review
 * quotes the real one with `hot_quote_send`: one of its coins paying an address of the same type,
 * with change, as `signed_payment` builds it. Native SegWit's is 141, the size every estimate used
 * before the other types.
 */
public expect fun `hotTypicalVsize`(`addressType`: AddressType): kotlin.ULong

/**
 * Keeps each wallet's scan in `dir`, app storage that no backup copies, so the first sync after a restart
 * asks only what can have changed. Call it once at app start. Without it scans live in memory only.
 */
public expect fun `hotUseStateDir`(`dir`: kotlin.String)

/**
 * The latest transactions of the wallet's address, each as the wallet saw it. The order is
 * Esplora's: the mempool first, then confirmed transactions newest first. Only the first page
 * comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
 *
 * Makes two network calls, so call it off the main thread. An answer it cannot read fails the
 * whole call, never one transaction.
 */
@Throws(MobileSignerException::class)
public expect fun `hotWalletHistory`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType): List<HotTx>

/**
 * Confirmed + mempool balance for receive index 0 via Esplora.
 */
@Throws(MobileSignerException::class)
public expect fun `hotWalletSnapshot`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType): HotWalletSnapshot

/**
 * True when `address` parses and belongs to `network`: the same test `hot_prepare_send_at_rate` applies to its destination.
 */
public expect fun `isValidAddress`(`address`: kotlin.String, `network`: KeyNetwork): kotlin.Boolean

/**
 * How many `WalletLink` objects exist in this process. For tests, not a stable API: a link
 * that a call kept alive after `close()` still counts here.
 */
public expect fun `liveWalletLinks`(): kotlin.ULong

/**
 * The BIP-48 P2WSH account key for an existing BIP-39 mnemonic.
 *
 * `mnemonic_words` is the 12, 15, 18, 21 or 24 words, or one string that
 * already joins them with spaces. The record echoes the normalized words.
 * `passphrase`, `network` and `account` mean the same as in
 * `generate_software_multisig_key`.
 */
@Throws(MobileSignerException::class)
public expect fun `restoreSoftwareMultisigKey`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `account`: kotlin.UInt): SoftwareAccountKey

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

/**
 * The signed transaction of `held` once at least `threshold` keys of the wallet signed every input, for `hot_broadcast`.
 * Runs every check `vault_sign_psbt` runs, refuses fewer signers than `threshold`, puts `threshold` of their signatures in
 * each input's witness in the order its script names their keys, and refuses bytes heavier than the size the fee was
 * priced at or a fee past the network's 10,000 sat/vB. Testnet4 only. No network call.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultFinalizePsbt`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte, `held`: VaultPsbt): PreparedSend

/**
 * The latest transactions of the `threshold`-of-`xpubs.len()` sorted multisig that `vault_snapshot` reads, over every
 * used address of its receive and change chains, each once, as the wallet saw it: the mempool first, then confirmed
 * transactions newest first. Only each address's first page comes back (about 25 confirmed transactions plus the
 * mempool), so older history is not here. Testnet4 only, so mainnet `xpub` keys are refused before any request. Fails
 * unless the scan reached the end of both chains, and an answer it cannot read fails the whole call. Makes a network
 * call per used address plus one for the tip, on top of the sync it shares with a `vault_snapshot` of the last ten
 * seconds, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultHistory`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): List<HotTx>

/**
 * The unsigned payment of `amount_sats` to `destination` from the `threshold`-of-`xpubs.len()` sorted multisig at
 * `fee_rate_sat_vb`, or with a null amount everything it can spend less the fee in one output, as a PSBT every key of the
 * wallet signs with `vault_sign_psbt` and `vault_finalize_psbt` finishes. Coins, change and fee as
 * `hot_prepare_send_at_rate` and `hot_prepare_send_all` pick them, with their refusals, change to the first unused address
 * of the change chain, and every input's parent transaction in the PSBT, read once per transaction and refused when it does
 * not pay the coin as listed, so each signer proves the fee. Fails unless the scan reached the end of both chains. Signs and
 * posts nothing. Testnet4 only, so mainnet `xpub` keys are refused before any request. About 40 Esplora calls for a wallet
 * nobody paid, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultPrepareSend`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte, `destination`: kotlin.String, `amountSats`: kotlin.ULong?, `feeRateSatVb`: kotlin.ULong): VaultPsbt

/**
 * `held` with key `key_index`'s signature on every input, made with the words and passphrase of a phone key, whose BIP-48
 * account key at m/48'/1'/0'/2' must be key `key_index` of the `threshold`-of-`xpubs.len()` wallet on public key and chain
 * code. Before it signs it refuses, naming the rule, a PSBT that is not this wallet's payment of `held.amount_sats` to
 * `held.destination` for `held.fee_sats` with at most one change output back to the wallet's change chain at or below
 * `held.max_change_index`, a txid other than `held.txid`, any sighash but ALL, and a signature that does not verify. The
 * same words sign the same PSBT the same way, so signing again changes nothing. Testnet4 only. No network call.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultSignPsbt`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte, `held`: VaultPsbt, `keyIndex`: kotlin.UInt, `mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String): VaultPsbt

/**
 * The confirmed + mempool balance over every address of both chains, receive `0/i` and change `1/i`, of the
 * `threshold`-of-`xpubs.len()` sorted multisig that `compute_vault_address_multisig` takes, with how far the scan
 * got. Testnet4 only, so mainnet `xpub` keys are refused before any request. About 40 Esplora calls for a wallet
 * nobody paid and a routine sync's few after that, so call it off the main thread. No screen may show
 * `balance_sats` unless `scan` is `Complete`.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultSnapshot`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): VaultSnapshot

/**
 * What a payment from the `threshold`-of-`xpubs.len()` sorted multisig could spend now, for the 5.1 Send screen: every
 * confirmed coin of its receive and change chains and its own unconfirmed change, by the sync `vault_snapshot` makes,
 * which a read within ten seconds shares, and one coin list per funded address once that scan is complete. A scan that
 * goes on or that a host lied in answers 0 with how far it got. Testnet4 only, so mainnet `xpub` keys are refused before
 * any request. Call it off the main thread.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultSpendable`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): VaultSpendable

/**
 * The virtual size the 5.1 Send screen prices a payment of the `threshold`-of-`xpubs.len()` sorted multisig at, before
 * `vault_prepare_send` builds the real one: one of its coins paying a P2WSH address, with change, signed by `threshold`
 * keys. 201 for a 2-of-3. Testnet4 only. No network call.
 */
@Throws(MobileSignerException::class)
public expect fun `vaultTypicalVsize`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): kotlin.ULong

/**
 * The keys, how many must sign, receive address 0 and the typical payment size of the wallet `descriptor`
 * describes. Asks nothing of the network.
 */
@Throws(MobileSignerException::class)
public expect fun `walletDescribe`(`descriptor`: kotlin.String, `network`: KeyNetwork): WalletShape

/**
 * Scans the wallet `descriptor` describes, plans a payment of `amount_sats`, or of the whole balance when it
 * is null, to `destination` at `fee_rate_sat_vb` as `hot_quote_send` plans one, and returns the unsigned PSBT
 * with what it pays. Signs nothing and posts nothing.
 */
@Throws(MobileSignerException::class)
public expect fun `walletDraft`(`descriptor`: kotlin.String, `network`: KeyNetwork, `destination`: kotlin.String, `amountSats`: kotlin.ULong?, `feeRateSatVb`: kotlin.ULong): SpendReview

/**
 * The signed transaction for `hot_broadcast`, once `psbt` still pays what `unsigned_txid` named and at least
 * the needed number of distinct keys of the wallet `descriptor` describes have signed every input. Its
 * `amount_sats` is what every output but this wallet's change pays.
 */
@Throws(MobileSignerException::class)
public expect fun `walletFinish`(`descriptor`: kotlin.String, `network`: KeyNetwork, `psbt`: kotlin.String, `unsignedTxid`: kotlin.String): PreparedSend

/**
 * What `hot_wallet_history` answers, for the wallet `descriptor` describes, whose keys are not on this phone.
 */
@Throws(MobileSignerException::class)
public expect fun `walletHistory`(`descriptor`: kotlin.String, `network`: KeyNetwork): List<HotTx>

/**
 * What `psbt` pays and which keys of the wallet `descriptor` describes have signed it, once it is a payment
 * of that wallet. Its change must sit where this phone's scan of the wallet looks, at most 20 indices past the
 * last change proven used, or below 20 while none is. Asks nothing of the network.
 */
@Throws(MobileSignerException::class)
public expect fun `walletReview`(`descriptor`: kotlin.String, `network`: KeyNetwork, `psbt`: kotlin.String): SpendReview

/**
 * Signs `psbt`, a payment of the wallet `descriptor` describes that the user was shown as `expected_txid`, with the
 * key of that wallet the phrase `mnemonic_words` and `passphrase` make, as a device signs one
 * (`WalletLink::sign_wallet_psbt`): refused before any signature when it is not that payment or the phrase makes no
 * key of the wallet, back as it is when that key already signed, and refused unless that key then signed every
 * input. A key of several kept on this phone signs through it, and so does a debug build's stand-in for a device.
 */
@Throws(MobileSignerException::class)
public expect fun `walletSignWithWords`(`descriptor`: kotlin.String, `network`: KeyNetwork, `psbt`: kotlin.String, `expectedTxid`: kotlin.String, `mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String): SpendReview

/**
 * What `hot_wallet_snapshot` answers, for the wallet `descriptor` describes, whose keys are not on this phone.
 */
@Throws(MobileSignerException::class)
public expect fun `walletSnapshot`(`descriptor`: kotlin.String, `network`: KeyNetwork): HotWalletSnapshot

/**
 * One transaction of the wallet `descriptor` describes in full, or None when the explorer does not know `txid`.
 */
@Throws(MobileSignerException::class)
public expect fun `walletTxDetail`(`descriptor`: kotlin.String, `network`: KeyNetwork, `txid`: kotlin.String): HotTxDetail?

