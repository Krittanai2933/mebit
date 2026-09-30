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

**สิ่งที่ต้องทำทั้งหมด**
- [ ] ทีมอ่าน Bitcoin fundamentals (BIP-32/48, PSBT, multisig script) ให้จบก่อน — สัปดาห์ 1-2 (`.claude/skills/bitcoin-fundamentals/SKILL.md`)
- [x] เปิดใช้ `bitcoin`/`miniscript` crate จริงใน `Cargo.toml` (2026-08-25 — ยังใช้จริงแค่ใน `keys`)
- [ ] `keys`: เพิ่มฟังก์ชัน derive จริง (ตอนนี้มีแค่ type) + ตัดสินใจว่า `derivation_path` ควรเป็น `String` หรือ `bitcoin::bip32::DerivationPath` — **รอ @munich** (เอกสาร `08-multisig-wallet-spec.md` ยังไม่อยู่ใน repo)
- [x] `hw`: Jade client ผ่าน **BLE** (ไม่ใช่ QR — Jade Core ไม่มีกล้อง) — No.4, เจ้าของ @phoovich (2026-09-30)
- [ ] `hw`: Trezor Safe 7 (BLE) client — No.5
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
