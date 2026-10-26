package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.computeVaultAddressMultisig

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
)

/**
 * Policy → choose keys → review → BSMS file export (no QR).
 * personal_custody only; decaying/timelock surface as T16 boards.
 */
class VaultDraft {
    var m: Int by mutableStateOf(2)
    var n: Int by mutableStateOf(3)
    var policy: CustodyPolicy by mutableStateOf(CustodyPolicy.PersonalCustody)
    val selectedIds = mutableStateListOf<String>()
    var lastError: String? by mutableStateOf(null)
        private set
    var address: String by mutableStateOf("")
        private set
    var bsms: String by mutableStateOf("")
        private set
    val created = mutableStateListOf<CreatedVault>()
    var selectedVaultId: String? by mutableStateOf(null)

    fun selectVault(id: String) {
        selectedVaultId = id
    }

    fun selectedVault(): CreatedVault? = created.find { it.id == selectedVaultId } ?: created.lastOrNull()

    fun setMn(newM: Int, newN: Int): Boolean {
        lastError = null
        if (newM < 1 || newN < 1 || newM > newN) {
            lastError = "M / N ไม่ถูกต้อง · ต้อง 1 ≤ M ≤ N"
            return false
        }
        m = newM
        n = newN
        while (selectedIds.size > n) selectedIds.removeAt(selectedIds.lastIndex)
        return true
    }

    fun toggleKey(id: String) {
        if (id in selectedIds) selectedIds.remove(id)
        else if (selectedIds.size < n) selectedIds.add(id)
    }

    fun selectionComplete(): Boolean = selectedIds.size == n

    fun buildReview(store: KeyringStore): Boolean {
        lastError = null
        if (!selectionComplete()) {
            lastError = "จำนวนกุญแจไม่ตรง · เลือกให้ครบตาม N"
            return false
        }
        val keys = selectedIds.mapNotNull { id -> store.keys.find { it.id == id && it.verified } }
        if (keys.size != n) {
            lastError = "ต้องใช้กุญแจที่ยืนยันแล้วเท่านั้น"
            return false
        }
        return try {
            val addr = computeVaultAddressMultisig(keys.map { it.xpub }, m.toUByte())
            address = addr
            bsms = buildBsms(m, n, keys, addr)
            true
        } catch (e: Exception) {
            lastError = e.message ?: "สร้าง descriptor ไม่สำเร็จ"
            address = ""
            bsms = ""
            false
        }
    }

    fun commit(store: KeyringStore, forceFail: Boolean = false): Boolean {
        lastError = null
        if (forceFail) {
            lastError = "สร้าง Vault ไม่สำเร็จ"
            return false
        }
        if (address.isBlank() || bsms.isBlank()) {
            if (!buildReview(store)) return false
        }
        val fps = selectedIds.mapNotNull { id -> store.keys.find { it.id == id }?.fingerprint }
        fps.forEach { store.markUsedInVault(it) }
        val vault = CreatedVault(
            id = "vault_${created.size + 1}",
            name = "Vault ${created.size + 1}",
            m = m,
            n = n,
            policy = policy,
            address = address,
            bsms = bsms,
            keyFingerprints = fps,
        )
        created.add(vault)
        selectedVaultId = vault.id
        return true
    }

    private fun buildBsms(m: Int, n: Int, keys: List<SavedKey>, address: String): String {
        require(keys.size == n)
        val descriptor = "wsh(sortedmulti($m,${keys.joinToString(",") { descriptorKey(it) }}))"
        val pathLines = keys.joinToString("\n") { "${it.fingerprint} ${it.derivationPath}" }
        return listOf(
            "BSMS 1.0",
            descriptor,
            pathLines,
            "/0/*,/1/*",
            "# mebit personal_custody · receive[0]=$address",
            "# export=file · not QR",
        ).joinToString("\n")
    }

    /** Key origin when the fingerprint and path are known. Otherwise the bare xpub. */
    private fun descriptorKey(key: SavedKey): String {
        val fingerprint = key.fingerprint.trim().lowercase()
        val path = key.derivationPath.trim().removePrefix("m/")
        val fingerprintOk = Regex("^[0-9a-f]{8}$").matches(fingerprint)
        val pathOk = Regex("^\\d+'?(/\\d+'?)+$").matches(path)
        return if (fingerprintOk && pathOk) "[$fingerprint/$path]${key.xpub}" else key.xpub
    }
}
