# โฟลว์การทำงาน

## 1. Onboarding (ครั้งเดียวต่อลูกค้า)

1. ลูกค้า import หรือ generate seed ในแอปมือถือ (private key ไม่ออกจากเครื่อง)
2. แอป derive account xpub ตาม BIP-48 (`m/48'/0'/0'/2'`)
3. แอปส่ง account xpub ให้ backend เก็บไว้ในตาราง `customers` พร้อม `next_loan_index = 0`
4. จบขั้นตอนนี้แล้วลูกค้าไม่ต้องส่ง key ซ้ำอีกในทุก loan ถัดไป

## 2. เปิด Loan ใหม่ (Open Loan)

1. แอปฝั่งผู้กู้ derive child pubkey จาก xpub ของตัวเอง โดยใช้ index ถัดไปที่ backend บอก แล้วส่งมาพร้อม open-loan request
2. Backend derive ค่าที่ควรจะเป็นจาก stored xpub + index ที่เก็บไว้ แล้วเทียบกับ pubkey ที่แอปส่งมา — ไม่ตรงกันให้ reject ทันที
3. (เสริมความปลอดภัย) Backend ส่ง nonce กลับให้แอปเซ็นพิสูจน์ว่าถือ private key จริง ก่อนยืนยันสร้าง vault
4. เมื่อผ่านการตรวจสอบ backend สร้าง `vault_descriptor` และ `vault_address` จาก pubkey ของทั้งสามฝ่าย (ผู้กู้ / แพลตฟอร์ม / ผู้ให้กู้) บันทึกแถวใหม่ในตาราง `loans` ด้วย `UNIQUE (customer_id, loan_index)` กัน address ชนกัน และตั้งสถานะเป็น `pending_deposit`
5. ลูกค้าโอน BTC เข้า vault address — เมื่อ Monitor Service ตรวจพบ deposit ยืนยันบนเชนแล้ว เปลี่ยนสถานะเป็น `active`
6. ลูกค้าตรวจสอบ vault address ของตัวเองบนบล็อกเชนได้ตลอดเวลาผ่านเครื่องมือ explorer ทั่วไป (on-chain proof)

## 3. คืนหลักประกัน (Loan Repayment / Closing)

1. ผู้กู้ชำระหนี้ครบตามเงื่อนไข
2. Backend สร้าง PSBT คืน BTC ทั้งหมดกลับผู้กู้
3. Policy enforcement layer ตรวจสอบว่า output ของ PSBT นี้ตรงกับเหตุผล "คืนหลักประกัน" จริง (ไม่ใช่ liquidation หรืออื่นๆ) ก่อนอนุญาตให้แพลตฟอร์มเซ็น
4. ผู้กู้ + แพลตฟอร์ม เซ็นร่วมกัน (2-of-3) → broadcast ธุรกรรม
5. สถานะ loan เปลี่ยนเป็น `closed`

## 4. Liquidation Flow

1. Monitor Service ตรวจ LTV ของแต่ละ loan เทียบกับ threshold ต่อเนื่อง (ค่าที่เสนอ: init 50% / margin call 65-72% / liquidate 80% — ดู [`04-open-items.md`](04-open-items.md) ข้อ 5 สำหรับสถานะการ sign-off)
2. เมื่อเข้าเกณฑ์ margin call: สถานะเปลี่ยนเป็น `margin_call` แจ้งเตือนผู้กู้ให้เติมหลักประกันหรือชำระหนี้บางส่วน
3. เมื่อเข้าเกณฑ์ liquidate: สถานะเปลี่ยนเป็น `liquidating`
   - คำนวณจำนวน BTC ที่ต้องขายให้พอดีกับหนี้ + buffer เท่านั้น (ไม่ขายทั้งก้อน) ส่วนเกินคืนผู้กู้ในธุรกรรมเดียวกัน
   - Backend สร้าง PSBT ที่ policy enforcement layer ตรวจสอบว่า output ตรงกับเหตุผล "liquidation" จริง
   - รวมลายเซ็นแพลตฟอร์ม + ผู้ให้กู้ (ผู้ให้กู้เซ็นผ่าน `lender-signer-cli` แบบ offline)
   - **จุดเสี่ยง operational latency**: ถ้าตัวแทนกองทุนเซ็นช้าตอนราคาร่วงเร็ว มีข้อเสนอ (ยังไม่ตัดสินใจ) ให้ทำ policy-constrained auto co-signer ที่ HSM เซ็นอัตโนมัติเฉพาะ PSBT ที่ตรงเงื่อนไข whitelist
4. Broadcast ธุรกรรม → ขายจริงผ่าน exchange/OTC → ชำระผู้ให้กู้ → คืนส่วนเกินให้ผู้กู้ → log ราคาที่ใช้ไว้เป็นหลักฐาน
5. สถานะ loan เปลี่ยนเป็น `closed`

## 5. Fallback Flow

หากแพลตฟอร์มหายไปหรือถูกแฮ็ก (เช่น HSM/KMS ใช้งานไม่ได้ หรือแพลตฟอร์มปิดกิจการ) ผู้กู้และผู้ให้กู้สามารถเซ็นร่วมกัน (2-of-3) เพื่อกู้คืนหรือจัดการ BTC ใน vault ได้โดยไม่ต้องพึ่งแพลตฟอร์ม — เป็นเหตุผลหลักที่ต้องออกแบบเป็น multisig จริงสามฝ่ายแทนการให้แพลตฟอร์มถือ key เดียว
