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

ยังไม่ได้ทดสอบบนเครื่อง: จอยืนยันการเชื่อมต่อ ("Allow … to connect") ซึ่งมีแต่ test กับ Safe 7 จำลอง และ `BondRemoved` (Safe 7 ลืม host แต่ host ยังจำ Safe 7) (อัปเดต 2026-10-23: `BondRemoved` เห็นบน Mac แล้วหลังกู้ Safe 7 ข้อความตรงและแก้ได้ด้วยการลืมอุปกรณ์ใน macOS Bluetooth ดู `04-open-items.md` ข้อ 30)

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
- `rust/` มีโค้ดฝั่ง vault signer เท่านั้น และ **ตั้งแต่ 2026-10-20 เปิดให้ Kotlin เรียกผ่าน UniFFI แล้ว** (`#[uniffi::export]`, uniffi `=0.29.4`): `derive_borrower_pubkey`, `compute_vault_address` และ `sign_psbt` ซึ่งทั้งสามตัวยังต่อกับ mock ของ `vault-core` (`sign_psbt` ยังเป็น mock signature) กับฟังก์ชันใหม่ `compute_vault_address_multisig(xpubs, threshold)` ที่เรียก `build_multisig_descriptor` และ `derive_address_at` ของ `vault-core` จริงและได้ address จริง (index 0, network ดูจาก prefix ของ xpub) มี 17 test ผ่าน (เดิม 5) Kotlin binding อยู่ในโมดูลใหม่ `app/mobileSignerFfi` ที่สร้างด้วย Gobley 0.3.7 ผ่าน `scripts/ffi.sh` `shared` ขึ้นกับโมดูลนี้ และมีแค่โพรบฮาร์ดแวร์ชั่วคราวใน `shared` ที่เรียกใช้ ส่วน UI ยังไม่เรียก **ยังไม่มี `bdk` dependency หรือ hot-wallet code ใดๆ**
- **ตั้งแต่ 2026-10-21 `rust/` มี export Bluetooth แบบ async สำหรับ Jade และ Trezor Safe 7 ด้วย** (`scan_wallets`, `WalletLink` ที่มี `connect`, `info`, `disconnect`) ใช้ `jade-ble` และ `trezor-ble` ตรงๆ บน btleplug 0.13.3 ผลทดสอบบนเครื่องจริงอยู่ในหัวข้อ "บันทึกการทดสอบ Bluetooth บน KMP app" ด้านล่าง
- `app/` เป็น Expo React Native app ที่ **รันได้จริง** (เว็บ/iOS/Android ผ่าน simulator) ครบ 12 หน้าจอตาม design ล่าสุด รองรับโมเดลหลายสัญญาเงินกู้พร้อมกัน (multi-loan) แล้ว
- ข้อมูลทั้งหมดใน `app/` ยังเป็น **mock ล้วน** (`mockVault.ts`, TypeScript) — ยังไม่ได้เชื่อมกับ `rust/` เลย (คนละภาษา ยังไม่มี native module bridge) — รวมถึงยังไม่มี concept ของ UTXO/fee/node connectivity จริงใน mock เลย เพราะตอนสร้าง mock ยังไม่รู้ว่าต้องมี hot-wallet layer

**สิ่งที่ต้องทำทั้งหมด**
- [ ] **ตัดสินใจ**: ใช้ `bdk` Rust core สำหรับเลเยอร์ hot wallet ตามที่เอกสารแนะนำ หรือ implement เอง (ดู `04-open-items.md` ข้อ 2) — ยังไม่ได้ตัดสินใจเป็นมติ
- [ ] เริ่มเลเยอร์ hot wallet (single-sig): address generation, UTXO tracking, fee estimation, ส่ง/รับ BTC, เชื่อมต่อ Electrum/Esplora, สร้าง PSBT แบบ watch-only — **ยังไม่มีโค้ดแม้แต่บรรทัดเดียว**
- [x] เปิดฟังก์ชันของ `mobile-signer-ffi` ให้ Kotlin เรียกผ่าน UniFFI: เพิ่ม `uniffi` dependency, ใส่ `#[uniffi::export]`, generate binding สำหรับ Kotlin Multiplatform — ทำแล้วเมื่อ 2026-10-20 (ไม่ได้ทำ Swift เพราะ KMP ครอบคลุม iOS) เฉพาะ `compute_vault_address_multisig` ที่ต่อ API ของ `vault-core` ซึ่งนิ่งแล้ว วิธีรัน สิ่งที่ตรวจแล้วและสิ่งที่ยังไม่ได้ตรวจอยู่ใน `mobile-signer-ffi/README.md`
- [x] Bluetooth สำหรับ Jade และ Trezor Safe 7 ใน KMP app (2026-10-21): export async ผ่าน UniFFI, เพิ่ม guard ใน `jade-ble` และ `trezor-ble` ให้การสแกนและลิงก์ที่ยังไม่เสร็จถูกปล่อยเมื่อยกเลิก, ตั้งค่าสิทธิ์ iOS (`NSBluetoothAlwaysUsageDescription`) และ Android (API 31+ ขอ `BLUETOOTH_SCAN` กับ `BLUETOOTH_CONNECT`), และโพรบสำหรับตรวจบนเครื่อง **ทดสอบกับ Jade จริงบน iPhone 15, Galaxy S25 FE (Android 16) และ POCO X3 NFC (Android 11) แล้ว ส่วน Trezor Safe 7 ผ่านบน iPhone 15 และ POCO X3 NFC (2026-10-23) แต่ยังไม่ได้ทดสอบบน Galaxy S25 FE** รายละเอียดอยู่ใน `mobile-signer-ffi/README.md` และ `04-open-items.md` ข้อ 29–34 รอบที่สอง (2026-10-21) แก้ช่องรั่วของ wrapper ที่ Gobley generate เมื่อเรียกจาก coroutine ที่ถูกยกเลิกแล้ว ด้วยแพตช์ใน `ffi.sh` กับ test `WalletLinkCancelTest` (ข้อ 31) และรันบน Galaxy S25 FE (หัวข้อด้านล่าง)
- [x] ทดสอบ Trezor Safe 7 บน iPhone และ Android (สแกน, จับคู่ด้วยรหัส 6 หลัก, `info`, `cancel-connect:trezor`, MTU) — ผ่านบน iPhone 15 และ POCO X3 NFC (Android 11) เมื่อ 2026-10-23 (MTU 247 ทั้งสองเครื่อง) ดูหัวข้อ "บันทึกการทดสอบรอบสาม" ด้านล่าง ส่วน Galaxy S25 FE ยังไม่ได้ทดสอบกับ Safe 7
- [ ] ลบโพรบฮาร์ดแวร์กับ hook ทั้งสอง (`04-open-items.md` ข้อ 34): เงื่อนไขของผู้ใช้ ("เก็บไว้จนกว่า Safe 7 ผ่าน") ผ่านแล้ว รอผู้ใช้ตัดสินใจ
- [ ] เมื่อ `vault-core` เปลี่ยน mock เป็นของจริง (`build_descriptor`, `derive_child_pubkey`, การเซ็น PSBT): ให้ฟังก์ชัน 3 ตัวเดิมของ wrapper (`compute_vault_address`, `derive_borrower_pubkey`, `sign_psbt`) ใช้ของจริง — ตอนนี้ทั้งสามเปิดให้ Kotlin เรียกได้แล้วแต่ยังเป็น mock และ `vault-core` ยังเป็น "simplified skeleton" ตามหัวไฟล์ของมัน
- [ ] เชื่อม UI เข้ากับ native binding จริงแทน `mockVault.ts` ทีละฟังก์ชัน (ทั้งฝั่ง hot wallet และ vault signer)
- [ ] ทำ verification/challenge-response flow ตอนเปิด loan (ตรวจจับ pubkey derive ผิด) — **ยังไม่มีเลยตอนนี้**
- [ ] เชื่อม UI margin-call/liquidation กับข้อมูลจริงจาก `monitor-service`/`custody-service` แทน mock
- [ ] เชื่อม Receive/Borrow/Send flow กับ testnet จริงผ่าน node connectivity ของแอปเอง (ไม่ผ่าน backend) — ตรงกับ Definition of Done ข้อ 6 ใน `00-capstone-brief.md`
- [ ] (ถ้าจำเป็น) เปลี่ยนจาก state-switch ง่ายๆ ใน `App.tsx` เป็น react-navigation เมื่อโครงสร้างซับซ้อนขึ้น
- [ ] ถ้าเวลาไม่พอ: ใช้แนวทาง MVP-screens-ก่อน ตาม `00-capstone-brief.md` §3.3 (onboarding→success ก่อน, activity/portfolio/settings เป็น stretch goal)

### บันทึกการทดสอบ Bluetooth บน KMP app (2026-10-21)

เครื่อง: iPhone 15 (`iPhone15,4`, iOS 27.0.1) ต่อสายกับ Mac · Jade Core `Jade BE0184` (board `JADE_V2C`, firmware 1.0.41) · โค้ดคือ working tree บน `8c6201c` ที่ยังไม่ commit · รันผ่านโพรบของแอป (`MEBIT_HWPROBE`) หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-21/` นอก repo

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| BLE-1 | Mac: `jade-hw-test info` กับโค้ดล่าสุด | ✅ `Jade BE0184`, 1.0.41, `JADE_V2C`, `Locked`, `TEST` เหมือนผลเดิมในหัวข้อบน |
| BLE-2 | iPhone: `jade` (สแกน, เชื่อมต่อ, `info`, ตัดการเชื่อมต่อ) | ✅ ได้ค่าเดียวกับ BLE-1 (`get_version_info`) id ที่เห็นเป็น UUID ของ CoreBluetooth ต่อแอป ไม่ใช่ MAC |
| BLE-3 | iPhone: `cancel` (ยกเลิกการสแกนที่ 1.5 วินาที แล้วรันเต็ม) | ✅ |
| BLE-4 | iPhone: `cancel-connect:jade:500` และ `:1000` (ยกเลิก `connect` กลางทาง แล้วรันเต็ม) | ✅ การสแกนแรกหลังยกเลิกเจอ Jade (ไม่ต้องสแกนซ้ำ) ไม่มีลิงก์ค้าง (`:500` ผ่านทุกรอบ ส่วน `:1000` บางรอบ `connect` จบก่อนยกเลิก จึงนับเฉพาะรอบที่ยกเลิกทัน) |
| BLE-5 | BLE-4 อีกครั้ง โดยถอด guard ของลิงก์ใน `JadeBle::connect` ออกชั่วคราว | ✅ ล้มตามที่คาด: iPhone ไม่เจอ Jade 30 วินาที, Mac ก็ไม่เจอตอนแอปยังรัน แล้วเชื่อมต่อได้หลังแอปปิด แสดงว่าการทดสอบจับลิงก์ค้างได้จริง และ guard คือสิ่งที่ปล่อยลิงก์ (รอบ 1000 ms ในชุดที่ถอด guard จบก่อนยกเลิก จึงไม่นับ) |
| BLE-6 | iPhone: `ffi` | ✅ ได้ address ทอง `tb1q693w8s33wzc0pmrpu9yda3ngm2eu9hkzvkwagw4q69643my3xt0q9lcl0f` |
| BLE-7 | Android emulator API 36 (debug build ของโค้ดล่าสุด): สิทธิ์, สแกน, ปฏิเสธสิทธิ์, `ffi`, `cancel` | ✅ ไม่มี wallet จึงได้ `NotFound` ตามที่คาด |
| BLE-8 | Android R8 release build | ✅ build ผ่านและ dex ยังมี Java 28 class, JNA และการโหลด library (ตรวจกับโค้ดล่าสุด) ส่วนการรันจริงของ APK ที่ minify ทำบนโค้ดรุ่นก่อนที่จำกัดโพรบให้เฉพาะ debug build |
| ยังไม่ได้ทดสอบ | Trezor Safe 7 บน iPhone, Android และ Mac กับโค้ดล่าสุด, Bluetooth ปิดบน iOS, Android adapter ที่ปิดสนิท, Android รุ่นอื่น, เครื่องที่ใช้ page 16 KB | ไม่มีเครื่อง Safe 7 ในวันทดสอบ ดู `04-open-items.md` ข้อ 30 และ 33 |

ข้อสังเกตระหว่างทดสอบ: โพรบรอบแรกรายงาน `NotFound` เท็จหลังยกเลิก `connect` เพราะสแกนซ้ำก่อนที่ Jade จะกลับมา advertise Mac เจอ Jade อีก 30 วินาทีต่อมาขณะแอปยังรัน จึงไม่ใช่ลิงก์ค้าง โพรบจึงถูกแก้ให้รอได้ถึง 30 วินาทีและบอกว่ารอนานเท่าไร

### บันทึกการทดสอบรอบสอง: แก้ช่องรั่วของ Gobley และทดสอบบน Android จริง (2026-10-21 และ 2026-10-22)

เครื่อง: iPhone 15 (ตามเดิม) และ Samsung Galaxy S25 FE (SM-S731B, Android 16, API 36, One UI 8.5, arm64-v8a, page 4 KB) ต่อ USB กับ Mac · Jade Core `Jade BE0184` ตัวเดิม · โค้ดคือ working tree บน `8c6201c` ที่ยังไม่ commit · แพตช์ Gobley มีสองเวอร์ชัน: เวอร์ชันแรก (flag `started` กับ free สองตำแหน่ง) รันบน iPhone และบน Galaxy ส่วนใหญ่ เวอร์ชันปัจจุบัน (`finally` ตัวเดียวนอก `withContext`) รันใน Kotlin test ทุกตัวและบน Galaxy ตอนท้าย (AND-10) · หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-21/` นอก repo

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| FIX-1 | `WalletLinkCancelTest` โดยยังไม่แพตช์ | ✅ ล้มด้วยเหตุผลที่ถูก: test ควบคุมผ่าน และมีแต่ test ที่เรียกจาก coroutine ที่ยกเลิกแล้วล้ม (`expected: <0> but was: <1>` บน Android host JVM, `Expected <0>, actual <1>` บน iOS simulator) |
| FIX-2 | เหมือนกัน หลังแพตช์เวอร์ชันแรก | ✅ ผ่านทั้ง 5 test บน Android host JVM และบน iOS simulator, `ffi.sh check` ผ่าน, mutation ทั้งหกจุดของ Rust ยังล้มตามเดิม (ไม่มีตัวรอด) ซึ่งยังไม่ได้ mutate ตัวแพตช์เอง |
| FIX-3 | iPhone 15 หลังแพตช์เวอร์ชันแรก: `jade` และ `cancel-connect:jade:500` | ✅ ผ่านทั้งคู่ ได้ค่า `info` เดิม และยกเลิกกลางทางแล้วการสแกนแรกเจอ Jade ผู้ใช้รายงานว่าไม่เห็น prompt จับคู่บน iPhone หรือ Jade ในสองรอบนี้ นี่เป็น regression check ไม่ใช่หลักฐานของเส้นทาง "ยกเลิกอยู่แล้ว" ที่แพตช์แก้ (เส้นทางนั้นรันแค่ใน unit test) และยังไม่ได้รัน iPhone บน wrapper ปัจจุบัน |
| FIX-4 | รีวิวสามโมเดล (Opus, Sonnet, Haiku) | ✅ เปลี่ยนแพตช์เป็น `finally` ตัวเดียวนอก `withContext`, เพิ่ม `AsyncWrapperFreeTest` (5 เส้นทาง, Android host JVM) และ `test_patch_bindings.py` (6 test) ผ่านทั้งหมด (Android host 10 test, iOS simulator 5 test) mutant ของ wrapper ล้มตามคาด: ฟังก์ชันเดิมของ Gobley ล้ม 3 test, free ซ้ำล้ม test นับและทำให้ JVM crash, ไม่ free เลยล้ม 6 test |
| AND-1 | Galaxy: ปฏิเสธสิทธิ์สองครั้ง | ✅ ครั้งแรก `ffi` ยังพิมพ์ address ทองได้ ครั้งที่สอง `jade` ได้ `PermissionDenied` ข้อความ `Need android.permission.BLUETOOTH_SCAN permission ...: ScanBinder.registerScanner()` ไม่ crash |
| AND-2 | Galaxy: สวิตช์ Bluetooth ปิด | ⚠️ adapter ค้างที่ `BLE_ON` (`ble_scan_always_enabled` เป็น 1): ได้ `NotFound` หลัง 6.1 วินาที ไม่ใช่ `BluetoothOff` ข้อความเดิมใน `ble.rs` และ `platform.rs` ที่ว่า Android ปิดแล้วได้ `BluetoothOff` ถูกสำหรับ adapter ที่ปิดสนิท (อ่านจาก source ยังไม่ได้รัน) แต่ผิดสำหรับ `BLE_ON` จึงแก้เป็นสองกรณี (ข้อ 30) |
| AND-3 | Galaxy: จับคู่ครั้งแรกกับ Jade | ✅ กล่องโต้ตอบของระบบบนมือถือสองขั้น bond ใช้เวลา 26.8 วินาที `info` ได้ค่าเดียวกับ Mac และ iPhone (ฝั่ง Jade สรุปจาก bond ที่สำเร็จ ไม่มี log หรือรูป) |
| AND-4 | Galaxy: `jade` ซ้ำ 3 รอบ และ `cancel:jade` | ✅ ไม่มี prompt, `info` กลับมา 0.1 ถึง 0.5 วินาทีหลัง `connected` (สี่รอบ), MTU 517 |
| AND-5 | Galaxy: `cancel-connect:jade:500` 3 รอบ และ `:200` 8 รอบ (แพตช์เวอร์ชันแรก 5 รอบ, ปัจจุบัน 3 รอบ) | ✅ `:200` ยกเลิกกลางทางทุกรอบและการสแกนแรกเจอ Jade 4 วินาทีหลังยกเลิก ส่วน `:500` มี 2 จาก 3 รอบที่ `connect` จบก่อนยกเลิก (ไม่นับ) |
| AND-6 | Galaxy: ถอด guard ของลิงก์ใน `JadeBle::connect` (build ที่แก้ชั่วคราว) | ✅ ล้มตามที่คาด: ไม่เจอ Jade 36 วินาที, Mac ก็ไม่เจอตอนแอปยังรัน แล้วเชื่อมต่อได้หลัง `am force-stop` ส่วน build ที่มี guard เจอ Jade ใน 4 วินาทีทั้ง 9 จาก 9 รอบ (สี่ build) restore แล้วตรวจ hash ตรงกับ build ที่แช่แข็ง |
| AND-7 | Galaxy: `scan-twice:jade` | ✅ เปิด Jade แล้วสแกนครั้งที่สองเจอ ถอดไฟ Jade ระหว่างรอ 45 วินาทีแล้วสแกนครั้งที่สองได้รายการว่าง (ยังไม่ได้รัน build ที่ไม่มี `clear_peripherals()`) |
| AND-8 | Galaxy: `cancel:jade` สองรอบติดกัน | ✅ เจอข้อจำกัดของ Android จริง: การเริ่มสแกนครั้งที่หก (19.5 วินาทีหลังครั้งแรก) ถูกปฏิเสธ (`status=6`) และ `connect` ได้ `NotFound` ทั้งที่ Jade อยู่ใกล้ |
| AND-9 | Galaxy: build ที่มี comment ใหม่ (แพตช์เวอร์ชันแรก): `jade` และ `cancel-connect:jade:200` | ✅ ผ่านทั้งคู่ ไม่ crash |
| AND-10 | Galaxy: wrapper ปัจจุบัน (`finally` ตัวเดียว, สอง build ที่ต่างกันแค่ข้อความ comment): `jade` สองรอบ, `cancel:jade`, `cancel-connect:jade:200` สามรอบ | ✅ ผ่านทั้งหมด ไม่ crash ทุกการเรียกในรอบนี้ free future ใน `finally` ตัวใหม่ จึงรันโค้ดที่แพตช์ ส่วนเส้นทาง "ยกเลิกอยู่แล้ว" โพรบทำไม่ได้ รันแค่ใน unit test |
| ยังไม่ได้ทดสอบ | Trezor Safe 7 บน iPhone และ Android, Android รุ่นอื่น, API 30 ลงไป, เครื่อง page 16 KB, release build บนเครื่องจริง, Android adapter ที่ปิดสนิท (`BluetoothOff` อ่านจาก source), iPhone บน wrapper ปัจจุบัน, iPhone ยังจับคู่กับ Jade ได้ไหมหลัง Galaxy จับคู่กับ Jade ตัวเดียวกัน | ไม่มีเครื่อง Safe 7 และไม่ได้รันส่วนที่เหลือ ดู `04-open-items.md` ข้อ 30 และ 33 |

**prompt จับคู่:** บน Galaxy prompt อยู่บนมือถือ (AND-3 มีภาพหน้าจอและ log) ส่วน prompt ที่ผู้ใช้เล่าว่าเห็นระหว่างทดสอบ iPhone รอบแรกระบุไม่ได้ว่าอยู่บน iPhone หรือ Jade เพราะไม่มีหลักฐานที่จับไว้

### บันทึกการทดสอบรอบสาม: Safe 7 บน iPhone และ Android, Android 11 (2026-10-23)

เครื่อง: iPhone 15 (iOS 27.0.1) · POCO X3 NFC (M2007J20CG, Android 11 API 30, MIUI 12.5, arm64-v8a, page 4 KB) · Mac (macOS 27.0.1) · Jade Core `Jade BE0184` (firmware 1.0.41) · Trezor Safe 7 (T3W1, firmware 2.13.0, testnet และ phrase สาธารณะเท่านั้น) · โค้ดคือ working tree บน `8c6201c` ที่ยังไม่ commit บวกการแก้ Kotlin เล็กน้อย (`locationOffBlocksScans` กับบรรทัด `HWPROBE precheck`) APK ของ POCO มีสองตัว: `cde058371c1dd865` (Jade และ matrix สิทธิ์) กับ `eb4a93363f554f64` (Safe 7 และ precheck) library native ตรงกันทุกไบต์ · หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-23/` นอก repo (มี `INDEX.md`) · รหัส 6 หลักของ Safe 7 ผู้ใช้พิมพ์เองผ่าน helper แบบซ่อน ไม่อยู่ในไฟล์ใดๆ

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| MAC-1 | Mac: Safe 7 หลังผู้ใช้กู้ phrase สาธารณะ | ✅ run แรกได้ `BondRemoved` (macOS ยังจำ key ที่ Safe 7 ลืม) หลังลืมอุปกรณ์ใน macOS Bluetooth แล้วจับคู่ได้ (49 วินาทีรวมขั้นตอนผู้ใช้, MTU 247) และ `xpub --verify-against` ได้ fingerprint `73c5da0a` ตรงกับที่ `vault-core` derive จาก phrase ก่อนหน้านั้น run บน seed เดิมของเครื่องได้ fingerprint ต่าง จึงหยุดงาน Safe 7 ทั้งหมดจนกว่าจะกู้ phrase |
| MAC-2 | Mac: Safe 7 `info` | ✅ Safe 7, T3W1, firmware 2.13.0 (2.12.5 เมื่อ 2026-10-01 ผู้ใช้อัปเดตระหว่างกู้ phrase), MTU 247, ไม่ถามรหัส (ใช้ credential) การสแกนไม่เสถียรตอน Mac bond แล้ว: 10 วินาทีเจอ 1 ใน 3, 20 วินาทีไม่เจอ, 30 วินาทีเชื่อมต่อได้หลัง 35 วินาที |
| IOS-1 | iPhone: `scan-twice:trezor:10000` | ✅ id และ suffix เดิมทั้งสองสแกน |
| IOS-2 | iPhone: `trezor` จับคู่ครั้งแรก | ✅ ผ่านในการเปิดแอปครั้งที่สี่: `info TrezorSafe7 ... firmware=2.13.0 mtu=247` 45 วินาทีรวมขั้นตอนผู้ใช้ สองรอบแรกพลาดเพราะยังไม่ได้แจ้งขั้นตอนให้ผู้ใช้ล่วงหน้า (ไม่มีใครกด prompt → `Link: Runtime Error: Encryption is insufficient.` หลัง 36 วินาที แล้ว `Timeout: timed out connecting` 50 วินาที จนออกจากจอ Pair new device แล้วเปิดใหม่) |
| IOS-3 | iPhone: `trezor` ซ้ำเมื่อ bond แล้ว (Safe 7 อยู่หน้าแรก) | ✅ ไม่มี dialog ของ iPhone ต้องกดยืนยัน "Allow…?" และใส่รหัสใหม่ `info` เดิม |
| IOS-4 | iPhone: `cancel-connect:trezor` | ✅ ยกเลิกในหน้ารหัส Safe 7 เจออีกใน 4 วินาที รันเต็มผ่านด้วยรหัสที่สอง |
| IOS-5 | iPhone: รหัสผิดรูปและรหัสผิด | ✅ พิมพ์ 5 หลัก → ถามใหม่ใน 9 วินาที `000000` → `PairingFailed: Trezor: pairing failed: the Trezor refused the code; reconnect and pair again` |
| IOS-6 | iPhone: Jade ตัวเดิมหลัง POCO จับคู่ | ✅ เชื่อมต่อใน 4.9 วินาทีโดยไม่มี prompt (ก่อนหน้านั้นการเชื่อมต่อ Jade ครั้งแรกของวันใช้ 19.9 วินาที เป็นการจับคู่ใหม่ ผู้ใช้เล่าว่าเห็น prompt ทั้งบน iPhone และ Jade ไม่มีภาพหรือ log) |
| AND-11 | POCO: สิทธิ์กับ Location (สี่กรณี) | ✅ ไม่มีสิทธิ์ (Location เปิดหรือปิด) และมีสิทธิ์แต่ Location ปิด ได้ `NotFound` ทั้งสามกรณี มีสิทธิ์และ Location เปิดจึงใช้ได้ (Android 16 ได้ `PermissionDenied`) บรรทัด `HWPROBE precheck` ใหม่ตรงกับพฤติกรรม |
| AND-12 | POCO: จับคู่ Jade ครั้งแรก | ✅ prompt เป็นแจ้งเตือน heads-up ที่ค้างไม่กี่วินาที รอบแรกไม่มีใครแตะ: `Disconnected: Not connected` หลังคำขอจับคู่ 33 วินาที bond ไม่ค้าง รอบสองแตะทัน `info` กลับมา 21 วินาทีหลังเริ่ม |
| AND-13 | POCO: Jade bonded 3 รอบ, `cancel:jade`, `cancel-connect:jade` | ✅ ไม่มี prompt (bond เปลี่ยน BONDED→BONDING→BONDED เงียบๆ) `:500` จบก่อนยกเลิก (ไม่นับ) `:200` สองรอบ, `:100`, `:50` ยกเลิกกลางทางและรันเต็มผ่าน แต่ Jade กลับมาให้สแกนเจอที่ 12 วินาที (Galaxy และ iPhone 4 วินาที) |
| AND-14 | POCO: Safe 7 (`scan-twice`, จับคู่ครั้งแรก, bonded, `cancel-connect:trezor`) | ✅ MTU 247 (btleplug ขอ 517) จับคู่ครั้งแรกมี dialog ของระบบแล้วจึงถึงจอยืนยันของ Safe 7 และรหัส ครั้งที่ bond แล้วไม่มี dialog ยกเลิกในหน้ารหัสแล้ว Safe 7 เจอใน 4 วินาที |
| AND-15 | POCO: Bluetooth ปิด | ✅ `ble_scan_always_enabled`=1 → `BLE_ON` → `NotFound` (6 วินาที) ปิด scanning ด้วย → adapter `OFF` → `BluetoothOff: No Bluetooth adapter available` (70 มิลลิวินาที) คืนค่าแล้ว |
| AND-16 | POCO: Safe 7 ลบ POCO ออกแล้วเปิด Pair new device | ✅ ไม่มี error Android จับคู่ใหม่ด้วย dialog เดียวและใช้งานได้ (macOS ได้ `BondRemoved` ในกรณีเดียวกัน) |
| JADE-1 | Mac: Jade หลัง POCO จับคู่ | ⚠️ `Peer removed pairing information` Jade ลบ bond ของ Mac (เก่าสุด) ต้องลืม Jade ใน macOS Bluetooth ก่อนจับคู่ใหม่ ยังไม่ได้ทำ |
| GATE | 20 gates บนโค้ดที่แก้ | ✅ ผ่านทั้งหมด (ราว 7 นาที) และ build `androidApp` ซ้ำได้ hash เดิม |
| ยังไม่ได้ทดสอบ | Safe 7 บน Galaxy S25 FE และ Android 12 ขึ้นไป, Android 12 ขึ้นไปที่ Location ปิด, Safe 7 ที่หลับบนมือถือ, `BondRemoved` บน iOS, negative control ของ guard ใน `TrezorBle::connect` บน Safe 7, ความจุ bond ของ Jade และ Safe 7, `x86_64`, เครื่อง page 16 KB, release build บนเครื่องจริง, authenticity ของ Safe 7 | ไม่มีเครื่องหรือไม่ได้รัน ดู `04-open-items.md` ข้อ 30, 33, 35 ถึง 37 |

**สิ่งที่ผู้ใช้ต้องรู้หรือทำต่อ:** (1) Mac ของผู้ใช้เสีย bond กับ Jade (JADE-1) ต้องลืม Jade ใน macOS Bluetooth ก่อนใช้ `jade-hw-test` อีกครั้ง (2) Safe 7 เปลี่ยน auto-lock ระหว่างทดสอบ (ไฟ USB 6 วัน, แบตเตอรี่/ไร้สาย 10 นาที) ไม่ได้บันทึกค่าเดิมไว้ (3) POCO: เดิม Location ปิด ตอนนี้เปิดอยู่ (4) โพรบฮาร์ดแวร์ยังอยู่ รอผู้ใช้ตัดสินใจลบ (ข้อ 34)

### บันทึกการทดสอบรอบสี่: error ของการจับคู่และ bond, fallback ของ id (2026-10-23)

เครื่อง: ตามรอบสาม (Mac, iPhone 15, POCO X3 NFC Android 11, Jade, Safe 7 firmware 2.13.0 testnet และ phrase สาธารณะ) · โค้ดคือ working tree บน `8c6201c` ที่ยังไม่ commit ไฟล์ที่เปลี่ยนในรอบนี้ 9 ไฟล์ (`trezor-ble/src/lib.rs`, `jade-ble/src/lib.rs`, `trezor-hw-test/main.rs`, `ble.rs`, `ble/vendors.rs`, `ble/platform.rs`, `mobile_signer_ffi.common.kt` ที่ generate ใหม่, `HardwareProbe.kt`, `ProbeModeTest.kt`) · APK `0f8775346c719f8f` (library arm64 `532545b986e87cc5`) และแอป iPhone ที่ link กับ library iOS `e8dbfa33924b9d41` · หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-23/round4/` นอก repo (มี `INDEX.md`) · รหัส 6 หลักของ Safe 7 ผู้ใช้พิมพ์เองผ่าน helper แบบซ่อน ไม่อยู่ในไฟล์ใดๆ

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| UNIT-4 | unit test กับ mutation | ✅ `trezor-ble` 24 test (เดิม 18), `jade-ble` 12 (เดิม 10), wrapper 40 (เดิม 36), `ProbeModeTest` เพิ่ม clippy กับ fmt ผ่าน mutation 19 จุด (6 เดิม กับ 13 ใหม่) ล้มด้วย test ที่ตั้งใจทุกจุดและไฟล์คืนค่าตรงทุกไบต์ จุดเรียก `while_connecting` ใน `connect()` ไม่มี unit test (ต้องมีวิทยุ) จึงมีแต่หลักฐานจากเครื่องจริง |
| GATE-4 | 20 gates | ✅ ผ่านทั้งหมด ไฟล์ generate เปลี่ยนไฟล์เดียว (`mobile_signer_ffi.common.kt` จาก doc comment) |
| MAC-3 | Mac: `jade-hw-test info` ตอน bond ถูก Jade ลบ | ✅ ได้ "the Jade no longer knows this host: forget it in the Bluetooth settings, then connect again and confirm the pairing on the Jade" แทนข้อความดิบของรอบสาม |
| MAC-4 | Mac: `xpub --verify-against` กับ `trezor-ble` ที่แก้แล้ว | ✅ จับคู่ใหม่ 56 วินาที (MTU 247) fingerprint `73c5da0a` ตรงกับ phrase |
| MAC-5 | Mac: fallback ด้วย id ปลอม | ✅ เห็น Safe 7 แล้ว ไม่มี `--device-is-hint` ได้ `NotFound` อีก 12 วินาทีต่อมามี flag เชื่อมต่อได้ (สองครั้งแรกล้มเพราะ Safe 7 หยุด advertise จึงไม่นับ) |
| IOS-7 | iPhone: Safe 7 ลืมโทรศัพท์แต่ iOS ยังจำ key | ✅ `BondRemoved` พร้อมคำแนะนำของ Safe 7 (ครั้งแรกบน iOS) |
| IOS-8 | iPhone: ไม่แตะ prompt การจับคู่ใดเลย | ✅ `PairingFailed: the Bluetooth pairing was not completed: confirm the pairing request on this device and on the Safe 7 within about 30 seconds…` ที่ 36 วินาที |
| IOS-9 | iPhone: `trezor-stale` เป็นการจับคู่ครั้งแรกจริง | ✅ เชื่อมต่อผ่าน fallback ได้ `info` กับ `mtu=247` log ไม่มีตัวเลข 6 หลัก |
| IOS-10 | iPhone: Safe 7 bonded, Jade bonded, `cancel-connect:jade:500` | ✅ ผ่านทั้งหมด |
| IOS-11 | iPhone: `cancel-connect:trezor` | ⚠️ ครึ่งยกเลิกผ่านสามครั้ง (Safe 7 เจอใน 4 วินาที) แต่รันเต็มล้มสามครั้งด้วยเหตุจากจังหวะของผู้ใช้ (ส่งรหัสเร็วไปแล้วถูกปฏิเสธ แล้วกดปุ่มบน Safe 7 สองครั้ง) ไม่ได้ทำซ้ำบน iPhone ผ่านบน POCO (AND-20) |
| AND-17 | POCO: กด Cancel ที่ prompt การจับคู่ (APK รอบสาม) | ✅ วัดได้ `Disconnected: the Trezor disconnected` เปล่าๆ ที่ 10 วินาทีหลังสแกน (สี่ครั้งกว่าจะวัดได้: Safe 7 หยุด advertise, ผู้ใช้กดยืนยันไปแล้ว, สแกนซ้ำไม่เจอ) |
| AND-18 | POCO: กด Cancel ที่ prompt (APK ใหม่) | ✅ `Disconnected: the Safe 7 dropped the link while connecting. If a pairing request was waiting, it was not confirmed in time…` |
| AND-19 | POCO: `trezor-stale` เป็นการจับคู่ครั้งแรกจริง และ Jade จับคู่ครั้งแรก, bonded, `cancel-connect:jade:200` | ✅ ผ่าน fallback ได้ `mtu=247` prompt บน Safe 7 ขึ้น "Allow mebit on mebit Android to pair?" ส่วน Jade เจอใหม่หลังยกเลิกที่ 12 วินาทีเหมือนเดิม |
| AND-20 | POCO: `cancel-connect:trezor` | ✅ รันเต็มผ่าน Safe 7 เจอใน 4 วินาทีหลังยกเลิก `mtu=247` |
| ยังไม่ได้ทดสอบ | Safe 7 บน Galaxy S25 FE, fallback บน Android 12 ขึ้นไป, id ที่เก่าจริง (เห็นแต่ id ปลอม), Jade ที่ปฏิเสธการจับคู่บน iOS (macOS ได้ `Device disconnected` ที่ยังไม่จัดประเภท), `BondRemoved` ที่ Jade บน iOS, ความจุ bond, negative control ของ guard ใน `TrezorBle::connect` บน Safe 7 | ไม่มีเครื่องหรือไม่ได้รัน ดู `04-open-items.md` ข้อ 30, 33, 35 ถึง 38 |

**สิ่งที่ผู้ใช้ต้องรู้หรือทำต่อ:** (1) Safe 7 ยังแสดงชื่อ `mebit mobile` ในหน้าจอหลัง "Pair & Connect" ขอให้ดูรายการ host บน Safe 7 (ข้อ 38) (2) Mac ยังไม่มี bond กับ Jade (3) POCO ตอนนี้มี Safe 7 และ Jade เป็น bond ใหม่ (4) ค่า auto-lock ของ Safe 7 ที่เปลี่ยนไว้ยังไม่ได้คืน (5) โพรบฮาร์ดแวร์ยังอยู่ รอผู้ใช้ตัดสินใจลบ

### บันทึกการทดสอบรอบห้า: ชื่อโฮสต์ใน Safe 7, id ที่เก่าเอง, cancel-connect แบบมี gate, Jade ที่ปฏิเสธการจับคู่บน iOS (2026-10-23)

เครื่อง: Mac, iPhone 15, Jade BE0184 (firmware 1.0.41), Safe 7 firmware 2.13.0 (testnet และ phrase สาธารณะ) POCO X3 NFC ไม่ได้ใช้ในรอบนี้ (ยังเป็น APK รอบสี่ ซึ่งไม่มีโหมดใหม่) Galaxy S25 FE ไม่ได้เสียบ adb จึงไม่ได้ทดสอบ · โค้ด: ไม่แก้ Rust เลย (library iOS `e8dbfa33924b9d41` และ arm64 `532545b986e87cc5` ตรงกับรอบสี่) แก้เฉพาะโพรบ Kotlin (`HardwareProbe.kt`, `ProbeModeTest.kt` และ `FileCodesTest.kt` ใหม่) บน working tree ที่ยังไม่ commit · หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-23/round5/` นอก repo (มี `INDEX.md`, `TEST-PLAN.md` และสำเนา source ของ firmware 2.13.0 พร้อม hash) · รหัส 6 หลักของ Safe 7 ผู้ใช้พิมพ์เองผ่าน helper แบบซ่อน ไม่อยู่ในไฟล์ใดๆ

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| UNIT-5 | unit test กับ mutation ของโพรบ | ✅ `ProbeModeTest` เพิ่มกรณีของ `rotate` และ `cancel-connect-gated` (บวก 3 ลบ 9 สตริง) `FileCodesTest` ใหม่ 3 test (ทิ้งไฟล์รหัสที่มีอยู่ก่อนขอรหัส, gate รอไฟล์ go และไม่นับไฟล์ go เก่า, gate ที่หมดเวลาต้องจบเป็น error ไม่ใช่การยกเลิกเงียบ) รวม 10 test ของ `shared` บน Android ผ่าน mutation 4 จุด (ไม่ทิ้งไฟล์รหัสเก่า, gate นับ go เก่า, gate คืนทันที, `withTimeout` แทนที่จะเป็น error) ล้มด้วย test ที่ตั้งใจทุกจุดและไฟล์คืนค่าตรงทุกไบต์ |
| GATE-5 | 20 gates | ✅ ผ่านทั้งหมดบน tree ที่มีการแก้โพรบ (library Rust ไม่เปลี่ยน) และรันซ้ำบน tree สุดท้ายหลังแก้ gate ที่หมดเวลา (ดู `round5/INDEX.md`) |
| FW-1 | อ่าน source ของ firmware 2.13.0 (`core/v2.13.0`) | ✅ อธิบายรายการชื่อใน Safe 7: `cache_host_info` ข้ามการเขียนถ้า address มีอยู่แล้ว, รายการเมนู = bond join กับที่เก็บนี้, "Forget this device" ไม่แตะที่เก็บ, ที่เก็บถูกล้างเฉพาะตอนมี address ใหม่ (ข้อ 38) และ controller firmware เปิด privacy กับรีสตาร์ท advertising ที่ 30 วินาที (ข้อ 36) สำเนาไฟล์กับ hash อยู่ใน `round5/firmware-2.13.0/` |
| N0 | ผู้ใช้อ่านรายการอุปกรณ์ที่จับคู่บน Safe 7 | ✅ 3 รายการ `desktop` (Mac, app `mebit trezor-hw-test`) กับ `mebit mobile` สองรายการ ตรงกับที่ source ทำนาย (มีรายการของ Mac ด้วยชื่อ fallback) |
| N1 | Mac: จับคู่ใหม่ด้วยชื่อ `probe-A` แล้ว `probe-B` (ไม่มี credential, `xpub --verify-against`) | ✅ prompt ขึ้นชื่อสดทั้งสองครั้ง รายการยัง 3 รายการและรายการของ Mac ยังเป็น `desktop` / `mebit trezor-hw-test` ทั้งสองครั้ง fingerprint `73c5da0a` ตรงทั้งสองครั้ง (MTU 247) |
| IOS-12 | iPhone: `cancel-connect-gated:trezor` | ✅ ผ่านรอบแรก (1 รอบ) ยกเลิกในหน้ารหัส 15:17:07 แล้วรอ go ผู้ใช้ dismiss หน้ารหัสและเปิด Pair new device ใหม่ Mac เห็น Safe 7 อีกครั้ง (suffix ใหม่) go 15:19:52 เจอ Safe 7 ใน 4 วินาที ขอรหัส 15:20:49 เชื่อมต่อ 15:21:22 `mtu=247` ไม่มี `stale-code-discarded` เดิมล้ม 3 ครั้งในรอบสี่ และผ่าน 1 ครั้งในรอบสาม |
| IOS-13 | iPhone ลืม Safe 7 แล้วรัน `rotate:trezor:600` | ✅ สแกนแรก id `cf9cdeb6…` suffix `2J9` อีก 14 วินาที id เปลี่ยนเป็น `0d3e51a5…` (suffix เดิม ไม่มีใครแตะ) โพรบเชื่อมต่อด้วย id เก่า ผู้ใช้ยืนยัน Pairing Request ของ iPhone กับ prompt บน Safe 7 ได้ `info` `mtu=247` หลังการเปลี่ยน 62 วินาที (ว่าผ่าน fallback เป็นข้อสรุปจากเส้นทางของโค้ด) |
| MAC-6 | Mac: เฝ้า passive 13 นาที (192 รอบ รอบละ 4 วินาที) | วัดแล้ว: ขณะ bond อยู่เห็น id เดียว `197161c7…` 10 นาที (suffix เปลี่ยนหนึ่งครั้ง) หลังผู้ใช้ลืม Safe 7 ใน macOS Bluetooth เห็น id ต่างกัน 4 ค่าใน 95 วินาที (สองค่าคาบเกี่ยวกับตอนผู้ใช้เปิด Pair new device ใหม่) 19 รอบไม่เห็น Safe 7 ยังอธิบายไม่ได้ |
| MAC-7 | Mac: strict กับ hint ด้วย id ที่เก่าเอง | ✅ id เปลี่ยนเองที่ 20 วินาทีหลังสแกนแรก (`cb83e794…` เป็น `0d740e20…` suffix `2D3`) สแกนก่อนและหลังเห็นเครื่องใหม่ `--device <id เก่า>` ได้ `NotFound` และ `--device <id เก่า> --device-is-hint` เชื่อมต่อได้ (`MTU 247`) |
| IOS-14 | iPhone: Jade ที่ปฏิเสธหรือไม่ตอบการจับคู่ (ลืม Jade บน iPhone ก่อนทุกรอบ) | ⚠️ กด Cancel บน iPhone ได้ `Timeout: timed out discovering services` ที่ 19.5 วินาที (timeout 15 วินาทีของ `jade-ble`) reject บน Jade ได้ `Link: Runtime Error: Device disconnected` ที่ 18.5 วินาที ไม่แตะเลยได้ข้อความเดียวกันที่ 35.2 วินาที ไม่มีรอบใดเป็น `PairingFailed` ครั้งแรกของรอบ Cancel ผู้ใช้กด Pair โดยพลาด จึงเป็นการจับคู่ที่ยอมรับ (เชื่อมต่อใน 18 วินาที) ไม่นับ รอบละหนึ่งครั้ง (ข้อ 39) |
| ยังไม่ได้ทดสอบ | N2 (address ที่ Safe 7 ไม่เคยเก็บได้ชื่อสดและล้างรายการเก่าหรือไม่), Safe 7 บน Galaxy S25 FE, id ที่เก่าเองบน Android (มีแค่ log รอบสาม), strict-control บนมือถือ (wrapper ตั้ง hint เสมอ), สาเหตุของการเปลี่ยน address, Jade ที่ยังมี bond เก่ากับ iPhone จะต่างกันหรือไม่ | ไม่มีเครื่องหรือไม่ได้รัน ดู `04-open-items.md` ข้อ 36, 38, 39 |

**สิ่งที่ผู้ใช้ต้องรู้หรือทำต่อ:** (1) ชื่อ `mebit mobile` ของโทรศัพท์สองเครื่องในรายการ Safe 7 เป็นพฤติกรรมของ firmware ไม่ใช่บั๊กของเรา และจะไม่เปลี่ยนจนกว่า address จะถูกเก็บใหม่ (ข้อ 38) ควรเลือกชื่อโฮสต์สุดท้ายก่อนผู้ใช้จริงคนแรก (2) ต้องตัดสินใจข้อ 39: จะแปลง `Device disconnected` และ `Timeout` ของ discovery ที่เกิดระหว่าง connect ของ Jade เป็น hint หรือไม่ (ต้องแก้ Rust และรันทุกอย่างซ้ำ) (3) iPhone ไม่มี bond กับ Jade ตอนนี้ (ผู้ใช้เลือกไม่คืน) และการจับคู่ที่ยอมรับครั้งแรกของรอบนี้อาจทำให้ Jade ลบ bond ของ POCO ไปแล้ว ยังไม่ได้ตรวจ (4) Mac กับ iPhone จับคู่ Safe 7 ใหม่ในรอบนี้ POCO ไม่ได้ใช้ (5) โพรบฮาร์ดแวร์ยังอยู่ รอผู้ใช้ตัดสินใจลบ

### บันทึกการทดสอบรอบหก: wipe และ recover Safe 7, hint ของการจับคู่ Jade (2026-10-24)

เครื่อง: Mac, iPhone 15, POCO X3 NFC (Android 11, `adb install` ใช้ได้แล้วหลังผู้ใช้เปิดการติดตั้งผ่าน USB), Jade BE0184 (firmware 1.0.41), Safe 7 firmware 2.13.0 ที่ผู้ใช้ wipe จากเมนูอุปกรณ์แล้ว recover ด้วย phrase สาธารณะเดิม (testnet เท่านั้น) · โค้ด: แก้ `jade-ble` และ wrapper (ข้อ 39) library Rust เปลี่ยน: รอบทดสอบฮาร์ดแวร์ใช้ iOS `0d10a4fe587ee362`, arm64 `69743c5aff3b2a21`, APK `46ca5092b7545298` จากนั้นรีวิวแก้ doc comment สองจุด (จุดหนึ่งอยู่บน item ที่ export จึงเปลี่ยน checksum ของ UniFFI) build สุดท้ายคือ iOS `60dd6e2ff68402f5`, arm64 `361c8bea8135bf33`, APK `02c5b843b0c19699` ติดตั้งบนทั้งสองเครื่อง (ตรวจ hash ของ APK หลัง pull ตรง) ผ่านโหมด `ffi` และรัน Jade bonded บนแต่ละเครื่อง ส่วนชุดทดสอบ Jade และ Safe 7 ไม่ได้รันซ้ำบน build สุดท้าย (ข้อสรุปว่าโค้ดไม่เปลี่ยน ไม่ได้เทียบระดับ instruction) บน working tree ที่ยังไม่ commit · หลักฐานดิบอยู่ใน `~/mebit-ble-evidence/2026-10-24/round6/` นอก repo (มี `INDEX.md`, `TEST-PLAN.md` และสำเนา source ของ firmware 2.13.0 พร้อม hash) · รหัส 6 หลักของ Safe 7 ผู้ใช้พิมพ์เองผ่าน helper แบบซ่อน ไม่อยู่ในไฟล์ใดๆ

| ข้อ | ขั้นตอน | ผล |
|---|---|---|
| UNIT-6 | unit test และ mutation | ✅ jade-ble 14 test (เดิม 12), wrapper 41 (เดิม 40), trezor-ble 24, Kotlin 10 · mutation 29 จุด (19 เดิม + 10 ใหม่) ไม่มีจุดรอด คืนไฟล์ตรงทุกไบต์ · clippy ของ lib ผ่าน (คำเตือนของ `--tests` อยู่ใน `wallet-e2e/model.rs` ที่ commit มาก่อน ไม่ใช่งานนี้) · ข้อพลาดของฉัน: `cargo fmt --all` จัดรูปไฟล์ของโมดูลอื่นสี่ไฟล์ ย้อนด้วย `git checkout` แล้ว ใช้ `-p` เท่านั้น |
| GATE-6 | 20 gates | ✅ ผ่านทั้งหมด `ffi.sh check` สะอาด ไฟล์ generate เปลี่ยนเฉพาะ doc comment |
| FW-2 | อ่าน source ของ wipe และ recovery (core v2.13.0) | ✅ wipe ล้าง storage ทั้งก้อน (ชื่อโฮสต์ device id device secret) แล้ว `erase_bonds()` · recovery ไม่คืนทั้งสองอย่าง (seed เก็บแค่กระเป๋า คีย์ credential มาจาก device secret แบบสุ่ม) · `erase_bonds` ไม่แตะ Bluetooth identity ของ Safe 7 (ข้อ 38) |
| WIPE-1 | รายการอุปกรณ์ที่จับคู่หลัง wipe และ recover | ✅ ว่าง (ผู้ใช้อ่านจากจอ) ไม่มีโฮสต์ใดจับคู่ก่อนเริ่มทดสอบ |
| WIPE-2 | Mac ที่ยังมี entry เดิมเชื่อมต่อพร้อม credential ก่อน wipe | ✅ `BondRemoved` พร้อมคำแนะนำหลัง 30 วินาที จากนั้นหลังลืมใน macOS จับคู่ใหม่ได้ ขอรหัส (credential เก่าถูกปฏิเสธ) fingerprint `73c5da0a` ตรง `info` ได้ device id ใหม่ (ค่าเก่าและใหม่อยู่ใน evidence) |
| WIPE-3 | Bluetooth identity | ✅ Mac ที่ยังมี entry เดิมเห็น id เดียว `0d740e20` ข้ามสอง session (suffix `2D9` และ `5D0`) เหมือนเมื่อวาน แปลว่า wipe ไม่เปลี่ยน identity |
| WIPE-4 | การจับคู่ครั้งแรกของแต่ละโฮสต์หลัง wipe | ✅ ชื่อปัจจุบันถูกเก็บ: `mac-wipe-a` (address ของ Mac เหมือนก่อน wipe), `mebit iPhone`, `mebit Android` (ผู้ใช้อ่านจากจอ) iPhone ไม่ได้ `BondRemoved` เห็น id ใหม่และ Pairing Request ใหม่ ไม่ทราบสถานะ entry เดิมก่อนรัน |
| WIPE-5 | ควบคุม: Forget this device บน Safe 7 แล้วจับคู่ Mac ใหม่ด้วยชื่อ `mac-wipe-c` | ✅ ชื่อในรายการยังเป็น `mac-wipe-a` (3 รายการ) Forget ไม่ล้างชื่อ wipe ล้าง |
| J-1 | Jade ที่ปฏิเสธการจับคู่บน iPhone หลังข้อ 39 | ✅ Cancel: `Timeout: timed out discovering services. If a pairing request was waiting…` 19.5 วินาที · reject บน Jade: `Disconnected: the Jade dropped the link while connecting…` 9.4 วินาที · ไม่แตะ: hint ของ timeout 20.0 วินาที (รอบห้าได้ `Device disconnected` ที่ 35.2 วินาที) |
| J-2 | Jade ที่ reject บน Mac | ✅ `the Jade dropped the link while connecting…` ที่ 11 วินาที (2026-10-01 ได้ข้อความดิบ) |
| J-3 | Jade บน POCO: การจับคู่ที่ล้มเหลวสองครั้ง | ⚠️ `connected` ก่อน แล้วราว 35 วินาทีต่อมา `Disconnected: the Jade disconnected` ธรรมดา **ไม่มี hint** เพราะ request แรกรอ bonding หลังขั้น connect จบ (ข้อ 39) การจับคู่ที่ยืนยันทัน ผ่าน (`info` หลัง 17 วินาที) |
| J-4 | เส้นทางปกติของ Jade | ✅ จับคู่ที่ยอมรับ (iPhone 16.1 วินาที), bonded, `cancel-connect:jade:500` บน iPhone และ bonded, `cancel-connect:jade:200` บน POCO ผ่าน bond ของ iPhone ยังอยู่หลัง POCO จับคู่ (Jade ถืออย่างน้อยสอง bond) |
| S-R | Safe 7 bonded ผ่าน library ที่ build ใหม่ | ✅ iPhone และ POCO ได้ `info` กับ `mtu=247` |
| ยังไม่ได้ทดสอบ | address ใหม่ล้างชื่อของโฮสต์ที่ถูก Forget (ไม่มีเครื่องสำรอง ผู้ใช้เลือกข้าม), hint ของ Jade บน Android สำหรับความล้มเหลวใน request แรก (เสนอไว้ในข้อ 39), Safe 7 ที่ถูก reject ที่หน้าจอเปรียบเทียบบน iOS, ความจุ bond ของ Jade เกินสอง, สถานะ entry เดิมของ iPhone ก่อน wipe รัน | ไม่มีเครื่องหรือไม่ได้รัน ดู `04-open-items.md` ข้อ 38 และ 39 |

**สิ่งที่ผู้ใช้ต้องรู้หรือทำต่อ:** (1) รายการ Safe 7 ตอนนี้มีสามเครื่องด้วยชื่อปัจจุบัน ชื่อใหม่จะไม่เปลี่ยนด้วย Forget อีก ต้อง wipe เท่านั้น (ฉันไม่ได้ wipe หรือ reset เครื่องเลย) (2) ต้องตัดสินใจข้อ 39: จะให้ request แรกของ Jade บนลิงก์ใหม่ได้ hint ด้วยหรือไม่ (แก้ Rust และรันทุกอย่างซ้ำ) (3) iPhone และ POCO ถือ bond ของ Jade และ Safe 7 ใหม่ Mac ถือ bond ของ Safe 7 ใหม่และไม่มี bond Jade (4) credential ของ Mac ก่อน wipe (`target/safe7-r4.cred` ฯลฯ) ใช้ไม่ได้แล้ว ไฟล์ใหม่คือ `target/safe7-r6.cred` (5) โพรบฮาร์ดแวร์ยังอยู่ รอผู้ใช้ตัดสินใจลบ

### บันทึกการทดสอบรอบเจ็ด: hint การจับคู่ Jade บน Android และการทดสอบบนสองเครื่อง (2026-10-25)

เครื่องที่ใช้ได้คือ iPhone 15, POCO X3 NFC (Android 11) และ Mac Galaxy S25 FE ไม่มีให้ใช้แล้ว การทดสอบที่ต้องใช้เครื่องนั้นถูกบล็อก ไม่นับว่าผ่านหรือไม่ผ่าน และไม่ได้ wipe Safe 7 เพื่อหลบ Build ที่ใช้ทดสอบเครื่องจริง: ไลบรารี iOS `f2ebac93b072207c`, arm64 `d753ccd1a128fae5`, APK `9cb87b56d8d2199d` (มีโพรบ) หลังลบโพรบ ไลบรารีเนทีฟเหมือนเดิมทุกไบต์และ APK เป็น `55052edaabd6114d` หลักฐานอยู่นอก repo ที่ `~/mebit-ble-evidence/2026-10-25/round7/` (`INDEX.md`, `TEST-PLAN.md`)

| การทดสอบ | ผล | ป้ายกำกับ |
|---|---|---|
| Unit test, mutation, gates | jade-ble 21 test, wrapper 42, mutation 42 จุดถูกฆ่าครบ (3 จุด wiring เฉพาะ Android host มองไม่เห็น), 20 gates ผ่านสองรอบ (มีโพรบ และหลังลบโพรบ) | ทดสอบบน host |
| POCO: reject, ไม่ตอบ, ถอดไฟระหว่างรอจับคู่ | hint ที่ 15.3, 31.2 และ 16.7 วินาทีหลัง `connected` | วัดบนเครื่องจริง รอบละหนึ่งครั้ง |
| POCO: จับคู่ที่ยอมรับแล้ว `info` สองครั้ง | ผ่านทั้งสอง ไม่มี hint | วัดบนเครื่องจริง |
| POCO: ถอดไฟ Jade ที่ bond แล้ว | ระหว่าง request: `Disconnected` ธรรมดา ก่อน request แรก: hint (ผลบวกลวง) ก่อนรัน: `NotFound` | วัดบนเครื่องจริง |
| POCO: `cancel-connect:jade:200` | ผ่าน | วัดบนเครื่องจริง |
| iPhone และ Mac: Jade บน build สุดท้าย | Cancel 20.4 วินาที, reject 8.6 วินาที, ไม่แตะ 35.2 วินาที, Mac 17 วินาที ทุกรอบได้ hint จับคู่ที่ยอมรับและ cancel-connect ผ่าน | วัดบนเครื่องจริง รอบละหนึ่งครั้ง |
| iPhone: Safe 7 ถูก reject ที่หน้าจอเปรียบเทียบ และ Cancel ที่ dialog | `PairingFailed` ที่ 14.8 และ 10.5 วินาทีหลังสแกน | วัดบนเครื่องจริง หน้าจอที่ผู้ใช้อ่านเป็นคำบอกเล่าของผู้ใช้ |
| Safe 7 จับคู่ใหม่บน iPhone และ POCO | `info` firmware 2.13.0 และ `mtu=247` ทั้งสองเครื่อง | วัดบนเครื่องจริง |
| App หลังลบโพรบ | POCO: hash ของ APK ที่ติดตั้งตรง แอปเปิดได้ ไม่มี crash iPhone: ติดตั้งและเปิดสำเร็จตามสคริปต์ verify (ตรวจ process เองไม่ได้ข้อสรุป) | วัดบนเครื่องจริง ไม่ได้ใช้ Bluetooth |
| Safe 7: address ใหม่ล้างชื่อโฮสต์ที่ถูก Forget | ไม่ได้ทดสอบ ถูกบล็อก ไม่มี Galaxy S25 FE ผู้ใช้ตัดสินใจ 2026-10-25 ว่าไม่ต้องทำอีก ปิดด้วยการตัดสินใจ ไม่ใช่ด้วยผลทดสอบ | ไม่ได้รัน |
| รันซ้ำ | POCO reject 3 ครั้ง (5.2, 6.3, 6.1 วินาที), POCO ไม่ตอบ 3 ครั้ง (31.4, 31.2, 30.9), iPhone ไม่แตะ 3 ครั้ง (19.5 วินาที) ทุกรอบมี hint มีสี่ครั้งที่เรียกไม่นับเป็นผล | วัดบนเครื่องจริง |
| master fingerprint ของ Safe 7 | `73c5da0a` ตรงกับ phrase ทดสอบ (ใช้ credential ที่บันทึกไว้ ไม่ต้องใส่รหัส) | วัดบนเครื่องจริง |
| ยังไม่ได้ทดสอบ | Android เวอร์ชันอื่นนอกจาก 11, การเขียนที่ล้มด้วย status ดิบ, ความจุ bond ของ Jade เกินสอง | ไม่ได้รัน |

**สิ่งที่ต้องรู้:** (1) hint บน Android แยก reject ออกจาก Jade ที่ไฟดับไม่ได้ ข้อความจึงเป็นเงื่อนไขและชนิด error ยังเป็น `Disconnected` หรือ `Timeout` (2) ยังไม่มีการ commit, push หรือเปิด PR (3) iPhone และ POCO ถือ bond ของ Jade และ Safe 7 ใหม่ Mac ถือ bond ของ Safe 7 แต่ไม่ถือ bond ของ Jade หลังที่ผู้ใช้ Forget

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
