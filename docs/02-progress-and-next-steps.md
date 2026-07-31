# สถานะความคืบหน้าและสิ่งที่ต้องทำต่อ

> อัปเดตล่าสุด: 2026-07-31 — เอกสารนี้เป็น living document ทีมควรอัปเดตทุกครั้งที่มี milestone ใหม่ อย่าปล่อยให้ล้าสมัยจนไม่ตรงกับโค้ดจริง (ตรวจสอบกับ `git log`/โค้ดจริงก่อนเชื่อว่าสถานะยังถูกต้อง)

ภาพรวม ณ ตอนนี้: ทุกโมดูลมี **skeleton ที่ compile ผ่าน มี test ผ่าน และรันได้จริง** แต่ยังเป็นระบบจำลอง (mock) แทบทั้งหมด — ยังไม่มีการเชื่อมต่อ Bitcoin จริง, testnet จริง, หรือการเชื่อมต่อระหว่าง service แบบครบวงจร นี่คือจุดเริ่มต้นสำหรับทีม capstone ให้เข้ามาต่อยอดตาม timeline ใน [`00-capstone-brief.md`](00-capstone-brief.md) §4

---

## 1. vault-core (คนที่ 1-2) — จุดวิกฤตของโปรเจกต์

**สถานะปัจจุบัน**
- มี skeleton ครบ 4 ส่วน (`descriptor`, `derivation`, `psbt`, `policy`) เขียนด้วย Rust ล้วน **ยังไม่ได้ใช้ `bitcoin`/`miniscript` crate จริง** — ใช้ String/struct จำลองแทน pubkey, descriptor, PSBT
- `policy::PolicyEngine` เป็นส่วนที่ "จริง" ที่สุดในตอนนี้: บังคับ default-deny, ตรวจ address/amount/loan ตรงกันเป๊ะ มี unit test แบบ adversarial ผ่านครบ (ปฏิเสธ output ผิด, จำนวนผิด, loan ผิด, และเคสที่มี output ถูกต้องปนกับ output แอบขโมยมูลค่า)
- รวม 11 test ผ่านหมด, compile สะอาด

**สิ่งที่ต้องทำทั้งหมด**
- [ ] ทีมอ่าน Bitcoin fundamentals (BIP-32/48, PSBT, multisig script) ให้จบก่อน — สัปดาห์ 1-2 (`.claude/skills/bitcoin-fundamentals/SKILL.md`)
- [ ] เปิดใช้ `bitcoin`/`miniscript` crate จริง (คอมเมนต์ไว้ใน `Cargo.toml` แล้ว)
- [ ] `descriptor`: สร้าง P2WSH 2-of-3 multisig descriptor จริงจาก pubkey จริงของ 3 ฝ่าย
- [ ] `derivation`: derive child pubkey จริงตาม BIP-48 (`m/48'/0'/0'/2'`) จาก account xpub
- [ ] `psbt`: สร้าง/parse PSBT จริงด้วย `bitcoin::Psbt`
- [ ] `policy`: ย้าย logic เดิม (ที่ทดสอบผ่านแล้ว) มาทำงานกับ PSBT จริง — คงหลักการ default-deny ไว้เหมือนเดิม
- [ ] เพิ่ม adversarial test เพิ่มสำหรับ PSBT จริง: fee manipulation, replay, partial-spend สำหรับ liquidation
- [ ] เทอม 2: รองรับ liquidation flow แบบเต็ม (ขายบางส่วน + คำนวณ change)
- [ ] เทอม 2 สัปดาห์ 13-14: security review แบบ adversarial ร่วมกันทั้งทีม

---

## 2. custody-service (คนที่ 3)

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
- `rust/` มี skeleton จริงแล้ว: 3 ฟังก์ชัน (`derive_borrower_pubkey`, `compute_vault_address`, `sign_psbt`) ต่อกับ mock type ของ `vault-core` โดยตรง มี 5 test ผ่าน — **ยังไม่ได้ใช้ `uniffi` crate/macro จริง** (ตั้งใจรอจนกว่า `vault-core` จะนิ่งก่อน ตามหลักการเดียวกับที่ไม่ implement Bitcoin จริงใน vault-core ตอนนี้)
- `app/` เป็น Expo React Native app ที่ **รันได้จริง** (เว็บ/iOS/Android ผ่าน simulator) ครบ 12 หน้าจอตาม design ล่าสุด รองรับโมเดลหลายสัญญาเงินกู้พร้อมกัน (multi-loan) แล้ว
- ข้อมูลทั้งหมดใน `app/` ยังเป็น **mock ล้วน** (`mockVault.ts`, TypeScript) — ยังไม่ได้เชื่อมกับ `rust/` เลย (คนละภาษา ยังไม่มี native module bridge)

**สิ่งที่ต้องทำทั้งหมด**
- [ ] เมื่อ `vault-core` นิ่งแล้ว: เพิ่ม `uniffi` dependency จริง, ใส่ `#[uniffi::export]` ให้ 3 ฟังก์ชันที่มีอยู่ (หรือฟังก์ชันใหม่ตามอินเทอร์เฟซจริง), generate binding ไป Kotlin/Swift
- [ ] เชื่อม UI เข้ากับ native binding จริงแทน `mockVault.ts` ทีละฟังก์ชัน
- [ ] ทำ verification/challenge-response flow ตอนเปิด loan (ตรวจจับ pubkey derive ผิด) — **ยังไม่มีเลยตอนนี้**
- [ ] เชื่อม UI margin-call/liquidation กับข้อมูลจริงจาก `monitor-service`/`custody-service` แทน mock
- [ ] เชื่อม Receive/Borrow flow กับ testnet จริง
- [ ] (ถ้าจำเป็น) เปลี่ยนจาก state-switch ง่ายๆ ใน `App.tsx` เป็น react-navigation เมื่อโครงสร้างซับซ้อนขึ้น

---

## 4. lender-signer-cli (คนที่ 5)

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

## 5. monitor-service (คนที่ 5)

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

- **Bitcoin testnet จริง** — ทุกอย่างตอนนี้เป็น mock/in-memory ล้วน ยังไม่มีการทดสอบบน testnet จริงแม้แต่ครั้งเดียว (เป็นเกณฑ์ข้อ 1 ใน Definition of Done ของ [`00-capstone-brief.md`](00-capstone-brief.md) §5)
- **การเชื่อมต่อระหว่าง service จริง** — custody-service ↔ monitor-service ↔ lender-signer-cli ↔ mobile app ยังไม่มีเส้นเชื่อมไหนที่เป็นของจริงเลยนอกจาก lender-signer-cli fetch จาก custody-service ได้
- **Auth/security ระหว่าง service** — ยังไม่มีเลย
- **เอกสาร**: API spec แบบละเอียด, threat model ของ policy engine (ข้อ 5 ใน Definition of Done) ยังไม่ได้เขียน
- **CI** — ยังไม่ได้ตั้ง
