package com.mebit.app.data

import com.mebit.ffi.signer.MnemonicCheck
import com.mebit.ffi.signer.bip39LastWordCandidates
import com.mebit.ffi.signer.bip39Suggest
import com.mebit.ffi.signer.checkMnemonic

/** An [ImportSeedDraft] wired to the Rust BIP-39 helpers. */
fun ffiImportSeedDraft() = ImportSeedDraft(
    suggest = { bip39Suggest(it, 8u) },
    lastWords = { bip39LastWordCandidates(it) },
    check = { words ->
        when (val result = checkMnemonic(words)) {
            MnemonicCheck.Valid -> SeedStatus.Valid
            is MnemonicCheck.UnknownWord -> SeedStatus.InvalidWord(result.index.toInt())
            // All 24 slots are filled before this runs, so a wrong count cannot happen.
            is MnemonicCheck.BadWordCount -> SeedStatus.Incomplete
            MnemonicCheck.InvalidChecksum -> SeedStatus.InvalidChecksum
        }
    },
)
