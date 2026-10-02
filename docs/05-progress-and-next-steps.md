# สถานะความคืบหน้าและสิ่งที่ต้องทำต่อ

> อัปเดตล่าสุด: 2026-09-30 — เอกสารนี้เป็น living document ทีมควรอัปเดตทุกครั้งที่มี milestone ใหม่ อย่าปล่อยให้ล้าสมัยจนไม่ตรงกับโค้ดจริง (ตรวจสอบกับ `git log`/โค้ดจริงก่อนเชื่อว่าสถานะยังถูกต้อง)

ภาพรวม ณ ตอนนี้: ทุกโมดูลมี **skeleton ที่ compile ผ่าน มี test ผ่าน และรันได้จริง** แต่ยังเป็นระบบจำลอง (mock) แทบทั้งหมด — ยังไม่มีการเชื่อมต่อ Bitcoin จริง, testnet จริง, หรือการเชื่อมต่อระหว่าง service แบบครบวงจร นี่คือจุดเริ่มต้นสำหรับทีม capstone ให้เข้ามาต่อยอดตาม timeline ใน [`00-capstone-brief.md`](00-capstone-brief.md) §4

---

## 1. vault-core (คนที่ 1-2) — จุดวิกฤตของโปรเจกต์

**สถานะปัจจุบัน**
- มี skeleton ครบ 4 ส่วน (`descriptor`, `derivation`, `psbt`, `policy`) เขียนด้วย Rust ล้วน — **ภายในยังเป็น String/struct จำลอง** แทน pubkey, descriptor, PSBT (ยังไม่ได้เปลี่ยนมาใช้ type จริงจาก `bitcoin`/`miniscript`)
- `policy::PolicyEngine` เป็นส่วนที่ "จริง" ที่สุดในตอนนี้: บังคับ default-deny, ตรวจ address/amount/loan ตรงกันเป๊ะ มี unit test แบบ adversarial ผ่านครบ (ปฏิเสธ output ผิด, จำนวนผิด, loan ผิด, และเคสที่มี output ถูกต้องปนกับ output แอบขโมยมูลค่า)
- **2026-08-25 — ก้าวแรกของ wallet-first pivot (M-of-N ใครก็ได้ แทน 2-of-3 role คงที่)**: เปิดใช้ `bitcoin = "0.32"` / `miniscript = "12"` ใน `Cargo.toml` แล้ว และเพิ่ม 2 module ใหม่คู่กับของเดิมโดย**ไม่แตะ logic เดิมเลย** — `keys` (data model กลาง: `VaultKey`, `KeySourceType`, `HwVendor` ใช้ `bitcoin::bip32::{Fingerprint, Xpub}` ของจริง) และ `hw` (placeholder เปล่า รอ Jade/Trezor client)
- **2026-09-30 — Jade ผ่าน BLE (No.4) เสร็จ** (เจ้าของ: **@phoovich** — งานของ wallet-first pivot ที่ไม่อยู่ในแผนแบ่งงาน 4 คนเดิม): `hw::jade::JadeSession` คือ protocol CBOR-RPC ของ Jade ล้วนๆ ไม่มี I/O — ทำ request/reply, ประกอบ reply ที่มาเป็นท่อนๆ, ขอ PSBT ที่เซ็นแล้วทีละท่อนด้วย `get_extended_data`, ส่งต่อ pinserver และตรวจว่า PSBT ที่ได้กลับมาเพิ่มแค่ลายเซ็นที่ถูกต้องของ Jade — ส่วน BLE อยู่ใน crate ใหม่ `jade-ble` (ใช้ `btleplug`) ทดสอบด้วย fake Jade ที่ทำตามกฎ firmware และกับเครื่องจริง (ดูบันทึกด้านล่าง)
- รวม 48 test ของ `vault-core` ผ่านหมด ณ 2026-10-01 (23 ใน `hw::jade`) + 6 ใน `jade-ble` — compile, clippy (`-D warnings`), rustdoc และ `cargo fmt` ของสอง crate นี้สะอาด
- **2026-10-01 — Trezor Safe 7 ผ่าน BLE (No.5): โค้ดและ test เสร็จแล้ว และผ่าน hardware checklist กับเครื่องจริงเกือบครบ** (เจ้าของ @phoovich — บันทึกการทดสอบอยู่ด้านล่าง)
  - `hw::trezor::TrezorSession` (feature `trezor`, ปิดไว้เป็นค่าเริ่มต้น) คือ Trezor-Host Protocol แบบไม่มี I/O
    - ใช้ crate `trezor-thp` และ protobuf bindings ของ Trezor เอง
    - ทำ code-entry pairing (CPace) และ pairing credential
    - ตรวจว่าเป็น Safe 7 (`T3W1`) สองชั้นทุกครั้งที่เชื่อมต่อ
    - เซ็นได้เฉพาะ PSBT ของ vault แบบ P2WSH `sortedmulti` และตรวจลายเซ็นที่ได้กลับมาด้วย `hw::psbt_check` (แยกออกมาจาก `hw::jade` ให้ใช้ร่วมกัน)
  - crate ใหม่ `trezor-ble` (btleplug 0.13) ทำหน้าที่ส่ง byte อย่างเดียว ส่วน `examples/trezor-hw-test` คือ harness สำหรับทดสอบกับเครื่องจริง
  - test ทั้ง workspace 114 ตัวผ่าน
    - `vault-core` ตอนเปิด feature `trezor`: 88 ตัว — 40 ตัวใหม่ รวม end-to-end กับ Safe 7 จำลองที่ใช้ device role ของ `trezor-thp` ของจริง
    - `trezor-ble`: 7 ตัว และ harness 2 ตัว
  - **mutation pass**: จงใจทำ check ด้านความปลอดภัยพังทีละจุด 33 จุด และสถานะ "รอผู้ใช้" อีก 3 จุด ทุกจุดมี test จับได้
  - `cargo check` ผ่านทั้ง `aarch64-apple-ios` และ `aarch64-linux-android` (Android ต้องใช้ clang ของ NDK สำหรับ `secp256k1-sys` ซึ่งเป็นเงื่อนไขเดิมของ `bitcoin`)
  - ใน dependency ของ `trezor-ble` ไม่มี crate USB/HID เลย
  - รายละเอียดอยู่ใน README ของ `trezor-ble` ส่วนข้อที่ยังเปิดอยู่คือ `04-open-items.md` ข้อ 16–22
- **2026-10-01 — audit อิสระของ No.5 แล้วแก้ตามผล**
  - ทำซ้ำได้จริง: test 114 ตัว, Elligator2 และ CPace (vector ทุกชุด + differential 20,000 input เทียบกับ implementation ที่เขียนใหม่จาก RFC), protobuf ที่ vendor มาตรงกับ upstream ที่ `c33f815` ทุกไบต์, xpub ใน log ของ hardware ตรงกับที่ derive เองจาก phrase ทดสอบ
  - mutation pass เดิม: ใน `mutate.py` มี 34 จุด test จับได้ 33 อีก 1 จุด compile ไม่ผ่าน (ส่วน 3 จุด "รอผู้ใช้" ไม่พบใน artifact) — audit ทำเพิ่มอีก 30 จุด รอด 19
  - แก้แล้ว:
    - host เชื่อคำว่า "paired" ของอุปกรณ์เฉพาะเมื่อส่ง credential ไปจริงเท่านั้น (THP spec host state HH3 — `04` ข้อ 23)
    - cancel ที่ไม่มีจอให้ตอบจะหมดไปพร้อม request นั้น ไม่ค้างไปยกเลิก request ถัดไป
    - key ของเครื่องต้องอยู่บน path BIP-48 P2WSH ของ network ของ session (`m/48'/coin'/account'/2'/{0,1}/i`) ไม่อย่างนั้นไม่ส่งไปเซ็น ส่วน change ที่อยู่นอก path นี้จะแสดงเป็น payment ให้ผู้ใช้เห็น
    - ปฏิเสธ PSBT ที่ใช้ output เดียวกันซ้ำ
    - test ใหม่: คำตอบ xpub ที่ผิดห้าแบบ, `psbt_check` มี test ของตัวเอง, test ว่า `Debug` ไม่เผย key (ตัวเดิมไม่มีทางล้ม)
  - test ทั้ง workspace 122 ตัวผ่าน (`vault-core` ตอนเปิด `trezor`: 96 ตัว), clippy (`-D warnings`) และ `cargo fmt` สะอาด
  - รันกับเครื่องจริงซ้ำหลังแก้แล้ว: HW-2, HW-4, HW-7 ผ่านทั้งหมด (`04` ข้อ 23) ส่วนข้อที่ยังไม่แก้อยู่ใน `04` ข้อ 24
- **2026-10-01 — `jade-ble` ย้ายไป btleplug 0.13.3** ตัวเดียวกับ `trezor-ble` เพื่อให้แอป Android มี btleplug ชุดเดียว (`04` ข้อ 18) ไม่ต้องแก้โค้ด และรัน hardware checklist ของ Jade ซ้ำบน 0.13.3 ผ่านทุกข้อที่เคยผ่านบน 0.11.8 (บันทึกด้านล่าง)

**สิ่งที่ต้องทำทั้งหมด**
- [ ] ทีมอ่าน Bitcoin fundamentals (BIP-32/48, PSBT, multisig script) ให้จบก่อน — สัปดาห์ 1-2 (`.claude/skills/bitcoin-fundamentals/SKILL.md`)
- [x] เปิดใช้ `bitcoin`/`miniscript` crate จริงใน `Cargo.toml` (2026-08-25 — ยังใช้จริงแค่ใน `keys`)
- [ ] `keys`: เพิ่มฟังก์ชัน derive จริง (ตอนนี้มีแค่ type) + ตัดสินใจว่า `derivation_path` ควรเป็น `String` หรือ `bitcoin::bip32::DerivationPath` — **รอ @munich** (เอกสาร `08-multisig-wallet-spec.md` ยังไม่อยู่ใน repo)
- [x] `hw`: Jade client ผ่าน **BLE** (ไม่ใช่ QR — Jade Core ไม่มีกล้อง) — No.4, เจ้าของ @phoovich (2026-09-30)
- [ ] `hw`: Trezor Safe 7 (BLE) client — No.5: โค้ดและ test เสร็จ และผ่าน hardware checklist แล้ว (2026-10-01) เหลือแค่จอยืนยันการเชื่อมต่อหลัง restart (HW-13 ยังสรุปไม่ได้) — ดู README ของ `trezor-ble`
- [ ] `descriptor`: สร้าง P2WSH 2-of-3 multisig descriptor จริงจาก pubkey จริงของ 3 ฝ่าย
- [ ] `derivation`: derive child pubkey จริงตาม BIP-48 (`m/48'/0'/0'/2'`) จาก account xpub
- [ ] `psbt`: สร้าง/parse PSBT จริงด้วย `bitcoin::Psbt`
- [ ] `policy`: ย้าย logic เดิม (ที่ทดสอบผ่านแล้ว) มาทำงานกับ PSBT จริง — คงหลักการ default-deny ไว้เหมือนเดิม
- [ ] เพิ่ม adversarial test เพิ่มสำหรับ PSBT จริง: fee manipulation, replay, partial-spend สำหรับ liquidation
- [ ] เทอม 2: รองรับ liquidation flow แบบเต็ม (ขายบางส่วน + คำนวณ change)
- [ ] เทอม 2 สัปดาห์ 13-14: security review แบบ adversarial ร่วมกันทั้งทีม

### บันทึกการทดสอบกับ Jade Core จริง (2026-09-30)

เครื่อง: Jade Core `Jade BE0184` (board `JADE_V2C`, firmware 1.0.41) · host: macOS + `btleplug` 0.11.8 · รันด้วย `cargo run -p jade-ble --example jade-hw-test -- …` (checklist เต็มอยู่ใน `vault-workspace/jade-ble/README.md`)

| # | ทดสอบ | ผล |
|---|---|---|
| HW-1 | scan → connect → GATT → `get_version_info` | ✅ ก่อน reset: state `LOCKED`, networks `MAIN` — ยืนยันว่า wallet เดิมผูกกับ mainnet |
| HW-2 | factory reset → restore test phrase → ตั้ง PIN ผ่าน BLE + pinserver → `get_xpub` | ✅ fingerprint `73c5da0a` และ xpub ที่ `m/48'/1'/0'/2'` ตรงกับที่ `keys::account_multisig_xpub_from_mnemonic` derive เองจาก phrase (ปักไว้เป็น test `hw::jade::device_vectors`) |
| HW-3 | `roundtrip` vault 2-of-3 P2WSH: 2 / 16 / 100 inputs | ✅ ทุกครั้ง: Jade เซ็นครบทุก input, PSBT ที่ได้กลับเปลี่ยนแค่ลายเซ็นของ Jade, ลายเซ็น verify ผ่าน และ finalize 2-of-3 ได้ (100 inputs: request ~49 KB = 97 writes, PSBT ที่เซ็นแล้วกลับมาราว 20 ท่อน, 28.8 วินาทีรวมเวลากดยืนยัน) |
| HW-4 | ไม่เจอ Jade (Jade ไม่ได้ advertise) | ✅ error บอกให้เช็ค Bluetooth/ไฟของ Jade |
| HW-4 | ปฏิเสธบน Jade | ✅ `Jade: declined on the device` |
| HW-4 | ถอดสาย USB ขณะรอ Jade | ✅ `the Jade disconnected` ทันที (ทดสอบตอนรอใส่ PIN — code path เดียวกับตอนรอยืนยันการเซ็น) |
| HW-4 | เชื่อมใหม่หลังหลุด | ✅ run ถัดไปเชื่อมได้เลย |
| HW-4 | ขอ mainnet กับ wallet ที่ผูก testnet | ✅ ปฏิเสธก่อนที่ Jade จะถาม PIN |
| ยังไม่ได้ทดสอบบนเครื่อง | Jade ไม่มี input ให้เซ็น, ปฏิเสธการจับคู่ BLE, มี Jade หลายเครื่อง | logic มี unit test ครอบแล้ว ขั้นตอนทดสอบด้วยมืออยู่ใน README ของ `jade-ble` |

ข้อสังเกต: `roundtrip` ใช้ input สังเคราะห์ (ไม่ได้ broadcast) — การเซ็นด้วย UTXO testnet จริงแล้ว broadcast ยังเป็นงานของเกณฑ์ผ่าน Phase 0 (`09-wallet-mvp-buildplan.md` §5)

### ตรวจซ้ำก่อนเปิด PR (2026-10-01)

รีวิวแบบ adversarial ก่อนเปิด PR: เทียบ protocol กับ source ของ firmware 1.0.41 แล้วทดสอบกับเครื่องเดิมทั้งก่อนและหลังแก้ (ข้อที่แก้อยู่ใน `vault-core::hw::jade` และ `jade-ble`)

| # | ทดสอบ | ก่อนแก้ | หลังแก้ |
|---|---|---|---|
| HW-5 | pinserver ติดต่อไม่ได้ (บังคับด้วย `ALL_PROXY=http://127.0.0.1:9`) | Jade ค้างที่ "Checking…" จน host ตัดการเชื่อมต่อ แล้วขึ้น "Network or server error" ให้กด | host ส่ง `cancel` แทนคำตอบของ pinserver — `unlock()` ครั้งถัดไปบน connection เดิมผ่าน |
| HW-6 | BIP39 passphrase (Always Ask) รอราว 30 วินาทีก่อนยืนยัน | timeout 15 วินาทีหลัง relay pinserver (2 ครั้ง) | unlock ผ่าน และ xpub ตรงกับ test vector |
| HW-7 | ขอ version info ตอนที่จอ Jade รอผู้ใช้ (จอ passphrase) | timeout 15 วินาที | ขอแบบ `nonblocking` ซึ่ง firmware ตอบจาก task ของ BLE เอง — `info` ตอบปกติ |
| HW-8 | reply ค้างจาก connection ก่อนหน้า | reply id `"3"` ของ session เก่ามาถึง connection ใหม่ (id เริ่มที่ 1 ทุก session จึงชนกันได้) | id เริ่มแบบสุ่มต่อ session แบบเดียวกับ jadepy และ reply ที่ id ไม่ตรงถูกปฏิเสธ |
| ซ้ำ | `info`, `xpub --verify-against`, `roundtrip` 2/16/100, ปฏิเสธบนเครื่อง, ใส่ PIN ผิดแล้วลองใหม่ทันที | — | ✅ ทั้งหมด — PSBT 100 inputs (ตอบกลับ 20 ท่อน) ตรวจซ้ำแบบอิสระด้วย rust-bitcoin + miniscript โดยไม่ผ่าน `verify_signed_psbt` |

### รันซ้ำบน btleplug 0.13.3 (2026-10-01)

`jade-ble` ย้ายจาก btleplug 0.11.8 ไป 0.13.3 เพื่อให้แอป Android ใช้ btleplug ชุดเดียวกับ `trezor-ble` (`04` ข้อ 18) ไม่ได้แก้โค้ด แต่ backend CoreBluetooth เปลี่ยนพฤติกรรมในจุดที่ Jade ใช้ (รายละเอียดใน `04` ข้อ 18) ผลในสองตารางด้านบนได้จาก 0.11.8 ทั้งหมด จึงรันซ้ำบน 0.13.3

เครื่องเดิม (Jade Core `Jade BE0184`, firmware 1.0.41) · host: macOS 26.6.2, Rust 1.98.1, `btleplug` 0.13.3 · 2026-10-01 21:31–23:13 · log ของแต่ละข้ออยู่ที่ `vault-workspace/target/jade-b013-*.log` และ `jade-b0118-*.log` (harness บน 0.11.8 ที่ใช้เทียบ A/B) ไม่อยู่ใน git

| # | ทดสอบ | บน 0.11.8 | บน 0.13.3 | สรุป |
|---|---|---|---|---|
| HW-1 | `info` | ✅ | ✅ `Jade BE0184`, `JADE_V2C`, `BLE`, 1.0.41, `TEST` | ผ่าน — เหมือนเดิม |
| HW-2 | `xpub --verify-against` (unlock ผ่าน pinserver) | ✅ | ✅ `73c5da0a` และ ✓ ทั้งสองบรรทัด | ผ่าน — เหมือนเดิม |
| HW-3 | `roundtrip` 2 / 16 / 100 inputs | ✅ (100 inputs: 28.8 วินาที) | ✅ ทั้งสามแบบ ลายเซ็น verify ผ่านและ finalize ได้ (100 inputs: 28.6 วินาที รวมเวลากดยืนยัน) | ผ่าน — เหมือนเดิม |
| HW-4 | ปฏิเสธบน Jade, ถอดสาย USB, เชื่อมใหม่, network pin, ไม่เจอ Jade | ✅ | ✅ ทุกข้อ — ถอดสายแล้ว `the Jade disconnected` ขึ้นภายใน 4 วินาทีหลังเริ่ม unlock, network pin ปฏิเสธก่อนถาม PIN, "ไม่เจอ" ทดสอบด้วยการปิด Bluetooth ของ Jade | ผ่าน — เหมือนเดิม |
| HW-5 | pinserver ติดต่อไม่ได้ | ✅ (ผ่านเครื่องมือชั่วคราว) · รันซ้ำผ่าน harness คู่กับ 0.13.3 (22:50): ✅ | ✅ `pinserver relay failed: io: Connection refused` และ Jade กลับหน้า home ทันที — รันคู่กับ harness ตัวเดียวกันที่ build จาก HEAD `81ae7a9` (btleplug 0.11.8) ได้จอเหมือนกัน | ผ่าน — เหมือนเดิม (เทียบ A/B) |
| HW-6 | BIP39 passphrase รอ 30 วินาที | ✅ | ✅ unlock ใช้ 62 วินาทีรวมเวลารอที่จอ passphrase และ ✓ ทั้งสองบรรทัด | ผ่าน — เหมือนเดิม |
| HW-7 | version info ตอนจอรอผู้ใช้ | ✅ | ✅ (รอบสอง 22:20 ดูจอแล้ว) Ctrl-C ตอนจอ passphrase ซึ่งยังค้างอยู่บน Jade แล้ว `info` ตอบทันทีในวินาทีเดียวกับที่เชื่อมต่อได้ — รอบแรกไม่ได้ดูจอ | ผ่าน — เหมือนเดิม |
| HW-8 | reply ค้างจาก connection ก่อนหน้า | ✅ | ✅ (รอบสี่ 23:03) Ctrl-C ตอนจอ passphrase แล้วยืนยัน passphrase ระหว่างที่ run ใหม่กำลัง unlock: reply ของ session เก่า (id `2315457464`) มาถึง connection ใหม่และถูกปฏิเสธ `protocol error: reply id … does not answer request "3532897796"` จากนั้น run ถัดไป unlock ได้ปกติ — สามรอบแรกไม่มี reply ค้างมาถึง (ดูด้านล่าง) | ผ่าน — เหมือนเดิม |
| ซ้ำ | ใส่ PIN ผิดแล้วลองใหม่ทันที | ✅ | ✅ (รอบสอง 23:12) PIN ผิดถูกปฏิเสธ (`unlock failed`) แล้วเริ่ม run ที่สอง 13 วินาทีต่อมาขณะจอ "Incorrect PIN!" ยังค้าง: รอกด Continue และใส่ PIN แล้ว unlock ได้ (38 วินาที เทียบกับ 15–17 วินาทีของการ unlock ปกติ) — รอบแรกไม่ได้จดว่าจอยังค้างหรือไม่ | ผ่าน — เหมือนเดิม |
| ใหม่ | จับคู่ใหม่ (ลืม Jade ใน Bluetooth ของ Mac) รอ 20 วินาทีก่อนกดยืนยัน | ✅ โดยอ้อม (มีการจับคู่ครั้งแรกระหว่างรอบทดสอบเดิม) | ✅ เชื่อมต่อใช้ 21 วินาทีรวมเวลารอ ไม่ timeout ที่ขั้น discovery (15 วินาที) | ผ่าน |
| ใหม่ | ปฏิเสธการจับคู่ | ยังไม่ได้รัน | ✅ `Bluetooth: Runtime Error: Device disconnected` 3 วินาทีหลังสแกน ไม่ค้าง | ผ่าน (ไม่มีผลของ 0.11.8 ให้เทียบ) |

สิ่งที่พบ:
- ไม่พบ regression: ทุกข้อที่เคยผ่านบน 0.11.8 (HW-1 ถึง HW-8 และใส่ PIN ผิดแล้วลองใหม่ทันที) ผ่านบน 0.13.3 ด้วยผลเหมือนเดิม (HW-5 เทียบแบบ A/B) — ข้อที่ไม่เคยรันบนเครื่องจริงทั้งสองเวอร์ชันคือ Jade ไม่มี input ให้เซ็น และมี Jade หลายเครื่อง
- HW-8 ต้องลองสี่รอบ สามรอบแรก Jade ทำ reply เสร็จตอนที่ไม่มี host เชื่อมต่ออยู่ และไม่เห็น reply นั้นที่ connection ถัดไปเลย:
  - รอบหนึ่ง: Ctrl-C ตอนจอขอยืนยันการเซ็น แล้วยืนยันก่อนรัน `info`
  - รอบสอง (22:22): host หมดเวลาที่จอ PIN เพราะขั้นตอนที่ให้ไปไม่ได้บอกให้ใส่ PIN ก่อน จึงไม่ถึงขั้นเซ็น
  - รอบสาม (22:51): host หมดเวลาที่จอยืนยันธุรกรรม (300 วินาที) แล้วจึงกดยืนยัน
  - รอบสี่ Jade ทำ reply เสร็จระหว่างที่ connection ใหม่เชื่อมอยู่ reply จึงมาถึง แปลว่า Jade ส่ง reply ทาง connection ที่เชื่อมอยู่ตอนทำเสร็จ — README ของ `jade-ble` เดิมยกตัวอย่าง "ยืนยันหลัง host หมดเวลา" ซึ่งรอบสามแสดงว่าไม่จริง แก้แล้ว และรอบสี่ยืนยันด้วยว่า host บน 0.13.3 รับ reply ค้างได้ปกติ ที่สามรอบแรกไม่เจอจึงมาจากฝั่ง Jade ไม่ใช่ btleplug
- รอบสองของ HW-8: หลัง host หมดเวลาไปแล้วจึงมีการใส่ PIN (อนุมานจากจอที่ตามมา) Jade จึงขึ้น "Network or server error" เพราะไม่มี host ส่งคำขอต่อไปที่ pinserver หรือส่ง `cancel` ให้ เป็นพฤติกรรมของ firmware ที่คาดได้ ไม่เกี่ยวกับ btleplug — แอป Phase 1 ควรบอกผู้ใช้เมื่อหมดเวลารอ PIN ไปแล้ว
- ชื่อเครื่องยังเป็น `Jade BE0184` แม้ 0.13 จะใช้ชื่อจาก advertisement ก่อนชื่อ GAP
- ที่ห่วงว่า discovery ซึ่งย้ายออกจาก `connect()` และมีเวลาแค่ 15 วินาทีจะ timeout ระหว่างรอผู้ใช้ยืนยันการจับคู่ ไม่เกิด: จากเวลาที่วัดได้ การจับคู่เกิดตอน subscribe ซึ่งมีเวลา 60 วินาที จึงไม่ต้องย้าย discovery เข้าไปใน retry loop
- ที่คาดไว้ว่าการปฏิเสธการจับคู่บน 0.11.8 จะค้าง 60 วินาที **ผิด**: เครื่องจริงตัดการเชื่อมต่อเมื่อปฏิเสธ และ 0.11.8 ก็ปลดคำขอ subscribe ที่ค้างอยู่ทันทีเมื่อหลุดเหมือนกัน
- HW-8 รอบหนึ่ง: หลัง Ctrl-C แล้วยืนยันบน Jade, `info` 21 และ 37 วินาทีหลัง Ctrl-C ยังเห็น state `Ready` (ยัง unlock อยู่) แล้วเป็น `Locked` ที่ 65 วินาที (log ของสองครั้งแรกถูก run ที่สามและสี่เขียนทับ ข้อมูลมาจาก output ที่คัดลอกไว้) ตรวจ source แล้วว่า btleplug ทั้งสองเวอร์ชันสร้าง `CBCentralManager` และเชื่อมต่อแบบเดียวกันทุกประการ (ไม่ใส่ option ใดเลย) และ Ctrl-C ตัด process ก่อนโค้ดของ btleplug จะได้ทำงาน ผลนี้จึงมาจาก firmware หรือ OS ไม่ใช่ btleplug แต่ขัดกับที่ว่า Jade lock ทันทีที่หลุด — แยกเป็น `04` ข้อ 25

### บันทึกการทดสอบกับ Trezor Safe 7 จริง (2026-10-01)

เครื่อง: Trezor Safe 7 firmware 2.12.5 (restore test phrase แล้ว) · host: macOS 26.6.2, Rust 1.98.1, `btleplug` 0.13.3, `trezor-thp` 0.1.1 · รันด้วย `cargo run -p trezor-ble --example trezor-hw-test -- …` (checklist เต็มอยู่ใน `vault-workspace/trezor-ble/README.md`)

| # | ทดสอบ | ผล |
|---|---|---|
| HW-1–3 | scan → GATT → จับคู่ครั้งแรก (Bluetooth ของ OS + code ของ THP) → `GetFeatures` | ✅ MTU 247, `T3W1` ผ่านทั้งสองชั้น, model `Safe 7`, firmware 2.12.5 และได้ credential — สำเร็จในครั้งที่ 3: ครั้งแรกเผลอแตะจอที่แสดง code (ปุ่มเดียวบนจอนั้นคือยกเลิกการจับคู่) ครั้งที่สอง Safe 7 อยู่หน้า lock แล้วไม่ตอบ channel allocation (ตอนนี้ขอซ้ำได้แล้ว) |
| HW-4 | เชื่อมใหม่ด้วย credential | ✅ ไม่ถาม code และใช้ credential เดิมได้ทุก run หลังจากนั้น |
| HW-5 | `xpub --verify-against` ที่ `m/48'/1'/0'/2'` | ✅ fingerprint `73c5da0a` และ xpub ตรงกับที่ vault-core derive เองจาก phrase และตรงกับของ Jade (`hw::jade::device_vectors`) |
| HW-6 | `xpub` BIP-84 แบบ SLIP-132 | ✅ key ตรงกับ vault-core และ decode แล้ว `vpub` ที่ได้คือ key เดียวกัน — ยังไม่ได้เทียบกับจอของ Trezor Suite |
| HW-7 | `roundtrip` 2 และ 16 inputs | ✅ ทั้งสองแบบ ลายเซ็น verify ผ่านและ finalize 2-of-3 ได้ (2 inputs: 18.0 วินาที, 16 inputs: 50.4 วินาที รวมเวลากดยืนยัน) |
| HW-8 | ปฏิเสธบน Safe 7 | ✅ `cancelled on the Trezor` |
| HW-9 | กด Ctrl-C (ส่ง Cancel) ระหว่าง Safe 7 รอยืนยัน | ✅ Safe 7 ตอบ `ActionCancelled` และ harness จบด้วย `cancelled on the Trezor` |
| HW-10 | ปิด Bluetooth ของ Mac ระหว่างรอยืนยัน | ✅ `the Trezor disconnected` ภายในไม่กี่วินาที และ run ถัดไปเชื่อมได้ |
| HW-11 | Safe 7 lock อยู่ (ปลุกให้ขึ้นหน้า lock) แล้วสั่ง `info` | ✅ Safe 7 ขอ PIN บนเครื่อง แล้วเชื่อมต่อได้ |
| HW-12 | ลืม Safe 7 ใน Bluetooth settings ของ Mac | ✅ OS ขอจับคู่ใหม่ (numeric comparison) และ credential ของ THP ยังใช้ได้ ไม่ถาม code — run แรกจบด้วย `cancelled on the Trezor` โดยไม่ทราบสาเหตุ แต่ทำซ้ำแล้วเซ็นและ finalize ได้ |
| HW-13 | ปิดแล้วเปิด Safe 7 ใหม่ ปลดล็อก แล้วสั่ง `info` | ❔ เชื่อมต่อได้โดยไม่มีจอ "Allow … to connect" — ยังสรุปไม่ได้ เพราะไม่แน่ใจว่าปิดเครื่องสนิท และ spec ไม่ได้บอกว่า channel cache อยู่รอดหลังปิดเครื่องหรือไม่ |
| แก้ bug แล้ว | `roundtrip` โดยปล่อยจอให้ยืนยันไว้ 30 วินาทีก่อนกด | ✅ เซ็นและ finalize ได้ (54.7 วินาทีรวมเวลารอ) |

สิ่งที่เจอจากเครื่องจริง:
- **bug (แก้แล้ว):** driver ให้เวลาผู้ใช้กดยืนยันแค่ 15 วินาทีแทน 5 นาที เพราะ Safe 7 ส่ง ACK ของ ButtonAck มา*หลัง*ขึ้นจอให้ยืนยัน แล้ว driver นับเวลาใหม่จาก packet ล่าสุด (เจอใน run หลัง HW-10: `timed out waiting for the Trezor`) ตอนนี้ใช้ `TrezorSession::awaiting_user()` ตัดสินแทน และมี test ที่ทำให้เกิดอาการเดียวกันบน device role ของ `trezor-thp`
- Safe 7 ที่หลับโดยไม่มี host เชื่อมต่ออยู่จะปิดวิทยุ Bluetooth (`ble_suspend` ใน firmware) จึงหาไม่เจอจนกว่าจะปลุกเครื่อง เจอแบบนี้ 2 ครั้ง แอปต้องบอกผู้ใช้ให้ปลุก Safe 7 ก่อน
- ถ้า host หายไประหว่างรอยืนยัน (kill harness หรือปิด Bluetooth) Safe 7 ยังค้างจอนั้นไว้จนเครื่อง lock เอง (ราว 1 นาที) ถ้าต้องการให้จอนั้นหายไป ต้องส่ง Cancel ขณะที่ยังเชื่อมต่ออยู่
- ชื่อที่ advertise เปลี่ยนเกือบทุกครั้งที่เชื่อมต่อ (12 จาก 13 ครั้ง: `(0R4)`, `(4B6)`, `(2G8)`…) จึงใช้ระบุเครื่องไม่ได้ เช่นเดียวกับ address

- เชื่อมต่อใหม่ด้วย credential แล้ว Safe 7 ไม่ขึ้นจอให้ยืนยันการเชื่อมต่อเลย เพราะ firmware ทำ "channel replacement": ถ้ายังมี channel เปิดค้างของ host key เดิมอยู่ ก็ถือเป็น autoconnect และไม่ถาม แม้ credential ของเราจะไม่ใช่ autoconnect ก็ตาม (`core/embed/rust/src/thp/mod.rs`) จอ "Allow … to connect" ควรขึ้นเมื่อไม่มี channel นั้นแล้ว เช่นหลัง restart เครื่อง

ยังไม่ได้ทดสอบบนเครื่อง: จอยืนยันการเชื่อมต่อ ("Allow … to connect") ซึ่งมีแต่ test กับ Safe 7 จำลอง และ `BondRemoved` (Safe 7 ลืม host แต่ host ยังจำ Safe 7)

---

## 2. custody-service (คนที่ 3 — ร่วมกับ lender-signer-cli + monitor-service)

**สถานะปัจจุบัน**
- มี REST server จริงด้วย axum รันได้จริงที่ `127.0.0.1:8080` (ทดสอบด้วย curl แล้วใช้งานได้)
- มี state machine ของ signing request ครบ (`created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed`) และเป็น **idempotent จริง** (advance ซ้ำที่ confirmed ไม่พังไม่ error)
- ใช้ `vault-core::policy::SigningReason` ร่วมกันแล้ว (พิสูจน์ว่า dependency เชื่อมกันจริง)
- เก็บข้อมูลใน memory (`HashMap` ธรรมดา) — **ยังไม่มี Postgres**, ยังไม่มี auth, ยังไม่เชื่อม HSM/KMS

**สิ่งที่ต้องทำทั้งหมด**
- [ ] ออกแบบ API spec แบบเต็มสำหรับให้ NestJS Loan Service เรียกใช้จริง
- [ ] เปลี่ยนจาก in-memory store เป็น Postgres ผ่าน `sqlx` (dep คอมเมนต์ไว้แล้ว) + migration + unique constraint กันการประมวลผลซ้ำ (`loan_index`)
- [ ] เก็บ mapping loan ↔ vault descriptor จริง (รอ `vault-core::build_descriptor` เวอร์ชันจริง)
- [ ] เชื่อม mock HSM/KMS สำหรับคีย์ฝั่งแพลตฟอร์ม
- [ ] เพิ่ม endpoint รับ trigger จาก `monitor-service` (ตอนนี้ยังไม่มีเลย)
- [ ] เพิ่ม auth ระหว่าง service (ตอนนี้เปิดกว้างไม่มีการยืนยันตัวตนใดๆ)
- [ ] integration test กับ vault-core บน Bitcoin testnet จริง
- [ ] เทอม 2: รองรับ liquidation state

---

## 3. mobile-signer-ffi (คนที่ 4)

**สถานะปัจจุบัน**
- **ขอบเขตขยายใหญ่ขึ้นจากเดิม**: ตามดีไซน์ล่าสุด (ดู `01-architecture.md`'s "mobile-signer-ffi is two wallets in one app" และ `04-open-items.md` ข้อ 2/9) โมดูลนี้ต้องเป็นทั้ง (ก) hot wallet single-sig จริงสำหรับ BTC ที่ยัง free — แนะนำใช้ `bdk` Rust core และ (ข) vault signer multisig ที่ห่อ vault-core (ของเดิม) **ตอนนี้มีแค่ (ข) เท่านั้น (ก) ยังไม่เริ่มเลยแม้แต่บรรทัดเดียว**
- `rust/` มี skeleton จริงแล้วสำหรับฝั่ง vault signer เท่านั้น: 3 ฟังก์ชัน (`derive_borrower_pubkey`, `compute_vault_address`, `sign_psbt`) ต่อกับ mock type ของ `vault-core` โดยตรง มี 5 test ผ่าน — **ยังไม่ได้ใช้ `uniffi` crate/macro จริง** (ตั้งใจรอจนกว่า `vault-core` จะนิ่งก่อน) **และยังไม่มี `bdk` dependency หรือ hot-wallet code ใดๆ**
- `app/` เป็น Expo React Native app ที่ **รันได้จริง** (เว็บ/iOS/Android ผ่าน simulator) ครบ 12 หน้าจอตาม design ล่าสุด รองรับโมเดลหลายสัญญาเงินกู้พร้อมกัน (multi-loan) แล้ว
- ข้อมูลทั้งหมดใน `app/` ยังเป็น **mock ล้วน** (`mockVault.ts`, TypeScript) — ยังไม่ได้เชื่อมกับ `rust/` เลย (คนละภาษา ยังไม่มี native module bridge) — รวมถึงยังไม่มี concept ของ UTXO/fee/node connectivity จริงใน mock เลย เพราะตอนสร้าง mock ยังไม่รู้ว่าต้องมี hot-wallet layer

**สิ่งที่ต้องทำทั้งหมด**
- [ ] **ตัดสินใจ**: ใช้ `bdk` Rust core สำหรับเลเยอร์ hot wallet ตามที่เอกสารแนะนำ หรือ implement เอง (ดู `04-open-items.md` ข้อ 2) — ยังไม่ได้ตัดสินใจเป็นมติ
- [ ] เริ่มเลเยอร์ hot wallet (single-sig): address generation, UTXO tracking, fee estimation, ส่ง/รับ BTC, เชื่อมต่อ Electrum/Esplora, สร้าง PSBT แบบ watch-only — **ยังไม่มีโค้ดแม้แต่บรรทัดเดียว**
- [ ] เมื่อ `vault-core` นิ่งแล้ว: เพิ่ม `uniffi` dependency จริง, ใส่ `#[uniffi::export]` ให้ฟังก์ชันฝั่ง vault signer ที่มีอยู่ (หรือฟังก์ชันใหม่ตามอินเทอร์เฟซจริง), generate binding ไป Kotlin/Swift
- [ ] เชื่อม UI เข้ากับ native binding จริงแทน `mockVault.ts` ทีละฟังก์ชัน (ทั้งฝั่ง hot wallet และ vault signer)
- [ ] ทำ verification/challenge-response flow ตอนเปิด loan (ตรวจจับ pubkey derive ผิด) — **ยังไม่มีเลยตอนนี้**
- [ ] เชื่อม UI margin-call/liquidation กับข้อมูลจริงจาก `monitor-service`/`custody-service` แทน mock
- [ ] เชื่อม Receive/Borrow/Send flow กับ testnet จริงผ่าน node connectivity ของแอปเอง (ไม่ผ่าน backend) — ตรงกับ Definition of Done ข้อ 6 ใน `00-capstone-brief.md`
- [ ] (ถ้าจำเป็น) เปลี่ยนจาก state-switch ง่ายๆ ใน `App.tsx` เป็น react-navigation เมื่อโครงสร้างซับซ้อนขึ้น
- [ ] ถ้าเวลาไม่พอ: ใช้แนวทาง MVP-screens-ก่อน ตาม `00-capstone-brief.md` §3.3 (onboarding→success ก่อน, activity/portfolio/settings เป็น stretch goal)

---

## 4. lender-signer-cli (คนที่ 3 — ร่วมกับ custody-service + monitor-service)

**สถานะปัจจุบัน**
- มี CLI จริงด้วย `clap`: `fetch` (ดึงข้อมูลจาก custody-service ผ่าน HTTP จริง — ทดสอบแล้วใช้ได้), `inspect` (อ่านไฟล์ PSBT JSON), `sign` (เขียนไฟล์ signed PSBT)
- ใช้ `vault-core::psbt::UnsignedPsbt` เป็นรูปแบบไฟล์ร่วมกัน
- `sign` ยังเป็น **mock signature** (string ปลอม ไม่ได้เซ็นจริง)

**สิ่งที่ต้องทำทั้งหมด**
- [ ] เชื่อมกับ `vault-core` จริงเพื่อเซ็น PSBT ด้วยคีย์จริงแบบ air-gapped
- [ ] ออกแบบ transport mechanism ที่เป็น air-gapped จริง (ตอนนี้ `fetch` ต่อ HTTP ตรงๆ ซึ่งขัดกับหลักการ "offline/air-gapped" — ต้องคิดว่าจะย้ายข้อมูลข้ามเครื่องอย่างไร เช่น ไฟล์/QR code/USB)
- [ ] ออกแบบ key management ฝั่งผู้ให้กู้ (เก็บ private key ที่ไหน ปลอดภัยแค่ไหน)
- [ ] เทอม 2: รองรับเซ็น PSBT ของ liquidation แบบเต็ม

---

## 5. monitor-service (คนที่ 3 — ร่วมกับ custody-service + lender-signer-cli)

**สถานะปัจจุบัน**
- มี `PriceFeed` trait + สูตร LTV/liquidation ที่ตรงกับฝั่ง mobile app เป๊ะ (ทดสอบแล้วเลข liquidation price ตรงกัน)
- รันเป็น loop 5 tick พิมพ์สถานะ LTV ของ 3 loan ตัวอย่างออก console ได้จริง
- ราคาเป็น **mock random walk** ไม่ใช่ราคาจริง, ยังไม่เชื่อม custody-service จริง (แค่ print ข้อความเตือน)

**สิ่งที่ต้องทำทั้งหมด**
- [ ] เชื่อม Esplora API จริง (public, MVP) แทน mock price feed — โครงสร้าง trait รองรับการสลับอยู่แล้ว
- [ ] เปลี่ยนจาก one-shot loop เป็น scheduler ทำงานต่อเนื่อง (cron/interval)
- [ ] ดึงรายการ loan ที่ active จริงจาก `custody-service` แทน mock 3 สัญญา
- [ ] เชื่อม HTTP เรียก `custody-service` จริงเพื่อ trigger margin-call/liquidation (ต้องรอ custody-service เพิ่ม endpoint รับ trigger ก่อน)
- [ ] เทอม 2: คำนวณจำนวน BTC ที่ต้องขายสำหรับ liquidation แบบเต็ม (ร่วมกับ vault-core)

---

## ภาพรวม: สิ่งที่ยังไม่ได้แตะเลยทั้งระบบ

- **mobile-signer-ffi ยังไม่มีเลเยอร์ hot wallet เลย** — ขอบเขตขยายเป็นสองเลเยอร์ (hot wallet + vault signer) ตามดีไซน์ล่าสุด แต่โค้ดตอนนี้มีแค่ฝั่ง vault signer เท่านั้น ดูรายละเอียดในหัวข้อ 3 ด้านบน — เป็นช่องว่างที่ใหญ่ที่สุดในระบบตอนนี้
- **Bitcoin testnet จริง** — ทุกอย่างตอนนี้เป็น mock/in-memory ล้วน ยังไม่มีการทดสอบบน testnet จริงแม้แต่ครั้งเดียว (เป็นเกณฑ์ข้อ 1 ใน Definition of Done ของ [`00-capstone-brief.md`](00-capstone-brief.md) §5)
- **การเชื่อมต่อระหว่าง service จริง** — custody-service ↔ monitor-service ↔ lender-signer-cli ↔ mobile app ยังไม่มีเส้นเชื่อมไหนที่เป็นของจริงเลยนอกจาก lender-signer-cli fetch จาก custody-service ได้
- **Auth/security ระหว่าง service** — ยังไม่มีเลย
- **เอกสาร**: API spec แบบละเอียด, threat model ของ policy engine (ข้อ 5 ใน Definition of Done) ยังไม่ได้เขียน
- **CI** — ยังไม่ได้ตั้ง
