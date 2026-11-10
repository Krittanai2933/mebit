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

    fun selectedVault(): CreatedVault? =
        createdAddress?.let { address -> created.lastOrNull { it.address == address } }
            ?: created.find { it.id == selectedVaultId }
            ?: created.lastOrNull()
}
