# ภาพรวมสถาปัตยกรรม

## หลักการสำคัญ

- **Self-custody จริง**: แพลตฟอร์มห้ามเคลื่อนย้าย BTC ได้ฝ่ายเดียวเด็ดขาด ต้องอาศัยลายเซ็นร่วมเสมอ
- **On-chain proof**: ลูกค้าตรวจสอบเงินของตัวเองบนบล็อกเชนได้ตลอดเวลา ผ่าน vault address ที่มาจาก descriptor ที่ตรวจสอบได้
- ประเด็นกฎหมาย (ก.ล.ต./ธปท.) และโมเดล Fund/NAV ของฝั่งผู้ให้กู้ ถูกตัดออกจากการออกแบบรอบนี้ ต้องกลับมาคุยแยกต่างหาก

## Multisig 2-of-3

หัวใจของระบบคือ Bitcoin script `multi(2, A, B, C)` โดยสามฝ่ายถือคีย์คนละ 1 ใน multisig จริง ไม่มี arbitrator บุคคลที่สามลอยๆ:

- ผู้กู้ (เจ้าของ BTC) ถือคีย์ในมือถือ
- แพลตฟอร์ม ถือคีย์ใน HSM/KMS
- ผู้ให้กู้ (ตัวแทนกองทุนรวม) ถือคีย์เดียวแทนนักลงทุนทั้งหมด

**ข้อควรระวังสำคัญ**: script multisig ไม่สนใจว่า "คู่ไหน" เซ็น หรือ output ปลายทางคืออะไร ความหมายของแต่ละคู่ (คืนเงิน / liquidate / fallback) เป็นแค่ข้อตกลงทางธุรกิจเท่านั้น ระบบจึงต้องมี **policy enforcement layer** ฝั่ง backend คอยตรวจสอบว่า output ที่ขอเซ็นตรงกับเหตุผลที่อนุญาตจริงก่อนที่แพลตฟอร์มจะยอมเซ็น — จุดนี้คือความเสี่ยง bug ร้ายแรงที่สุดถ้าพลาด

## Tech Stack ที่ตกลงแล้ว

- **Backend crypto/custody logic**: Rust ล้วน — ไม่ใช้ bdk-rn/bdk-dart wrapper เพราะยังอยู่ขั้น integration-testing เท่านั้น (เช็คแล้ว ณ ก.ค. 2026)
- **Backend business logic เดิม**: NestJS (Loan Service) เรียกใช้ custody-service ผ่าน API
- **Bitcoin data provider**: เริ่มจาก public Esplora API ก่อนช่วง MVP แล้วค่อย self-host full node + Electrs/Fulcrum ทีหลัง — ออกแบบ Monitor Service ให้ swap provider ได้ง่ายตั้งแต่แรก
- **Mobile app**: framework UI (React Native vs Flutter) กลายเป็นเรื่องรอง เพราะ core logic อยู่ใน Rust module ที่เรียกผ่าน UniFFI ทั้งคู่ — เลือกจาก preference อื่นได้เลย
- **Mobile app = full hot wallet**: จากดีไซน์ "mebit" ที่อัปโหลดมา (ดูรายละเอียดเต็มใน `06-mobile-app-design-mebit.md`) แอปฝั่งผู้กู้ไม่ใช่แค่หน้าจอเซ็น PSBT ไม่กี่หน้าอีกต่อไป แต่เป็น Bitcoin hot wallet เต็มรูปแบบ 12 หน้าจอ (onboarding/seed backup, Face ID, home, receive, borrow, loan dashboard, repay, success, activity, portfolio, settings) ที่ต้องดูแล BTC ทั้งส่วนที่ "free" (ยังไม่ pledge ค้ำ loan ใด — ต้องมี wallet logic แบบ single-sig เต็มรูปแบบ: address, UTXO, fee estimation) และส่วนที่ pledge แล้ว (เข้าสู่ multisig vault ผ่าน vault-core ตามเดิม)
- **"เต็มรูปแบบ" หมายถึงต้องทำทุกอย่างที่ Bitcoin wallet จริงทำได้**: สร้างธุรกรรม, ส่ง BTC, รับ BTC, เชื่อมต่อโหนด (Electrum/Esplora client เพื่อ sync balance/fee/history โดยไม่ต้องพึ่ง backend เสมอ — สอดคล้องหลัก self-custody), และสร้าง PSBT แบบ watch-only (อุปกรณ์ที่มีแค่ public descriptor สร้าง PSBT ให้อุปกรณ์ที่มี private key เซ็นแยก) — ทั้งหมดนี้ `bdk` (Bitcoin Dev Kit) เวอร์ชัน Rust core รองรับแบบ built-in อยู่แล้ว แนะนำใช้เป็นฐานของเลเยอร์ wallet ทั่วไป แทนการเขียน wallet logic เองใหม่ทั้งหมด

## โครงสร้าง Workspace ที่เสนอ

```
vault-workspace/
├── vault-core/          # descriptor, policy, PSBT logic — ที่เดียวที่มี business logic
├── custody-service/      # gRPC/REST service, NestJS Loan Service เรียกใช้
├── mobile-signer-ffi/     # UniFFI binding -> Kotlin/Swift: hot wallet (single-sig, BTC ที่ยังไม่ pledge) + multisig vault signer (BTC ที่ pledge แล้ว)
└── lender-signer-cli/     # เครื่องมือฝั่งตัวแทนกองทุนเซ็น PSBT offline
```

## Key Derivation

- ใช้ BIP-48 (`m/48'/0'/0'/2'` สำหรับ P2WSH multisig cosigner)
- ลูกค้า import/generate seed **ครั้งเดียว** ตอน onboarding แล้วส่ง account xpub ให้ backend เก็บไว้
- ทุก loan ใหม่ backend derive child index ถัดไปเอง ไม่ต้องขอ key จากลูกค้าซ้ำอีก
- `loan_index` เป็น counter ต่อลูกค้า เก็บใน DB ต้องล็อกด้วย unique constraint กันปัญหา race condition

## DB Schema (ร่าง)

- ตาราง `customers`: `account_xpub`, `next_loan_index`
- ตาราง `loans`: `customer_id`, `loan_index`, `vault_descriptor`, `vault_address`, `status` (state machine: `pending_deposit → active → margin_call → liquidating → closed`)
- `UNIQUE (customer_id, loan_index)` กัน address ชนกัน
