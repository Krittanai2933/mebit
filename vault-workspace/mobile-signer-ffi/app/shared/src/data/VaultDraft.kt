package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class CustodyPolicy {
    PersonalCustody,
}

data class CreatedVault(
    val id: String,
    val name: String,
    val m: Int,
    val n: Int,
    val policy: CustodyPolicy,
    val address: String,
    val bsms: String,
    val keyFingerprints: List<String>,
    val hasMockKey: Boolean = false,
    /** The saved key ids picked at creation, in [keyFingerprints] order, so a signer row names the device the user picked. Empty for a wallet saved before MV2. */
    val keyIds: List<String> = emptyList(),
    /** The send in progress, a base64 PSBT, "" for none. Only the several-key send writes it. */
    val pending: String = "",
)

/** The vaults in the book and the one the vault screens show (detail, Receive, Send, T20, Wallet Created). The wizard that makes one is [CreateWalletDraft]. */
class VaultDraft(private val book: SavedVaults = SavedVaults.memoryOnly()) {
    val created: List<CreatedVault> get() = book.current.vaults
    var selectedVaultId: String? by mutableStateOf(null)

    /** The address [CreateWalletDraft.create] returned. A deferred book edit can replay and renumber the new vault, so an id alone may name an older one. */
    private var createdAddress: String? by mutableStateOf(null)

    fun selectVault(id: String) {
        selectedVaultId = id
        createdAddress = null
    }

    fun noteCreated(made: CreatedVault) {
        selectedVaultId = made.id
        createdAddress = made.address
    }

    /**
     * Removes the vault [id] from the book and keeps every key, so its BSMS file and keys can restore it. False, with the vault still listed,
     * when the removal could not be saved. A second call changes nothing.
     */
    fun remove(id: String): Boolean {
        val gone = created.find { it.id == id }
        if (!book.editSaved { b -> b.copy(vaults = b.vaults.filterNot { it.id == id }) }) return false
        if (selectedVaultId == id) selectedVaultId = null
        if (gone != null && createdAddress == gone.address) createdAddress = null
        return true
    }

    fun selectedVault(): CreatedVault? =
        createdAddress?.let { address -> created.lastOrNull { it.address == address } }
            ?: created.find { it.id == selectedVaultId }
            ?: created.lastOrNull()
}
