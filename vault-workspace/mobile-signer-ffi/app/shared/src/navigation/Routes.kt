package com.mebit.app.navigation

enum class MovieRoute(
    val path: String,
    val eyebrow: String,
    val titleTh: String,
    val bodyTh: String,
) {
    Welcome(
        "welcome",
        "WELCOME",
        "Bitcoin ของคุณ Key อยู่ในมือคุณ",
        "สร้างกระเป๋าใหม่ หรือนำเข้า seed",
    ),
    KeySource(
        "onboarding/key-source",
        "NEW WALLET",
        "เก็บกุญแจดอกแรกไว้ที่ไหน",
        "มือถือ · Hardware wallet",
    ),
    KeyCreated(
        "onboarding/key-created",
        "KEY CREATED",
        "สร้างกุญแจแล้ว",
        "สำรอง 24 คำก่อนรับเงิน",
    ),
    BackupWords(
        "onboarding/backup-words",
        "BACKUP",
        "จด 24 คำสำรอง",
        "เขียนลงกระดาษ อย่าถ่ายรูป",
    ),
    VerifyWords(
        "onboarding/verify-words",
        "VERIFY WORDS",
        "ยืนยันคำสำรอง",
        "เลือกคำตามลำดับให้ถูก",
    ),
    AppLock(
        "onboarding/app-lock",
        "APP LOCK",
        "ล็อกแอป",
        "เปิด Face ID หรือรหัสผ่านเครื่อง",
    ),
    HomeFirstRun(
        "home/first-run",
        "HOME",
        "จะเริ่มใช้แบบไหน",
        "กระเป๋ากุญแจเดียว หรือ Vault",
    ),
    HwBackupConfirm(
        "onboarding/hw-backup-confirm",
        "HW BACKUP",
        "ยืนยันสำรองบนอุปกรณ์",
        "Jade / Trezor Safe 7 ผ่าน BLE",
    ),
    AddKey(
        "keys/add",
        "ADD KEY",
        "เพิ่มกุญแจ",
        "mebit บนเครื่องนี้ · เครื่องอื่น · HW",
    ),
    PairMebitOther(
        "keys/pair-mebit",
        "PAIR DEVICE",
        "จับคู่เครื่องอื่น",
        "วาง xpub หรือ BSMS 1.0 ไม่ใช้ QR",
    ),
    HardwareWallet(
        "keys/hardware",
        "HARDWARE WALLET",
        "เชื่อมต่ออุปกรณ์",
        "BLE จริง · จำลองเมื่อไม่มีวิทยุ",
    ),
    VerifyKey(
        "keys/verify",
        "VERIFY KEY",
        "ยืนยัน fingerprint",
        "ตรง บันทึก · ไม่ตรง ไม่บันทึก",
    ),
    Keyring(
        "keys/keyring",
        "KEYRING",
        "คลังกุญแจ",
        "กุญแจที่ยืนยันแล้ว",
    ),
    KeyringFirstVault(
        "keys/keyring-first-vault",
        "NEW VAULT",
        "รวมกุญแจสำหรับ Vault",
        "แนะนำ 3 ดอกสำหรับ 2-of-3",
    ),
    Policy(
        "vault/policy",
        "POLICY · 1/3",
        "Vault นี้ใช้กุญแจแบบไหน",
        "personal_custody เท่านั้น",
    ),
    ChooseKeys(
        "vault/choose-keys",
        "POLICY · 2/3",
        "เลือกกุญแจ",
        "เลือกจากคลังตาม M-of-N",
    ),
    Review(
        "vault/review",
        "POLICY · 3/3",
        "ตรวจก่อนสร้าง",
        "build descriptor จริง",
    ),
    VaultCreated(
        "vault/created",
        "VAULT CREATED",
        "Vault พร้อมใช้งาน",
        "BSMS ไฟล์ ไม่ใช่ QR",
    ),
    VaultList(
        "vaults",
        "VAULTS",
        "Vault ของคุณ",
        "personal_custody",
    ),
    VaultDetail(
        "vaults/detail",
        "VAULT DETAIL",
        "รายละเอียด Vault",
        "รับ · ส่ง",
    ),
    T13UnsupportedModel(
        "critical/t13",
        "UNSUPPORTED MODEL",
        "รุ่นนี้ไม่รองรับ",
        "เฉพาะ Trezor Safe 7",
    ),
    T14VerifyMismatch(
        "critical/t14",
        "VERIFY KEY",
        "Fingerprint ไม่ตรง",
        "ไม่บันทึกเข้าคลัง",
    ),
    T15KeyringEmpty(
        "critical/t15-empty",
        "KEYRING",
        "ยังไม่มีกุญแจในคลัง",
        "เพิ่มกุญแจก่อน",
    ),
    T15UnverifiedFirst(
        "critical/t15-unverified",
        "KEYRING",
        "กุญแจยังไม่ยืนยัน",
        "ยืนยัน fingerprint ก่อน",
    ),
    T15DeleteBlocked(
        "critical/t15-delete",
        "KEYRING",
        "ลบกุญแจไม่ได้",
        "ยังถูกใช้ใน Vault",
    ),
    T16PolicyDecaying(
        "critical/t16-decaying",
        "POLICY · ADVANCED",
        "decaying multisig",
        "mutually exclusive กับ timelock",
    ),
    T16InvalidMn(
        "critical/t16-mn",
        "POLICY",
        "M / N ไม่ถูกต้อง",
        "ต้อง 1 ≤ M ≤ N",
    ),
    T17CountMismatch(
        "critical/t17",
        "COMPOSE VAULT",
        "จำนวนกุญแจไม่ตรง",
        "เลือกให้ครบตาม N",
    ),
    T18BackendError(
        "critical/t18",
        "CONFIRM VAULT",
        "สร้าง Vault ไม่สำเร็จ",
        "ลองอีกครั้ง",
    ),
    T20VaultActions(
        "critical/t20",
        "VAULT ACTIONS",
        "การกระทำของ Vault",
        "BSMS · health check",
    ),
    S1HomeSingleSig(
        "send/s1",
        "SINGLE-SIG",
        "กระเป๋าใช้จ่าย",
        "Home กุญแจเดียว",
    ),
    S2ReceiveBackupGate(
        "send/s2",
        "RECEIVE",
        "สำรองกุญแจก่อนรับเงิน",
        "backup gate",
    ),
    S3Receive(
        "send/s3",
        "RECEIVE",
        "รับ Bitcoin",
        "ที่อยู่รับ",
    ),
    S4Send(
        "send/s4",
        "SEND",
        "ส่ง Bitcoin",
        "จำนวน · ค่าธรรมเนียม",
    ),
    S5ReviewSign(
        "send/s5",
        "REVIEW & SIGN",
        "ตรวจแล้วเซ็น",
        "เซ็นบนเครื่องนี้",
    ),
    S6Sent(
        "send/s6",
        "SENT",
        "ส่งสำเร็จ",
        "แชร์ใบเสร็จ",
    ),
    S6SentSlip(
        "send/s6b",
        "SENT · SLIP",
        "ใบเสร็จแบบสลิป",
        "รูปแบบแชร์ B",
    ),
    S6SentBlock(
        "send/s6c",
        "SENT · BLOCK",
        "ใบเสร็จแบบบล็อก",
        "รูปแบบแชร์ C",
    ),
    S6SentPoster(
        "send/s6d",
        "SENT · POSTER",
        "ใบเสร็จแบบโปสเตอร์",
        "รูปแบบแชร์ D",
    ),
    M1SendVault(
        "send/m1",
        "SEND · VAULT",
        "ส่งจาก Vault",
        "ต้องเซ็น M จาก N",
    ),
    M2CollectSignatures(
        "send/m2",
        "COLLECT SIGNATURES",
        "เก็บลายเซ็น",
        "เลือกกุญแจที่พร้อมเซ็น",
    ),
    M3SignOnJade(
        "send/m3",
        "SIGN ON JADE",
        "เซ็นบน Jade",
        "Bluetooth เท่านั้น",
    ),
    M4ReadyToBroadcast(
        "send/m4",
        "READY TO BROADCAST",
        "พร้อมส่งธุรกรรม",
        "ลายเซ็นครบแล้ว",
    ),
    ;

    companion object {
        val start: MovieRoute = Welcome
        val demoOrder: List<MovieRoute> = entries
    }

    fun nextInDemo(): MovieRoute {
        val i = demoOrder.indexOf(this)
        return demoOrder[(i + 1) % demoOrder.size]
    }
}
