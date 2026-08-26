# mebit Wallet — แผนเริ่มพัฒนา (MVP Build Plan)

เอกสารนี้สรุปข้อสรุปเรื่อง stack และลำดับการพัฒนา หลังตัดสินใจว่า**ขา Wallet/Custody คือสิ่งที่ต้องทำก่อนขาอื่น** (ก่อน buy-sell และ lending) ต่อจาก `07-product-vision-mebit.md` และสเปคเทคนิคใน `08-multisig-wallet-spec.md`

---

## 1. หลักการ: พิสูจน์ core engine ก่อนแตะ UI

ความเสี่ยงที่สุดของโปรเจกต์นี้คือ crypto/multisig logic ไม่ใช่หน้าจอ ดังนั้นไม่เริ่มจากแอปมือถือ แต่เริ่มจาก vault-core (Rust) ที่พิสูจน์ตัวเองบน Bitcoin testnet ก่อน แล้วค่อยห่อด้วย UI ทีหลัง — **และไม่ทำ desktop app เลยในทุกเฟส (ตัดสินใจล็อกแล้ว)** Phase 0 จึงเป็นแค่ Rust CLI/automated tests ไม่มีหน้าตาให้ใช้งานจริง

## 2. Stack ที่ตัดสินใจแล้ว

| ชั้น | เครื่องมือ | บทบาท |
|---|---|---|
| **Phase 0 — engine validation** | Rust CLI + automated test suite (ไม่มี GUI, ไม่มี desktop app) | vault-core ล้วนๆ พิสูจน์ตัวเองบน testnet ผ่าน `cargo run`/`cargo test` เท่านั้น — ไม่ต้องมี glue code, ไม่ต้องมี UI framework ใดๆ |
| **Production mobile** | React Native + UniFFI | vault-core (Rust) ผูกผ่าน UniFFI เข้ากับ RN — เลือก RN เพราะทีมมีพื้นจาก Next.js อยู่แล้ว เรียนรู้โค้ดฝั่ง UI ได้เร็วกว่า Flutter/Dart |
| **Hardware wallet transport (มือถือ)** | native module ของ RN (Kotlin/Swift) เชื่อม BLE + กล้องสำหรับ QR | เขียนเองต่อยี่ห้อ ไม่มี library สำเร็จรูปข้าม platform ที่ครบ |

**เหตุผลที่ไม่ใช้ Tauri**: เดิมเคยเสนอ Tauri desktop สำหรับ Phase 0 เพราะ Rust-native ไม่ต้องมี glue code — แต่ผู้ใช้ปฏิเสธชัดเจนว่าไม่ต้องการทำ desktop app เลย (ไม่ใช่แค่ production ไม่เอา แต่รวมถึงเครื่องมือ internal validation ด้วย) จึงตัด Tauri ออกทั้งหมด ใช้ Rust CLI ธรรมดาแทนสำหรับ Phase 0 ส่วน production mobile ยืนตาม React Native + UniFFI เหมือนเดิม (BLE ใช้ `react-native-ble-plx` ที่วงการ Bitcoin wallet ใช้จริงมานาน)

## 3. Hardware wallet scope (ตัดสินใจแล้ว — ล็อก)

**รองรับแค่ QR และ Bluetooth เท่านั้น ไม่ทำ USB เลยใน MVP**

| ยี่ห้อ | รุ่น | ช่องทาง |
|---|---|---|
| Jade (Blockstream) | ทุกรุ่น | QR + BLE |
| Trezor | **Safe 7 เท่านั้น** | BLE เท่านั้น |

**ข้อจำกัดที่ต้องสื่อสารกับลูกค้าให้ชัด**: Trezor ไม่มีกล้อง ไม่รองรับ QR เลยสักรุ่น (ยืนยันแล้วว่า Safe 7 ก็ยังต้องต่อผ่าน USB-C หรือ BLE) และมีแค่ Safe 7 ที่มี Bluetooth ดังนั้น Trezor รุ่นอื่น (One, T, Safe 3, Safe 5) **ใช้กับ mebit ไม่ได้เลย** ในเวอร์ชันนี้ ต้องระบุในหน้า marketing/support ว่ารองรับ "Trezor Safe 7" เจาะจง ไม่ใช่ "Trezor" แบบกว้างๆ

Ledger, Coldcard, BitBox02 และการรองรับ USB ทั้งหมด — เลื่อนไปเฟสหลัง (ดู `08-multisig-wallet-spec.md` หัวข้อ 10)

## 4. Library ที่เช็คสถานะไว้แล้ว

- **Trezor**: มี `trezor-client` crate อย่างเป็นทางการใน `trezor/trezor-firmware/rust/trezor-client` — ใช้ได้เลย ความเสี่ยงต่ำ
- **Jade**: ไม่มี Rust crate สำเร็จรูป ต้องเขียน client เองจาก protocol ที่ Blockstream เปิดไว้เป็น public (`github.com/blockstream/jade`) — เริ่มจากโหมด **QR ก่อน** (อ่าน/เขียน PSBT เป็น QR ไป-กลับ ตาม BIP-174) เพราะง่ายกว่าการเขียน BLE JSON-RPC client เอง ค่อยทำ BLE ทีหลังถ้าจำเป็น
- **BLE สำหรับ Phase 0 (Rust CLI)**: ใช้ `btleplug` ตรงๆ (ไม่ต้องพึ่ง Tauri plugin เพราะไม่มี Tauri/desktop app แล้ว) ส่วน production mobile ค่อยใช้ `react-native-ble-plx` ผ่าน native module ของ RN

## 5. ลำดับการพัฒนา

### Phase 0 — พิสูจน์ engine (Rust CLI + automated tests, ไม่มี GUI/desktop app, ไม่มี mobile UI)

1. ตั้ง `vault-workspace/vault-core` เป็น Rust crate
2. Derive BIP-48 xpub (script_type `2'` = P2WSH) จำลอง 2 กุญแจ
3. ประกอบ descriptor P2WSH plain multisig (`sortedmulti`) — ข้าม miniscript policy (timelock/decay) ไปก่อน
4. เชื่อม Jade จริงผ่าน QR (อ่าน/ส่ง PSBT) และ Trezor Safe 7 จริงผ่าน BLE (ใช้ `trezor-client`)
5. ฝาก testnet BTC เข้า vault address, สร้าง PSBT, เซ็นจากทั้งสองอุปกรณ์จริง, broadcast — ทำผ่าน CLI command/สคริปต์ทดสอบ ไม่ต้องมีหน้าจอ

**เกณฑ์ผ่านเฟสนี้**: เห็นธุรกรรม multisig ที่เซ็นจาก Jade + Trezor Safe 7 จริง ยืนยันบน testnet ทั้งหมดรันจาก Rust CLI/test suite ตัวเดียว ไม่มีแอป desktop หรือ GUI ใดๆ เกี่ยวข้อง

### Phase 1 — thin slice ขึ้นมือถือ (React Native + UniFFI)

1. ห่อ vault-core ด้วย UniFFI
2. เขียน native module ของ RN สำหรับ BLE (Jade + Trezor Safe 7) และกล้อง/QR (Jade)
3. ทำ flow เดียวให้จบ: เพิ่มกุญแจ (เครื่องนี้) → เพิ่มกุญแจที่สอง (Jade หรือ Trezor Safe 7) → สร้าง vault → รับ/ส่ง BTC จริงบน testnet

### Phase 2 — ใส่ UI เต็มตามดีไซน์ที่ยืนยันแล้ว

Implement ทั้ง 11 หน้าตามดีไซน์ Claude Design ที่ตรวจแล้ว (เพิ่มกุญแจ → จับคู่/hardware wallet → ยืนยันกุญแจ → คลังกุญแจ → เลือก Policy 3 ขั้น → Vault สร้างสำเร็จ → เลือก Vault → รายละเอียด Vault) — งานเสี่ยงต่ำกว่า phase 0-1 มาก เพราะเป็น UI ประกบ engine ที่พิสูจน์แล้ว

### Phase 3 — advanced policy + เชื่อมขา lending

เติม timelock fallback, decaying multisig (miniscript), Taproot (phase 2 ของ `08`), แล้วย้อนไปต่อกับ vault-core ของขา lending (`01-06`)

## 6. สิ่งที่ยังไม่ล็อก

1. จะเปิดรองรับ USB (และ Trezor รุ่นอื่น/Ledger/Coldcard/BitBox02) เมื่อไหร่
2. ต้องเขียน Jade Rust client เอง — ประเมิน effort จริงหลัง spike เบื้องต้น

*(ตัดข้อ "จะลอง Tauri mobile ไหม" ออกแล้ว — ล็อกแล้วว่าไม่ทำ desktop app ในทุกเฟส ใช้ Rust CLI สำหรับ Phase 0 และ React Native + UniFFI สำหรับ production mobile เท่านั้น)*
