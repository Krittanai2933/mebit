# mobile-signer-ffi / แอปมือถือ "mebit" — สเปคจากไฟล์ Design

สกัดจากไฟล์ดีไซน์ที่ผู้ใช้อัปโหลด (`mebit App.dc.html`) เพื่อใช้เป็น source of truth ของหน้าตาและพฤติกรรมแอปฝั่งผู้กู้ แทนที่คำอธิบายแบบกว้างๆ เดิมใน `01-architecture-overview.md` และ `05-capstone-proposal.md`

## แนวคิดแบรนด์

- ชื่อโปรดักต์: **mebit** — sub-brand ภายใต้ Mapboss CI (teal `#007368`, leaf green, จุดสีเหลืองเป็น accent อุ่นจุดเดียว, ฟอนต์ FC Vision ทั้งหมด)
- แท็กไลน์: *"กระเป๋าบิตคอยน์ที่ถือกุญแจเอง แต่ใช้มูลค่าได้โดยไม่ต้องขาย"*
- Light theme เป็นหลัก (มิ้นท์วอช `#F2F8F7`); เขียวเข้ม `#06312D` สงวนไว้เฉพาะ 2 จุดที่ต้องรู้สึก "หนัก": risk panel ของ Loan Dashboard และหน้า success
- ภาษาไทยแบกเนื้อหาหลักทั้งหมด ส่วนภาษาอังกฤษใช้เป็น label แบบ uppercase eyebrow เท่านั้น (RECEIVE BITCOIN, LOAN DASHBOARD, LTV)
- ปุ่ม Borrow เป็น CTA หลักบน Home — บล็อกทีลสูง 64px พร้อมแถบ accent เหลือง ไม่ใช่แท็บ; bottom nav มี 5 แท็บเท่ากัน ไม่มี center FAB
- ยังไม่มี icon set ของ Mapboss CI จริง — ดีไซน์ปัจจุบันใช้ minimal line icon 1.8px เป็น placeholder ไปก่อน (ต้องตามงาน brand/design ทีมจริงภายหลัง)

## 12 หน้าจอ ตามลำดับ journey

1. **Splash** — เปิดแอปพร้อม endorsement "powered by mapboss"
2. **Seed backup** — สำรอง seed phrase ตอน onboarding
3. **Face ID** — ยืนยันตัวตนด้วย biometric ก่อนเข้าแอป
4. **Home** — หน้าสรุปยอด (ดูรายละเอียด variation ด้านล่าง)
5. **Receive** — รับ BTC เข้ากระเป๋า
6. **Borrow** — ขอสินเชื่อ (ลาก slider ปรับยอด, LTV และราคาบังคับขายคำนวณสด)
7. **Loan Dashboard** — ดูสถานะสัญญาเงินกู้ + risk panel
8. **Repay** — ชำระคืน (preset จำนวนเงินใช้งานได้จริง)
9. **Success** — หน้าจบธุรกรรมสำเร็จ
10. **Activity** — ledger รวม BTC movement + loan events
11. **Portfolio** — มุมมองหลายสัญญาเงินกู้พร้อมกัน
12. **Settings** — จัดการคีย์/การแจ้งเตือน/ตั้งค่า

Tappable prototype เดินเรื่องเป็น: Splash → Seed backup → Face ID → Home → Receive → Borrow → Loan Dashboard → Repay → Success ส่วน Activity / Portfolio / Settings เข้าถึงผ่าน bottom nav

## ตัวเลขอ้างอิงที่ดีไซน์ใช้ (ตัวอย่างเดโม)

- ยอดรวม 0.412 BTC ≈ ฿2,145,000 (ราคาบิตคอยน์อ้างอิง ฿5,206,000)
- วงเงินกู้ที่ใช้ได้ ฿1,200,000, หนี้คงเหลือ ฿350,000, LTV 16.3% (โซนปลอดภัย)
- ตัวอย่างขอกู้: ฿500,000 ค้ำด้วย 0.20 BTC (≈ ฿1,041,200) → LTV 48%, ราคาบังคับขาย ฿3,125,000

## Risk visualization — ค่าที่ดีไซน์ตกลงใช้จริง

ดีไซน์เสนอค่าตัวเลขที่ชัดเจนกว่าที่เราเคยระบุเป็น "ตัวอย่าง ไม่ finalize" ไว้ในเอกสารเดิม:

- **แนวคิด**: risk แสดงเป็น arc ที่เติมเข้าหาจุด liquidation, sweep เต็ม = LTV 80% เพื่อให้ "ใกล้แค่ไหน" อ่านง่ายเชิงพื้นที่ ไม่ต้องคิดเลข
- **โซนสี**: เขียว (leaf) `<50%`, เหลือง `50–65%`, ส้ม `>65%`
- **แจ้งเตือนล่วงหน้า**: ระบบแจ้งเตือนที่ LTV **65%** และ **72%** ก่อนเสมอ
- **จุด liquidation**: **80%** — ระบบจะขายบิตคอยน์ที่ค้ำไว้บางส่วนเพื่อชำระหนี้เท่าที่จำเป็น (ไม่ขายทั้งก้อน — ตรงกับ liquidation flow ที่ออกแบบไว้ใน `03-flows.md`)
- **ปุ่มบน Loan Dashboard**: "เพิ่ม BTC ค้ำ" (เติมหลักประกัน) และ "ชำระคืน"

**Borrow risk presets** (ทางเลือกไม่ต้องกรอกตัวเลขเอง เพื่อกันผู้กู้หน้าใหม่เข้าใกล้ LTV สูงเกินไป):

| Preset | LTV | ราคาบังคับขาย | ราคาต้องลดลง |
|---|---|---|---|
| ระมัดระวัง | 25% | ฿1,625,000 | 69% |
| สมดุล | 50% | ฿3,250,000 | 38% |
| สูงสุด | 70% | ฿4,550,000 | 13% |

> หมายเหตุ: ตัวเลข 65/72/80% นี้มาจากดีไซน์ ยังต้องให้ทีมความเสี่ยง/ธุรกิจ sign-off อย่างเป็นทางการ แต่ถือเป็นข้อเสนอที่เป็นรูปธรรมกว่าเดิมแล้ว ควรใช้แทนตัวเลขตัวอย่าง 50/70/80% เดิมในเอกสารสถาปัตยกรรม

## Portfolio (multi-loan) — ผลต่อ data model

- ลูกค้าหนึ่งคนมีได้หลายสัญญาเงินกู้พร้อมกัน แต่ละสัญญา **แยกหลักประกันเป็นอิสระ ไม่ cross-collateralise กัน**
- ตัวอย่าง: 0.330 BTC ถูก pledge กระจายใน 3 สัญญา (หนี้รวม ฿710,000, blended LTV 41.3%), เหลือ 0.082 BTC ที่ "free" — ยังไม่ผูกกับสัญญาไหนเลย
- ลิสต์ในหน้า Portfolio เรียงตามความเสี่ยง ไม่ใช่ตามวันที่ เพื่อให้สัญญาที่ต้องการความสนใจอยู่บนสุดเสมอ
- **นัยสำคัญต่อสถาปัตยกรรม**: schema เดิม (`loans` ตารางที่มี `vault_descriptor`/`vault_address` ต่อ 1 loan, unique ต่อ `customer_id + loan_index`) รองรับ multi-loan อยู่แล้วในระดับ backend ไม่ต้องแก้ schema แต่ฝั่งแอปต้องรวมยอดข้าม vault address หลายอันมาแสดงเป็น "TOTAL BITCOIN" เดียว และต้องมีแนวคิด "BTC ที่ยังไม่ pledge" แยกต่างหากจาก BTC ที่ pledge ไปกับ loan ใดๆ แล้ว — ดูหัวข้อถัดไป

## Activity — ledger รวม

- ledger เดียวรวมทั้ง BTC movement (ฝาก/ถอน/รับ) และ loan events (เปิดกู้/ชำระ/margin call/liquidation) จัดกลุ่มตามเดือน มี filter chip 3 แบบ
- แต่ละแถวที่เกี่ยวกับ loan ต้องโชว์ผลกระทบต่อ LTV ด้านขวา — LTV alert ก็ต้องปรากฏเป็นรายการใน ledger นี้ด้วย ไม่ใช่แค่ push notification เฉยๆ
- **นัยสำคัญ**: mobile-signer-ffi/demo app ต้องดึงทั้งข้อมูล on-chain (ผ่าน Monitor Service/Esplora) และ loan state events (ผ่าน custody-service) มา merge เป็น timeline เดียว

## Settings — sovereignty framing

- เปิดด้วย panel มืด (เขียวเข้ม) เพราะ "การันตีว่ากุญแจอยู่ที่อุปกรณ์นี้" ต้องอยู่บนสุด: แสดงว่า key อยู่บนเครื่องนี้ + วันที่ backup ล่าสุดที่ยืนยันแล้ว
- ตามด้วย 3 กลุ่ม: keys, loan alerts, preferences
- ปิดท้ายด้วยประโยคอธิบายว่าการลบแอปมีผล/ไม่มีผลอย่างไรต่อกองทุน (สื่อสารว่าลบแอปไม่ได้แปลว่าเสีย BTC ถ้า backup seed ไว้แล้ว)

---

## ผลต่อขอบเขตของ mobile-signer-ffi (สิ่งที่ต้องแก้จากเอกสารเดิม)

เดิมเราเขียน mobile-signer-ffi ไว้แค่ "ห่อ logic การ derive key และเซ็น PSBT จาก vault-core ให้เรียกจาก Kotlin/Swift" ซึ่ง**แคบเกินไป**เมื่อเทียบกับดีไซน์นี้ เพราะดีไซน์นี้คือแอป wallet เต็มรูปแบบ ไม่ใช่แค่หน้าจอเซ็น PSBT ไม่กี่หน้า สิ่งที่ต้องเพิ่มเข้าไปจริงๆ:

1. **ต้องมีเลเยอร์ "hot wallet" จริงสำหรับ BTC ที่ยังไม่ pledge** — หน้า Home/Receive/Portfolio แสดง BTC "free" ที่ยังไม่ผูกกับ loan ใด ซึ่งหมายความว่าแอปต้องดูแล UTXO/address ของผู้ใช้เองแบบ single-sig ด้วย (ไม่ใช่แค่ participant ใน multisig vault ตอนมี loan) — ต้องมี wallet logic เต็มรูปแบบ: address generation, UTXO tracking, fee estimation, broadcast ธุรกรรมทั่วไป (ไม่ใช่แค่ PSBT ของ vault)
2. **เมื่อเปิด loan** BTC ที่เลือก pledge จะย้าย/ผูกเข้ากับ vault address ของ loan นั้น (multisig 2-of-3) — เป็นจุดที่ vault-core logic (policy + PSBT) เข้ามาเกี่ยวข้อง
3. แนะนำให้ vault-core หรือโมดูลใหม่ใน workspace ใช้ `bdk` (Bitcoin Dev Kit) เวอร์ชัน Rust core (ไม่ใช่ bdk-rn/bdk-dart wrapper ที่ตัดออกไปแล้ว) เป็นฐานของ wallet logic ส่วน single-sig เพราะจะได้ UTXO management, fee estimation, transaction building แบบสำเร็จรูป และยังใช้ UniFFI ห่อให้มือถือเรียกได้เหมือนเดิม
4. ขอบเขตของ demo app ขยายจาก "เปิด loan + คืนหลักประกัน" เป็นครบ 12 หน้าจอเต็มตามดีไซน์ ซึ่งกระทบ timeline ของ capstone (ดูอัปเดตใน `05-capstone-proposal.md`)

**สรุปสั้นๆ**: mobile-signer-ffi ต้องเป็นทั้ง (ก) hot wallet เต็มรูปแบบสำหรับ BTC ที่ยังไม่ pledge และ (ข) multisig vault signer สำหรับ BTC ที่ pledge เป็นหลักประกันแล้ว สองเลเยอร์นี้ต้องออกแบบให้สลับโหมดกันได้ลื่นในมุมมองผู้ใช้ (หน้าจอเดียวกันแสดงทั้งสองส่วนรวมเป็นยอดเดียว) แต่แยกกันชัดเจนในเชิง key/signing logic

## Wallet capability checklist (สิ่งที่ "hot wallet เต็มรูปแบบ" ต้องทำได้จริง)

การเรียกว่าเป็น hot wallet ไม่ใช่แค่โชว์ balance — ต้องรองรับทุกความสามารถพื้นฐานของ Bitcoin wallet จริง ไม่ใช่แค่ signing UI:

1. **สร้างธุรกรรม (transaction construction)** — coin selection จาก UTXO set, คำนวณ fee, ประกอบ transaction ทั่วไป ไม่ใช่แค่ PSBT ของ vault
2. **ส่ง BTC (send)** — สร้าง, เซ็น (single-sig สำหรับส่วน free), และ broadcast ธุรกรรมออกไปที่เครือข่าย
3. **รับ BTC (receive)** — สร้าง address ใหม่ตาม descriptor, ติดตาม deposit ที่เข้ามา ยืนยันบนเชน
4. **เชื่อมต่อโหนด (node connectivity)** — client สำหรับคุยกับ Electrum/Esplora (หรือ full node ในอนาคต) เพื่อ sync UTXO, ยอดคงเหลือ, ประวัติธุรกรรม, และ fee estimate — เป็นชั้นเดียวกับที่ Monitor Service ใช้ฝั่ง backend แต่ต้องมีในแอปด้วยเพื่อให้ทำงานได้แม้ backend ล่ม (สอดคล้องกับหลัก self-custody: ผู้กู้ต้องดูข้อมูลของตัวเองได้โดยไม่พึ่งแพลตฟอร์ม)
5. **สร้าง PSBT สำหรับ watch-only** — ต้องรองรับโฟลว์ที่อุปกรณ์หนึ่งถือแค่ public descriptor/xpub (watch-only ไม่มี private key) สร้าง PSBT ที่ยังไม่เซ็นได้ แล้วส่งให้อุปกรณ์ที่ถือ private key จริงเซ็นแยกต่างหาก — เป็น pattern เดียวกับที่ `lender-signer-cli` ใช้อยู่แล้วฝั่งผู้ให้กู้ (air-gapped signer) และเป็นประโยชน์ต่อผู้กู้ที่อยากมี cold backup/hardware wallet ของตัวเองในอนาคตด้วย

**ข่าวดี**: ทั้ง 5 ข้อนี้คือสิ่งที่ `bdk` (Bitcoin Dev Kit) Rust core รองรับให้อยู่แล้วแบบ built-in (wallet ที่ผูกกับ descriptor, blockchain backend แบบ swap ได้ระหว่าง Electrum/Esplora, coin selection, PSBT-native ทุกจุด รวมถึงโหมด watch-only จากการใส่แค่ public descriptor) — นี่คือเหตุผลหลักที่แนะนำให้ mobile-signer-ffi ต่อกับ `bdk` โดยตรงแทนการเขียน wallet logic เองใหม่ทั้งหมด ทีมจะได้โฟกัสพลังงานไปที่ policy engine กับ multisig vault ซึ่งเป็นจุดเสี่ยงจริง ไม่ใช่ไปเสียเวลา reinvent wallet plumbing พื้นฐาน
