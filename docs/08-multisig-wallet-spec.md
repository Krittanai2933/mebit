# mebit Multisig Wallet — สเปคเชิงเทคนิค (v1)

สเปคนี้ครอบคลุมเฉพาะขา **Wallet / Custody-as-a-service** ตามที่ปรับ framing ไว้ใน `07-product-vision-mebit.md` เป็นเอกสารระดับวิศวกรรมที่แตกจากคอนเซปต์ create-new/import-existing และ Nunchuk-first/Casa-optional ให้เป็นการตัดสินใจที่ implement ได้จริง

---

## 1. หลักการสถาปัตยกรรม: Key-first (แบบ Nunchuk)

**Key เป็น entity อิสระ ไม่ผูกกับ Wallet ใดโดยเฉพาะ** ผู้ใช้เพิ่มกุญแจเข้า "คลังกุญแจ" ของตัวเองก่อน แล้วค่อยเลือกกุญแจจากคลังมาประกอบเป็น Wallet/Vault ทีหลัง กุญแจตัวเดียวใช้ประกอบได้หลาย vault พร้อมกัน (เช่น hardware wallet ตัวเดียวเป็นทั้งส่วนหนึ่งของ personal vault และของ lending vault คนละสัญญา)

ข้อดีของโมเดลนี้: ไม่ต้องแยก flow "สร้างใหม่" กับ "นำเข้า" ให้เป็นคนละหน้าจอ — ทั้งสองแบบคือ "เพิ่มกุญแจ" ที่มาจากคนละแหล่งเท่านั้น (generate ใหม่ในแอป vs. import xpub จาก hardware wallet ที่มีอยู่แล้ว)

## 2. Data model

```
Key
├── id
├── owner_customer_id
├── label                     -- ชื่อที่ผู้ใช้ตั้ง เช่น "มือถือหลัก", "Coldcard สำรอง"
├── source_type               -- mebit_this_device | mebit_other_device | hardware_wallet
├── hw_vendor                 -- null | jade | trezor (MVP); ledger | coldcard | bitbox02 (phase หลัง)
├── connection_method         -- null | qr | bluetooth (usb ตัดออกจาก MVP)
├── master_fingerprint         -- BIP32 fingerprint (4 bytes)
├── xpub                       -- account-level xpub
├── derivation_path            -- ตาม BIP-48 script_type
├── added_at
└── last_verified_at            -- อัปเดตตอน vault health check ผ่าน

Vault
├── id
├── customer_id
├── vault_type                 -- personal_custody | lending
├── policy_type                 -- plain_multisig | timelock_fallback | decaying_multisig
├── policy_source               -- policy language ที่ป้อนเข้า miniscript compiler (human-readable)
├── miniscript                  -- compiled miniscript
├── descriptor                  -- output descriptor (BIP-380) ที่ derive จาก miniscript
├── script_type                 -- p2wsh | p2sh-p2wsh (fallback) | taproot (phase 2)
├── status                      -- (ตาม state machine เดิมของแต่ละ vault_type)
├── mebit_backup_key_enabled     -- boolean, ผูกกับ subscription
└── created_at

VaultKeys (join table)
├── vault_id
├── key_id
├── position_index              -- ตำแหน่งใน descriptor/policy
└── role                         -- primary | backup_mebit | recovery | timelock_recovery
```

**นัยต่อ lending vault ที่ออกแบบไว้แล้ว** (`01-architecture-overview.md`): เข้ากับโมเดลนี้ได้ตรงๆ — lending vault คือ `Vault` ที่มี `vault_type = lending`, `policy_type = plain_multisig` (2-of-3 คงที่), และ `VaultKeys` 3 แถวที่ role = ผู้กู้/แพลตฟอร์ม/ผู้ให้กู้ ไม่ต้องแก้ schema เดิมที่ร่างไว้ (`customers`, `loans`) มาก แค่เพิ่มชั้น `Key`/`Vault` ทั่วไปคลุมด้านบน

## 3. Script types & derivation

| Script type | สถานะ MVP | หมายเหตุ |
|---|---|---|
| Native SegWit P2WSH (`bc1q...`) | **หลัก** | ใช้กับทั้ง plain multisig และ miniscript policy ทุกแบบใน MVP — compat กว้างสุดกับ hardware wallet 5 ยี่ห้อที่เลือกไว้ |
| Nested SegWit P2SH-P2WSH | fallback (read/import เท่านั้น) | เผื่อ key ที่ import มาจาก setup เก่า ไม่ใช้สร้างใหม่ |
| Legacy P2SH | ไม่รองรับสร้างใหม่ | ล้าสมัย, fee แพง, ไม่อยู่ใน BIP-48 อย่างสะอาด — ถ้าจำเป็นต้อง import จริงๆ ค่อยพิจารณาเพิ่ม adapter เฉพาะทีหลัง |
| Taproot multisig (MuSig2 / `multi_a` in script path) | **Phase 2** (ทันทีหลัง MVP) | ดูหัวข้อ 10 |

Derivation: BIP-48 `m/48'/0'/account'/script_type'/change/index` — `script_type' = 2'` สำหรับ P2WSH (ตามที่ล็อกไว้แล้วในขา lending), `1'` สำหรับ P2SH-P2WSH กุญแจสำรอง mebit (ถ้าถูกเลือกใช้) ก็ derive ตาม path เดียวกับกุญแจอื่นในวอลต์นั้น เพื่อให้ descriptor สอดคล้องกัน

## 4. Policy engine (miniscript) — template ที่รองรับใน MVP

ใช้ `rust-miniscript` เป็น policy compiler: รับ policy language ที่มนุษย์อ่านออก แล้ว compile เป็น miniscript → derive เป็น descriptor

### 4.1 Plain multisig (default)
```
thresh(M, pk(K1), pk(K2), ..., pk(KN))
```
Preset UX: 2-of-3, 3-of-5 เป็นปุ่มลัด, เปิด custom M/N ให้เลือกเองได้ (ครอบคลุม N ถึงประมาณ 6-7 ก่อนชนขนาด script ที่เหมาะสมของ P2WSH)

### 4.2 Timelock fallback / inheritance
```
or(
  thresh(M, pk(K1), pk(K2), ..., pk(KN)),
  and(pk(K_recovery), older(T))
)
```
ตัวอย่างจริง: ปกติต้อง 2-of-3 เซ็น แต่ถ้าไม่มีการเคลื่อนไหว/เซ็นเลยนานเกิน `T` บล็อก (เช่น `older(52560)` ≈ 1 ปี) กุญแจสำรอง (`K_recovery` — อาจเป็นกุญแจของทายาทหรือกุญแจสำรองอีกชุด) เซ็นคนเดียวพอ ใช้กับ use case มรดก/กู้คืนกรณีกุญแจหายเกิน threshold

### 4.3 Decaying multisig
```
or(
  thresh(M1, pk(K1)...pk(KN)),
  and(older(T1), thresh(M2, pk(K1)...pk(KN)))
)
```
ตัวอย่าง: 3-of-5 ตามปกติ, ผ่านไป `T1` บล็อกลดเป็น 2-of-5

**ข้อควรระวังเชิงเทคนิค**: การทำ decaying หลายระดับ (มากกว่า 2 branch) บน P2WSH จะทำให้ witness script ใหญ่ขึ้นเร็วและชน practical size limit ได้ง่าย เพราะทุก branch ต้องอยู่ใน script เดียวกัน แนะนำ**MVP รองรับแค่ 2 ระดับ (ปกติ + fallback เดียว) บน P2WSH ก่อน** ส่วน decaying แบบหลายขั้นจริงจัง (3+ ระดับ) ควรรอไป Taproot ใน phase 2 เพราะแต่ละ branch แยกเป็น script leaf ของตัวเอง ไม่ต้องรวมอยู่ใน witness เดียวกันทั้งหมด ถูกและ private กว่ามาก

## 5. Key source & hardware wallet support

**อัปเดต (ล็อกแล้ว)**: MVP รองรับแค่ **QR และ Bluetooth เท่านั้น ไม่ทำ USB** — ตัดสินใจแล้วว่ายอมรับ trade-off เรื่องความครอบคลุมของ Trezor เพื่อความเรียบง่ายของ engineering (ไม่ต้องเขียน native USB/HID bridge ที่ยุ่งยากที่สุดในทุก stack)

**Source ที่รองรับตั้งแต่ MVP**: mebit บนอุปกรณ์นี้, mebit บนอุปกรณ์อื่น (multi-device, จับคู่ผ่าน QR), hardware wallet จริง

**Hardware wallet ที่รองรับ MVP (ตัดเหลือ 2 ยี่ห้อ)**:

| ยี่ห้อ | รุ่นที่รองรับ | ช่องทาง |
|---|---|---|
| **Jade** (Blockstream) | Jade Classic / Jade Plus | QR (มีกล้องในตัว) + BLE |
| **Jade** (Blockstream) | **Jade Core** | **BLE เท่านั้น** — ไม่มีกล้องและไม่มีแบตเตอรี่ (ต้องเสียบไฟ USB ตลอดที่ใช้งาน) |
| **Trezor** | **เฉพาะ Safe 7** เท่านั้น | BLE เท่านั้น |

**อัปเดต 2026-09-30**: เครื่องที่ทีมมีจริงคือ **Jade Core** ซึ่งไม่มีกล้อง (ใน firmware คือ board `JADE_V2C` — "no camera, no battery") จึงใช้ flow QR ไม่ได้เลย Phase 0 จึงพิสูจน์ Jade ผ่าน **BLE** แทน ซึ่งทำเสร็จและทดสอบกับเครื่องจริงแล้ว (ดู `05-progress-and-next-steps.md`) ส่วน Jade แบบ QR (รุ่นที่มีกล้อง) ยังไม่ได้ implement และยังไม่ได้ตัดสินใจว่าจะทำใน MVP หรือไม่

**ข้อจำกัดสำคัญที่ต้องรู้และสื่อสารกับลูกค้า**: Trezor **ไม่มีกล้อง ไม่รองรับ QR-based air-gapped signing เลยสักรุ่น** (ยืนยันแล้วแม้ Safe 7 รุ่นล่าสุดก็ยังต้องต่อผ่าน USB-C หรือ BLE เท่านั้น) และมีแค่ **Safe 7** (ออกตุลาคม 2025) ที่มี Bluetooth — Trezor รุ่นอื่นทั้งหมด (One, T, Safe 3, Safe 5) มี USB อย่างเดียว จึง**ใช้กับ mebit ไม่ได้เลย**ภายใต้ MVP ที่ตัด USB ออก ต้องแจ้งลูกค้าให้ชัดว่ารองรับ Trezor รุ่นพรีเมียมล่าสุดเท่านั้น

**อัปเดต 2026-10-01**: ใน firmware ของ Trezor มีรุ่นที่สองที่มี BLE แล้ว คือ `T3T2` (`MODEL_BLE_CODE 7`) "เป็น Trezor ที่ต่อผ่าน Bluetooth" จึงไม่ได้แปลว่าเป็น Safe 7 อีกต่อไป
- client ของ mebit รับเฉพาะ Safe 7 แบบตรงตัว (internal model `T3W1`, model "Safe 7")
- ตรวจสองชั้นทุกครั้งที่เชื่อมต่อ: ชั้นแรกก่อน handshake ชั้นที่สองจาก `Features`
- ไม่ใช้ชื่อหรือ advertisement เป็นหลักฐาน

ดู `04-open-items.md` ข้อ 17 และ README ของ `trezor-ble`

**Ledger, Coldcard, BitBox02** — เลื่อนออกจาก MVP ไปเฟสหลัง (เดิมอยู่ในสเปคเวอร์ชันแรก ตอนนี้ตัดออกเพื่อโฟกัส 2 ยี่ห้อที่ใช้ QR/BLE ได้จริงตาม MVP ที่ตัด USB)

### 5.1 Connection method
- **QR / กล้อง** — สแกน PSBT ไป-กลับ สำหรับ Jade รุ่นที่มีกล้อง (ยังไม่ได้ implement — ดูหมายเหตุใต้ตารางข้างบน)
- **Bluetooth (BLE)** — สำหรับ Jade และ Trezor Safe 7 — **อัปเดต 2026-09-30 (ตัดสินใจแล้ว)**: ฝั่ง Jade ให้ **Rust เป็นเจ้าของ BLE ทั้งหมด** ทั้ง Phase 0 และ production mobile — crate `jade-ble` (ใช้ `btleplug`) ขับ `vault-core::hw::jade::JadeSession` แล้วแอป RN เรียกผ่าน UniFFI (`unlock` / `xpub` / `signPsbt` แบบ async) **แทน**แผนเดิมที่จะใช้ `react-native-ble-plx` ฝั่ง RN เหตุผล: protocol และ BLE อยู่ในที่เดียว ทดสอบได้ใน Rust และไม่ต้องเขียน BLE ซ้ำต่อ platform — ต้นทุนที่ต้องรู้: `btleplug` บน Android ต้อง build แบบ Rust+Java (ดู `04-open-items.md` ข้อ 14) **อัปเดต 2026-10-01 (ตัดสินใจแล้ว)**: Trezor Safe 7 ใช้แนวทางเดียวกัน คือ `vault-core::hw::trezor` (protocol ล้วน ไม่มี I/O บน crate `trezor-thp` ของ Trezor เอง) คู่กับ crate `trezor-ble` (btleplug 0.13) — ไม่ใช้ `trezor-client` ด้วยเหตุผลใน `04-open-items.md` ข้อ 16 — ต้นทุนที่ต้องรู้: ใน Android หนึ่งแอปต้องมี btleplug เวอร์ชันเดียว (ข้อ 18)
- **USB** — ไม่ทำใน MVP (ตัดออกทั้งหมดตามการตัดสินใจนี้)

### 5.2 ข้อจำกัดของ Jade ผ่าน BLE (ยืนยันจาก source code ของ firmware 1.0.41 และจากเครื่องจริง)

- **ไม่ใช่ air-gapped** — BLE คือการเชื่อมต่อทางวิทยุที่จับคู่ (pair) ไว้ การจับคู่ครั้งแรกเป็นแบบ numeric comparison: ตัวเลข 6 หลักขึ้นทั้งบน Jade และบนเครื่อง host ผู้ใช้ต้องดูว่าตรงกันแล้วกดยืนยันทั้งสองฝั่ง
- **ปลดล็อกด้วย PIN ต้องออนไลน์** — host ต้อง relay ข้อมูลไป-กลับ pinserver ของ Blockstream ผ่าน HTTPS (ข้อมูลเข้ารหัสระหว่าง Jade กับ pinserver เอง host แค่ส่งต่อ)
- **เครือข่ายถูกผูกกับ wallet** — การปลดล็อกครั้งแรกผูก wallet กับ mainnet หรือกับ testnet ถาวร และ Jade Core ไม่มีเมนูสลับกลับ (เมนูนี้มีเฉพาะใน QR mode ซึ่งต้องใช้กล้อง) ถ้าจะเปลี่ยนต้อง factory reset แล้ว restore ใหม่
- **ปลดล็อกได้ครั้งละการเชื่อมต่อ** — wallet ที่มี PIN จะล็อกตัวเองทุกครั้งที่ BLE หลุด การเชื่อมต่อใหม่ต้องใส่ PIN ใหม่
- **ไม่ต้อง register multisig เพื่อเซ็น** — แต่ถ้าไม่ register Jade จะแสดง change ของ vault เป็น output ธรรมดา ผู้ใช้จึงตรวจบนหน้าจอ Jade ไม่ได้ว่าเป็นของตัวเอง (ดู `04-open-items.md` ข้อ 13)

## 6. Format มาตรฐาน

- **Output descriptor (BIP-380)** เป็นภาษากลางภายในระบบสำหรับอธิบาย vault ทุกชุด (ทั้ง personal custody และ lending) — ให้ vault-core engine เดียวจัดการทั้งหมดได้
- **BSMS (BIP-129)** เป็นมาตรฐานหลักสำหรับ export/import ข้อมูล wallet config ระหว่างอุปกรณ์ — เข้ากับ hardware wallet ส่วนใหญ่ที่รองรับ QR/ไฟล์อยู่แล้ว, ใช้ raw descriptor string เป็น fallback
- **PSBT**: ใช้ PSBTv0 (BIP-174) เป็นฟอร์แมตหลักสำหรับความเข้ากันได้กับ hardware wallet/coordinator อื่นในตลาดปี 2026 (ยังไม่ทุกยี่ห้อรองรับ PSBTv2 เต็มที่) ส่วน PSBTv2 (BIP-370) พิจารณาใช้ภายใน engine เองถ้าช่วยเรื่อง manual UTXO selection แต่ต้อง convert กลับเป็น v0 ตอนส่งออกให้อุปกรณ์ signer

## 7. MVP scope: add-key ทีละตัว vs import ทั้ง vault ทีเดียว

จากโมเดล key-first (ข้อ 1) "การนำเข้ากุญแจจาก hardware wallet ที่มีอยู่แล้ว" **อยู่ใน MVP** อยู่แล้ว (เป็นแค่อีก source_type หนึ่งของ "เพิ่มกุญแจ")

สิ่งที่**เลื่อนไปเฟสหลัง**คือ: การ**นำเข้า vault ที่ประกอบสำเร็จรูปแล้วทั้งชุดในทีเดียว** (เช่น paste ไฟล์ BSMS/descriptor ที่มี 3-5 กุญแจ + policy ที่ config ไว้แล้วจากที่อื่นทั้งหมด ไม่ผ่านขั้นตอน add-key ทีละตัวของ mebit) เพราะต้องมี adapter parse export format เฉพาะของแต่ละที่มา (Sparrow, Nunchuk, hardware wallet vendor JSON) แบบ end-to-end ซึ่งเป็นงานเพิ่มเติมจาก add-key flow ปกติ

## 8. กุญแจสำรอง mebit (Casa-style, subscription)

- เป็นออปชันเปิดเผยตอนประกอบ vault: "เพิ่มกุญแจสำรองของ mebit เป็นอีก 1 ใน N" — มีค่าใช้จ่ายแบบ subscription
- Derive ตาม BIP-48 path เดียวกับกุญแจอื่นในวอลต์นั้น เก็บแยกเป็น `Key` ที่ `source_type = mebit_backup`, ผูกกับ `Vault.mebit_backup_key_enabled`
- **นัยกฎหมาย**: เพราะผูกกับรายได้แล้ว คำถาม custodian ที่เคยตัดออกไปคุยทีหลัง (ดู `04-open-items.md`/`07-product-vision-mebit.md`) ตอนนี้มีความเร่งด่วนมากขึ้น ควรเอาเข้าคิวคุยกับที่ปรึกษากฎหมายเร็วขึ้น ไม่ใช่ปล่อยไว้เรื่อยๆ

## 9. Vault health check & backup/export

- **Health check**: เช็คว่าทุก `Key` ใน vault ยัง valid (fingerprint/xpub derive address ตรงกับที่ descriptor คาดไว้) เป็นระยะ — เสนอ trigger 3 จุด: ทุกครั้งที่เปิดแอปหลังจากผ่านไป ≥90 วันจาก `last_verified_at`, ทุกครั้งที่เพิ่ม/ลบ vault ใหม่, และแบบ manual จากปุ่มใน Settings สำหรับ hardware wallet ต้อง prompt ให้เชื่อมต่อจริงเพื่อยืนยัน liveness ไม่ใช่เช็คจาก xpub ที่ cache ไว้เฉยๆ
- **Backup/export**: ผู้ใช้ export descriptor + policy statement (BSMS) ของ vault ตัวเองได้เสมอ ไม่ผูกกับ mebit — ตรงกับหลัก self-custody จริงที่เป็นแกนของทั้งโปรเจกต์ ควร nudge ให้ผู้ใช้เก็บสำเนานี้ไว้นอกแอปด้วย (ปริ๊นต์/เก็บไฟล์แยก) ไม่ใช่พึ่งพา mebit เก็บให้อย่างเดียว

## 10. Roadmap: MVP → Phase 2

| เฟส | สิ่งที่ทำ |
|---|---|
| Phase 0 (engine validation, Rust CLI + automated tests, ไม่มี GUI/desktop app) | vault-core ล้วนๆ: descriptor generation, plain multisig P2WSH, PSBT construction/signing จริงกับ Jade Core (BLE) และ Trezor Safe 7 (BLE) บน testnet — ยังไม่มี mobile UI และไม่ทำ desktop app เลย |
| MVP (production mobile, React Native + UniFFI) | Key-first add-key flow (mebit multi-device ผ่าน QR + Jade + Trezor Safe 7 เท่านั้น, QR+BLE ไม่มี USB), P2WSH เท่านั้น, policy: plain multisig + timelock fallback (2 ระดับ) + decaying multisig (2 ระดับ), preset 2-of-3/3-of-5 + custom, กุญแจสำรอง mebit แบบ subscription, vault health check, backup/export ผ่าน BSMS |
| Phase 2 (ทันทีหลัง MVP) | Taproot multisig (MuSig2/`multi_a`), decaying multisig หลายระดับ (3+ branch) ใช้ script leaf ของ Taproot, bulk-import vault ที่ config ไว้แล้วทั้งชุดจากที่อื่น |
| ยังไม่กำหนดเวลา | รองรับ USB (เปิดทาง Trezor รุ่นอื่นที่ไม่ใช่ Safe 7 + Ledger/Coldcard/BitBox02), legacy P2SH import adapter (ถ้าจำเป็นจริง) |

## 11. ความสัมพันธ์กับเอกสารเดิม (01–07)

- ไม่ชนกับ `01-architecture-overview.md`/`02`/`03` — lending vault ยังเป็น 2-of-3 คงที่แบบเดิม แค่ตอนนี้มองเป็น `Vault` instance หนึ่งภายใต้โมเดล Key-first ทั่วไปที่นี่
- `06-mobile-app-design-mebit.md` และ app flow v3 (คุยกันด้วยวาจา/ผ่าน artifact) ต้องอัปเดตหน้า "สร้าง Vault" ให้เป็น "เพิ่มกุญแจ" ก่อน แล้วค่อย "ประกอบ Vault" แยกขั้นตอน ตามโมเดลนี้ — ยังไม่ได้แก้ไดอะแกรมจริง รอ session หน้า
- `bdk` (Bitcoin Dev Kit) ยังเป็นตัวเลือกฐานของ wallet engine ตามที่แนะนำไว้ใน `06` — รองรับ descriptor-based wallet และ PSBT อยู่แล้ว แต่ทีมต้องเช็คว่า miniscript policy compiler (`rust-miniscript`) integrate กับ `bdk` เวอร์ชันที่จะใช้ได้ลื่นแค่ไหน เป็นงานวิจัยเชิงเทคนิคที่ต้องทำก่อนเริ่ม implement จริง

## 12. Open items ที่ยังไม่ล็อก

1. Format backup/export ที่ไม่ใช่ BSMS สำหรับ hardware wallet ที่ไม่รองรับ BSMS (ต้องเขียน adapter เฉพาะยี่ห้อ)
2. รายละเอียด UX ของ "เพิ่มกุญแจ" ต่อยี่ห้อ (แต่ละยี่ห้อ export xpub/fingerprint คนละฟอร์แมต)
3. ราคา/เงื่อนไข subscription ของกุญแจสำรอง mebit
4. คำถามกฎหมาย custodian สำหรับกุญแจสำรอง mebit (ผูกกับข้อ 3 — เร่งด่วนขึ้นแล้วเพราะมีรายได้ผูกอยู่)
5. งานวิจัยความเข้ากันได้ของ `rust-miniscript` policy compiler กับ `bdk`
