# โครงการ Capstone: ระบบปล่อยกู้ค้ำด้วย Bitcoin แบบ Self-Custody

**เอกสารสำหรับเสนอเป็นโจทย์ capstone project ระดับปริญญาตรี**
**ขอบเขต**: 1 ทีม ครอบคลุมทั้งระบบ (end-to-end)
**ทีม**: นักศึกษาวิศวกรรมคอมพิวเตอร์ 5 คน ระยะเวลา 2 เทอม

---

## 1. ทำไมโจทย์นี้ถึงน่าสนใจ

โจทย์นี้ไม่ใช่ CRUD app ทั่วไป แต่เป็นระบบที่ผสม cryptography, distributed systems, และ real-world financial engineering เข้าด้วยกัน มีลักษณะที่ capstone ทั่วไปหายาก:

- **ความท้าทายทางเทคนิคจริง**: ต้องออกแบบ Bitcoin multisig script, PSBT (Partially Signed Bitcoin Transaction), BIP-32/BIP-48 key derivation, และ policy enforcement layer ที่ป้องกัน bug ระดับ "เงินหาย" — ไม่ใช่โจทย์ตกแต่งที่ mock ได้ง่าย
- **นำไปใช้งานจริง**: ไม่ใช่โปรเจกต์เก็บใน GitHub เฉยๆ แต่เป็น component จริงของบริษัทที่กำลังจะเปิดให้บริการ นักศึกษาที่ทำได้ดีมีโอกาสสานต่อเป็นงานหรือฝึกงานจริง
- **Rust + Bitcoin**: ทักษะที่หายากในตลาดไทย ผู้เรียนได้ทั้ง systems programming ระดับ production และความเข้าใจ Bitcoin protocol ที่ลึกกว่าระดับ "เทรดเหรียญ"
- **ขอบเขตชัดเจน จับต้องได้ใน 1 ปีการศึกษา**: มี milestone ที่ทดสอบได้จริงบน testnet ทุกขั้น ไม่ใช่โจทย์ปลายเปิดที่วัดผลยาก

**สิ่งที่ตัดออกจากขอบเขต capstone นี้โดยตั้งใจ**: ประเด็นกฎหมาย (ก.ล.ต./ธปท.) และ Fund/NAV ledger ของฝั่งผู้ให้กู้ ไม่อยู่ในความรับผิดชอบของทีม เพราะเป็นเรื่องธุรกิจ/กฎหมายที่บริษัทดูแลเอง ทีม capstone โฟกัสที่ระบบ custody และ on-chain logic เท่านั้น

---

## 2. โจทย์ในภาพรวม

ออกแบบและพัฒนาระบบที่ให้ลูกค้านำ Bitcoin มาวางเป็นหลักประกันเงินกู้ โดยที่:

1. ไม่มีฝ่ายใดฝ่ายหนึ่งเคลื่อนย้าย BTC ได้เพียงลำพัง (ต้องใช้ 2-of-3 multisig เสมอ)
2. ลูกค้าตรวจสอบเงินของตัวเองบนบล็อกเชนได้ตลอดเวลา โดยไม่ต้องเชื่อใจแพลตฟอร์ม
3. ระบบต้องรองรับสถานการณ์ liquidation อัตโนมัติเมื่อราคาตกและ LTV เกินเกณฑ์ ภายในเวลาที่ปลอดภัยพอ

สามฝ่ายที่เกี่ยวข้อง: ผู้กู้ (ถือคีย์บนมือถือ), แพลตฟอร์ม (ถือคีย์ใน HSM/KMS), ผู้ให้กู้/ตัวแทนกองทุน (ถือคีย์แยกต่างหาก) — รายละเอียดบทบาทดูใน `02-roles-and-responsibilities.md` และโฟลว์แบบละเอียดใน `03-flows.md` ของชุดเอกสารนี้

---

## 3. สถาปัตยกรรมที่ทีมต้องส่งมอบ

```
vault-workspace/
├── vault-core/           # descriptor, policy, PSBT logic (Rust) — หัวใจของระบบ
├── custody-service/      # gRPC/REST service + state machine ของ signing request
├── mobile-signer-ffi/    # UniFFI binding -> Kotlin/Swift สำหรับแอปฝั่งผู้กู้
├── lender-signer-cli/    # CLI เซ็น PSBT แบบ offline ฝั่งตัวแทนกองทุน
└── monitor-service/      # ตรวจ LTV, trigger margin call / liquidation
```

### 3.1 vault-core (Rust) — โมดูลแกนหลัก

หน้าที่:
- สร้าง output descriptor สำหรับ P2WSH multisig 2-of-3 จาก pubkey ของสามฝ่าย
- Derive child pubkey ตาม BIP-48 (`m/48'/0'/0'/2'`) จาก account xpub
- สร้างและตรวจสอบ PSBT (Partially Signed Bitcoin Transaction)
- **Policy engine**: ตรวจสอบว่า output ของ PSBT ที่ขอเซ็นตรงกับเหตุผลที่อนุญาต (คืนหลักประกัน / liquidation / fallback) ก่อนอนุญาตให้เซ็น — นี่คือจุดที่ต้องทดสอบละเอียดที่สุด เพราะ multisig script เองไม่รู้ความหมายทางธุรกิจของแต่ละคู่เซ็น

เทคโนโลยี: Rust, `rust-bitcoin` / `miniscript` crate, unit test ครอบคลุม policy edge cases

โจทย์ที่ท้าทาย: ออกแบบ policy DSL หรือ struct ที่ตรวจสอบ PSBT ได้ครบทุกกรณี (จำนวนเงิน, output address, จำนวนผู้เซ็น) โดยไม่มีช่องโหว่ให้เซ็นผิดวัตถุประสงค์

### 3.2 custody-service — บริการฝั่งแพลตฟอร์ม

หน้าที่:
- expose gRPC/REST API ให้ NestJS Loan Service เดิมเรียกใช้
- state machine ของ signing request: track ว่า PSBT แต่ละใบรออยู่ขั้นไหน (created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed)
- เก็บ mapping ระหว่าง loan และ vault descriptor
- เชื่อมกับ HSM/KMS (หรือ mock สำหรับ capstone) สำหรับคีย์ฝั่งแพลตฟอร์ม

เทคโนโลยี: Rust (tonic สำหรับ gRPC หรือ axum สำหรับ REST), PostgreSQL

### 3.3 mobile-signer-ffi — "mebit" Bitcoin hot wallet เต็มรูปแบบ

อัปเดตขอบเขต: อิงตามไฟล์ design ที่ผู้ใช้ส่งมา (สรุปเต็มใน `06-mobile-app-design-mebit.md`) แอปนี้ไม่ใช่แค่หน้าจอเซ็น PSBT อีกต่อไป แต่เป็น Bitcoin hot wallet ที่มีครบ 12 หน้าจอ: splash, seed backup, Face ID, home, receive, borrow, loan dashboard, repay, success, activity, portfolio, settings

หน้าที่:
- **เลเยอร์ hot wallet (single-sig)**: ดูแล BTC ส่วนที่ยังไม่ pledge ค้ำ loan ใด ต้องทำทุกอย่างที่ Bitcoin wallet จริงทำได้ครบ ไม่ใช่แค่โชว์ยอด: สร้างธุรกรรม (coin selection + fee), ส่ง BTC, รับ BTC (address generation), เชื่อมต่อโหนด (Electrum/Esplora client สำหรับ sync UTXO/fee/history), และสร้าง PSBT แบบ watch-only (ให้อุปกรณ์ที่ถือ private key จริงเซ็นแยกได้ — pattern เดียวกับ `lender-signer-cli`) — แนะนำใช้ `bdk` Rust core เป็นฐาน เพราะรองรับทุกข้อนี้แบบ built-in อยู่แล้ว
- **เลเยอร์ vault signer (multisig)**: ห่อ logic การ derive key และเซ็น PSBT จาก vault-core ให้เรียกจาก Kotlin/Swift ได้ผ่าน UniFFI สำหรับ BTC ส่วนที่ pledge เข้า loan แล้ว
- **Portfolio**: รวมยอด BTC ข้ามหลาย vault address (หลาย loan พร้อมกัน แยกอิสระไม่ cross-collateralise) + ส่วน free ที่ยังไม่ผูก loan ใด เป็นยอดเดียวบนหน้า Home
- **Activity**: merge on-chain BTC movement (จาก Monitor Service) กับ loan events (จาก custody-service) เป็น ledger เดียว พร้อมแสดงผลกระทบต่อ LTV ต่อแถว
- **Risk visualization บน Loan Dashboard**: แจ้งเตือนที่ LTV 65% และ 72%, บังคับขายบางส่วนที่ 80% (ตัวเลขจากดีไซน์ ทีมสามารถ derive ราคาบังคับขายจากตัวเลขนี้ได้ ไม่ต้อง hardcode)
- **Borrow**: ให้เลือกได้ทั้ง preset ระดับความเสี่ยง (ระมัดระวัง 25% / สมดุล 50% / สูงสุด 70% LTV) และกรอกจำนวนเอง

เทคโนโลยี: UniFFI, Kotlin/Swift, React Native หรือ Flutter, `bdk` (Bitcoin Dev Kit) Rust core สำหรับเลเยอร์ wallet ทั่วไป

**หมายเหตุขอบเขต**: 12 หน้าจอเต็มรูปแบบถือว่าใหญ่กว่าที่ทีมเดียว (คนที่ 4) จะทำไหวคนเดียวใน 2 เทอม แนะนำให้กำหนด "MVP screens" ที่ต้องทำให้จบก่อน (onboarding, home, receive, borrow, loan dashboard, repay, success — ตรงกับ demo กลางเทอม/ท้ายเทอม) ส่วน activity, portfolio, settings เป็น stretch goal ที่ทำได้ถ้าเวลาเหลือ หรือขอความช่วยเหลือจากคนที่ 3/5 ในเทอม 2 ตอนโมดูลหลักของแต่ละคนนิ่งแล้ว (ดู 3.6)

### 3.4 lender-signer-cli — เครื่องมือฝั่งผู้ให้กู้

หน้าที่:
- CLI สำหรับดึง PSBT ที่รอเซ็น ตรวจสอบเนื้อหา แล้วเซ็นแบบ offline (air-gapped workflow)
- ใช้ vault-core เดียวกันเป็น dependency

เทคโนโลยี: Rust CLI (clap)

### 3.5 monitor-service — ตรวจ LTV และ trigger liquidation

หน้าที่:
- ดึงราคาผ่าน public Esplora API (MVP) หรือ price oracle
- คำนวณ LTV ของทุก loan ที่ active ต่อเนื่อง
- Trigger margin call / liquidation ตาม threshold — ดีไซน์ "mebit" เสนอค่าที่เป็นรูปธรรมกว่าตัวอย่างเดิม (50/70/80%) คือ **แจ้งเตือนที่ 65% และ 72%, บังคับขายที่ 80%** ทีมควรใช้ค่านี้เป็นฐาน แต่ยังต้องรอ sign-off จากทีมความเสี่ยง/ธุรกิจอย่างเป็นทางการ
- ออกแบบให้ swap data provider ได้ง่าย (เช่น เปลี่ยนจาก public API เป็น self-hosted Electrs ภายหลัง)

เทคโนโลยี: Rust, cron/scheduler, HTTP client

### 3.6 โครงสร้างทีมและการแบ่งงาน (5 คน)

5 โมดูลพอดีกับทีม 5 คน แบ่งเป็นเจ้าของโมดูลคนละ 1 ส่วน แต่ทุกคนต้องอ่านโค้ด `vault-core` ให้เข้าใจ เพราะทุกโมดูลพึ่งพามันเป็น dependency หลัก:

| คน | โมดูลหลัก | หมายเหตุ |
|---|---|---|
| คนที่ 1 (หัวหน้าทีมเทคนิค) | vault-core: descriptor + key derivation | ควรเป็นคนที่แข็ง Rust ที่สุดในทีม เพราะโมดูลนี้เป็น dependency ของทุกคน ต้องเสร็จก่อนคนอื่นเริ่มงานจริงได้ |
| คนที่ 2 | vault-core: PSBT + policy engine | ทำงานคู่กับคนที่ 1 ในโมดูลเดียวกัน เพราะ policy engine เป็นจุดเสี่ยงสูงสุด ควรมี 2 คนช่วยกันรีวิวโค้ดกันเอง |
| คนที่ 3 | custody-service | ต้องรอ interface เบื้องต้นของ vault-core (สัปดาห์ 3-4) ถึงเริ่มต่อได้เต็มที่ ระหว่างนั้นออกแบบ API spec และ state machine ล่วงหน้าได้ |
| คนที่ 4 | mobile-signer-ffi (mebit wallet 12 หน้าจอ) | ขอบเขตใหญ่ที่สุดในทีมหลังนับรวม hot wallet เต็มรูปแบบ — เริ่มออกแบบ UI/UX และ mock UniFFI interface ได้ตั้งแต่เทอม 1 โดยไม่ต้องรอ vault-core เสร็จสมบูรณ์ ควรโฟกัส MVP screens ก่อน (ดู 3.3) และรับความช่วยเหลือจากคนที่ 3/5 ในเทอม 2 |
| คนที่ 5 | lender-signer-cli + monitor-service | สองโมดูลนี้เล็กกว่าโมดูลอื่น จึงมอบให้คนเดียวดูแลทั้งคู่ได้ เริ่ม monitor-service (ดึงราคา, คำนวณ LTV) ได้ตั้งแต่ต้นเทอม 1 เพราะไม่ต้องพึ่ง vault-core มาก |

**หลักการแบ่งงาน**:
- สัปดาห์ 1-2 (ปูพื้นฐาน) ทำร่วมกันทั้งทีม 5 คน — อ่าน Bitcoin script/PSBT/BIP-32 ด้วยกัน ป้องกันไม่ให้มีใครตามไม่ทันตอนโค้ดเริ่มแยกกันทำ
- vault-core (คนที่ 1-2) เป็น critical path ของทั้งโปรเจกต์ ต้องมี milestone ภายในย่อยชัดเจน และรายงานความคืบหน้าบ่อยกว่าโมดูลอื่น เพราะถ้าช้า จะไปกระทบ custody-service และ mobile-signer-ffi ที่ต้องพึ่ง interface ของมัน
- ทุกสัปดาห์ควรมี stand-up สั้นๆ ร่วมกันทั้งทีม เพราะโมดูลเชื่อมกันผ่าน interface ที่ต้องตกลงร่วมกัน (เช่น รูปแบบ PSBT, error type)
- เทอม 2 เมื่อโมดูลหลักของแต่ละคนเริ่มนิ่ง ให้สลับกันช่วยกัน integration test แบบ end-to-end แทนที่จะแยกกันทำจนจบ

---

## 4. Timeline ที่เสนอ (ภาคการศึกษา ~ 2 เทอม, ทีม 5 คน)

**เทอม 1 — ปูพื้นฐานและ core logic**

| ช่วง | คนที่ 1-2 (vault-core) | คนที่ 3 (custody-service) | คนที่ 4 (mobile-signer-ffi) | คนที่ 5 (lender-cli + monitor) |
|---|---|---|---|---|
| สัปดาห์ 1-2 | ศึกษา Bitcoin script/PSBT/BIP-32-48 ร่วมกันทั้งทีม + เขียน design doc ของ policy engine | (ร่วมทั้งทีม) | (ร่วมทั้งทีม) | (ร่วมทั้งทีม) |
| สัปดาห์ 3-6 | descriptor generation + key derivation + unit test | ออกแบบ API spec + state machine (ยังไม่ต่อ vault-core จริง) | ออกแบบหน้าจอตาม design mebit ทั้ง 12 หน้า (UI/UX) + mock UniFFI interface + เริ่ม hot wallet layer (address/UTXO ด้วย `bdk`) | monitor-service: ดึงราคาผ่าน Esplora API, คำนวณ LTV เบื้องต้น |
| สัปดาห์ 7-10 | PSBT construction/parsing + policy engine (จุดยากที่สุด) | ต่อ custody-service เข้ากับ vault-core interface จริง | สร้าง MVP screens: splash, seed backup, Face ID, home, receive + เริ่มต่อ UniFFI binding จริงกับ vault-core | lender-signer-cli: ดึง PSBT + เซ็น offline เบื้องต้น |
| สัปดาห์ 11-14 | ช่วย custody-service integration test + แก้ bug จาก policy engine | state machine เต็มรูปแบบ, integration test กับ vault-core บน testnet | MVP screens ต่อ: borrow (พร้อม risk preset 25/50/70%) + loan dashboard + เปิด loan จริงบน testnet | monitor-service: trigger margin call เบื้องต้น |
| สัปดาห์ 15 | Demo กลางเทอม (ทั้งทีมร่วมสาธิต): เปิด loan → คืนหลักประกัน แบบ end-to-end บน testnet | | | |

**เทอม 2 — ระบบครบวงจรและ liquidation**

| ช่วง | คนที่ 1-2 (vault-core) | คนที่ 3 (custody-service) | คนที่ 4 (mobile-signer-ffi) | คนที่ 5 (lender-cli + monitor) |
|---|---|---|---|---|
| สัปดาห์ 1-4 | รองรับ PSBT ของ liquidation flow (partial spend + change) | ต่อ state machine รองรับ liquidation state | repay + success (ปิด MVP flow ครบ 9 หน้าตาม tappable prototype) + verification flow (challenge-response) | monitor-service: threshold margin call/liquidate เต็มรูปแบบ |
| สัปดาห์ 5-8 | รีวิว policy engine ร่วมกับคนที่ 5 สำหรับกรณี liquidation | ต่อ custody-service กับ monitor-service (trigger liquidation) | activity (merge on-chain + loan events) + margin call/liquidation status บน UI, portfolio ถ้าเวลาเหลือ (stretch) | lender-signer-cli: เซ็น PSBT liquidation แบบ offline เต็มรูปแบบ |
| สัปดาห์ 9-12 | Liquidation flow แบบเต็ม (คำนวณจำนวนขายบางส่วน, รวมลายเซ็น, broadcast) — ทำร่วมกันทั้งทีม | | | |
| สัปดาห์ 13-14 | Security review: adversarial testing ต่อ policy engine ร่วมกันทั้งทีม (พยายาม "แฮ็ก" ให้เซ็นผิดวัตถุประสงค์) | | | |
| สัปดาห์ 15 | Demo จบโครงการ (ทั้งทีมร่วมสาธิต): สาธิตทั้ง 3 โฟลว์ (repayment, liquidation, fallback) บน testnet ต่อหน้าอาจารย์และทีมบริษัท | | | |

---

## 5. เกณฑ์ความสำเร็จ (Definition of Done)

1. รันบน Bitcoin testnet ได้จริง ไม่ใช่ mock — เปิด loan, ฝาก BTC, เห็น vault address ยืนยันบนเชนได้
2. Policy engine ปฏิเสธ PSBT ที่ output ไม่ตรงกับเหตุผลที่อนุญาตได้ 100% ในชุดทดสอบ adversarial ที่ทีมหรือบริษัทออกแบบ
3. Verification flow (challenge-response ตอนเปิด loan) ทำงานถูกต้อง ตรวจจับ pubkey ที่ derive ผิดได้
4. Liquidation flow คำนวณจำนวน BTC ที่ต้องขายได้แม่นยำ (พอดีหนี้+buffer เท่านั้น ไม่ขายเกิน)
5. แอปมือถือครบ MVP screens ตามดีไซน์ mebit อย่างน้อย: onboarding/seed backup, Face ID, home, receive, borrow (พร้อม risk preset), loan dashboard, repay, success — ทำงานจบ flow บน testnet จริง (ไม่ใช่ mockup นิ่งๆ)
6. แอปมือถือส่ง/รับ/สร้างธุรกรรม BTC ทั่วไปได้จริงบน testnet ผ่าน node connectivity ของตัวเอง (ไม่ผ่าน backend) และสร้าง PSBT แบบ watch-only ได้อย่างน้อย 1 เคสสาธิต
7. เอกสารประกอบ: architecture doc, API spec, threat model สั้นๆ ของ policy engine

---

## 6. ทักษะที่นักศึกษาจะได้

- Rust ระดับ production (ไม่ใช่แค่ syntax): ownership, error handling, async, FFI
- Bitcoin protocol เชิงลึก: UTXO model, script, PSBT, multisig, BIP-32/48 — ความรู้ที่แยกจาก "เทรดคริปโต" อย่างสิ้นเชิง
- Distributed systems: state machine design, idempotency, race condition handling (เช่น `loan_index` unique constraint)
- Security mindset: การคิดแบบ adversarial ต่อระบบที่ตัวเองสร้าง ซึ่งเป็นทักษะที่หายากและเป็นที่ต้องการสูงในสาย fintech/security

## 7. เส้นทางหลังจบโครงการ

ทีมที่ทำโจทย์นี้สำเร็จมีโอกาส:
- สานต่อเป็นนักพัฒนา/ฝึกงานจริงกับบริษัท เพื่อพัฒนา custody-service เข้าสู่ production
- นำผลงานไปใช้เป็นพอร์ตสมัครงานสาย Rust/blockchain/security ซึ่งเป็นตลาดที่แข่งขันน้อยแต่ค่าตอบแทนสูงในไทย
- ต่อยอดเป็นหัวข้อวิจัย/ตีพิมพ์ในหัวข้อ policy-constrained multisig custody หากสนใจสายวิชาการ

---

## 8. สิ่งที่บริษัทจะสนับสนุนทีม

- Mentor ที่มีประสบการณ์ Rust และเคยรัน Lightning/LNbits จริง ให้คำปรึกษารายสัปดาห์
- Sandbox testnet environment และ CI พร้อมใช้
- Code review และ security review ร่วมกับทีมบริษัทก่อน demo จบโครงการ
- โอกาสฝึกงาน/งานจริงสำหรับสมาชิกที่ผลงานโดดเด่น
