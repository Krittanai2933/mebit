# mebit — โปรดักต์บิตคอยน์ไทย (Self-Custody, On-chain Transparent)

**เริ่มอ่านที่ `07-product-vision-mebit.md` ก่อน** — เป็น north star ล่าสุดที่ปรับ framing ใหม่: mebit คือโปรดักต์บิตคอยน์สำหรับคนไทย มี wallet/custody เป็นฐาน แล้วต่อยอดด้วย buy-sell และ lending ส่วนเอกสาร 01–06 ด้านล่างคือสเปคของ**ขา lending โดยเฉพาะ** ซึ่งยังใช้ได้ทั้งหมด แค่ไม่ใช่ภาพรวมทั้งโปรดักต์อีกต่อไป

**ลำดับความสำคัญตอนนี้**: เริ่มพัฒนาขา **Wallet/Custody ก่อน** (ก่อน buy-sell และ lending) — ดูแผนจริงที่จะ build ได้ใน `09-wallet-mvp-buildplan.md`

## ไฟล์ในชุดนี้

0. `07-product-vision-mebit.md` — **north star**: โครงสร้าง 3 ขา, โมเดลกุญแจ Nunchuk-first/Casa-optional, app flow v3, นัยต่อ vault-core
1. `01-architecture-overview.md` — ภาพรวมสถาปัตยกรรมของขา lending, multisig 2-of-3, tech stack, workspace structure
2. `02-roles-and-responsibilities.md` — บทบาท หน้าที่ และคีย์ของผู้กู้ / แพลตฟอร์ม / ผู้ให้กู้ (เฉพาะขา lending)
3. `03-flows.md` — โฟลว์ onboarding, เปิด loan, verification, liquidation (เฉพาะขา lending)
4. `04-open-items.md` — สิ่งที่ยังไม่ได้ตัดสินใจ / ยังไม่ได้ทำ ต้องกลับมาคุยต่อ (เฉพาะขา lending — ดู open items ของภาพรวมทั้งหมดใน `07`)
5. `05-capstone-proposal.md` — โจทย์ capstone project สำหรับทีมนักศึกษา 5 คน 2 เทอม (ขอบเขตเดิม = ขา lending)
6. `06-mobile-app-design-mebit.md` — สเปคแอปมือถือ "mebit" ที่สกัดจากไฟล์ design ที่อัปโหลด (12 หน้าจอ, brand, LTV thresholds) — ตอนนี้ต้องอ่านคู่กับ app flow v3 ใน `07`
7. `08-multisig-wallet-spec.md` — สเปคเชิงเทคนิคของ multisig wallet: key-first data model, script types, miniscript policy (timelock fallback, decaying multisig), hardware wallet support (Jade + Trezor Safe 7, QR+BLE เท่านั้น), roadmap MVP → phase 2
8. `09-wallet-mvp-buildplan.md` — **แผนเริ่ม build จริง**: Tauri desktop (พิสูจน์ engine) → React Native + UniFFI (production mobile), ลำดับ phase 0-3

## สถานะเอกสาร

ร่างจากการคุยออกแบบสถาปัตยกรรมรอบแรก (ก.ค. 2026) — ยังไม่ครอบคลุมประเด็นกฎหมาย (ก.ล.ต./ธปท.) และ Fund/NAV ของฝั่งผู้ให้กู้ ซึ่งถูกตัดออกจากรอบนี้โดยตั้งใจ ดูรายละเอียดใน `04-open-items.md` และ `07-product-vision-mebit.md`
