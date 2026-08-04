# mebit — คู่มือเริ่มต้นสำหรับทีม

ยินดีต้อนรับเข้าโปรเจกต์ capstone! นี่คือระบบปล่อยกู้ค้ำด้วย Bitcoin แบบ self-custody — ลูกค้าเอา BTC มาวางค้ำในกระเป๋า 2-of-3 multisig (ผู้กู้ / แพลตฟอร์ม / ผู้ให้กู้) แล้วกู้เงินบาทได้โดยไม่ต้องขาย BTC ไม่มีฝ่ายใดฝ่ายหนึ่งเคลื่อนย้ายเหรียญได้คนเดียว

## อ่านอะไรก่อน (เรียงตามลำดับ)

1. [`docs/00-capstone-brief.md`](docs/00-capstone-brief.md) — โจทย์ต้นฉบับฉบับเต็ม (ภาษาไทย) **อ่านอันนี้ก่อนทุกอย่าง**
2. [`docs/01-architecture.md`](docs/01-architecture.md) — สรุปสถาปัตยกรรมแบบย่อ (อังกฤษ)
3. [`docs/02-roles-and-responsibilities.md`](docs/02-roles-and-responsibilities.md) — ใครถือคีย์ไหน เซ็นคู่กับใครตอนไหน
4. [`docs/03-flows.md`](docs/03-flows.md) — โฟลว์แบบละเอียด: onboarding, เปิด loan, คืนหลักประกัน, liquidation, fallback
5. [`docs/04-open-items.md`](docs/04-open-items.md) — สิ่งที่ยังไม่ได้ตัดสินใจ — เช็คก่อนเชื่อว่าตัวเลข/กติกาไหนล็อกแล้ว
6. [`docs/design-notes.md`](docs/design-notes.md) — ดีไซน์แอปฝั่งผู้กู้ (ถ้าอยู่ทีม mobile-signer-ffi ต้องอ่าน)
7. [`docs/05-progress-and-next-steps.md`](docs/05-progress-and-next-steps.md) — **สถานะปัจจุบันจริงๆ ของแต่ละโมดูล และสิ่งที่ต้องทำต่อ** เช็คอันนี้ก่อนเริ่มงานทุกครั้งเพื่อไม่ให้ทำซ้ำกับที่มีอยู่แล้ว

## โครงสร้าง repo

```
mebit/
├── docs/                 # โจทย์ + สถาปัตยกรรม + ดีไซน์ + สถานะความคืบหน้า
├── design-reference/     # design token / โลโก้ ของแอป mobile
├── vault-workspace/      # โค้ดจริงทั้งหมด — Rust workspace, cargo build/test จากโฟลเดอร์นี้
│   ├── vault-core/           # descriptor, key derivation, PSBT, policy engine — จุดวิกฤตของระบบ
│   ├── custody-service/      # REST service ฝั่งแพลตฟอร์ม (axum, รันได้จริงที่ :8080)
│   ├── mobile-signer-ffi/    # UniFFI bindings + แอป React Native (รันได้จริง)
│   ├── lender-signer-cli/    # CLI เซ็น PSBT แบบ offline (clap, รันได้จริง)
│   └── monitor-service/      # ตรวจ LTV / trigger liquidation (รันได้จริง)
├── .claude/
│   ├── agents/           # subagent ต่อโมดูล — ใช้กับ Claude Code เวลาทำงานในโมดูลนั้น
│   └── skills/           # ความรู้ที่ใช้ร่วมกันได้ทุกโมดูล
└── README.md
```

แต่ละโฟลเดอร์ใน `vault-workspace/` มี `README.md` ของตัวเอง บอกว่าใครดูแล ขึ้นกับอะไร และรันยังไง — เข้าไปอ่านก่อนแตะโค้ดในโมดูลนั้น

## ใครทำโมดูลไหน

ดูรายละเอียดเหตุผลเต็มๆ ที่ `docs/00-capstone-brief.md` §3.6 (**หมายเหตุ: กำลังอยู่ระหว่างปรับให้ตรงกับจำนวนทีมจริง — เช็คกับหัวหน้าทีมก่อนยึดตามตารางนี้**):

| โมดูล | คนดูแล |
|---|---|
| `vault-core` (descriptor + derivation) | คนที่ 1 |
| `vault-core` (PSBT + policy engine) | คนที่ 2 |
| `custody-service` | คนที่ 3 |
| `mobile-signer-ffi` | คนที่ 4 |
| `lender-signer-cli` + `monitor-service` | คนที่ 5 |

`vault-core` เป็นจุดวิกฤต — ทุกโมดูลอื่นพึ่งพามันโดยตรงหรือผ่าน invariant ที่มันกำหนด ส่วน `policy` module ข้างในคือโค้ดที่เสี่ยงที่สุดในทั้งโปรเจกต์ (อ่าน `.claude/skills/policy-engine-review/SKILL.md` ก่อนแก้)

## เริ่มรันโค้ดยังไง

**Rust workspace** (`vault-core`, `custody-service`, `lender-signer-cli`, `monitor-service`):
```
cd vault-workspace
cargo build --workspace
cargo test --workspace
```

**Mobile app** (Expo/React Native, รันได้จริงแล้ว มี mock data ครบ 12 หน้าจอ):
```
cd vault-workspace/mobile-signer-ffi/app
npm install
npm start        # กด w สำหรับเว็บ, i สำหรับ iOS simulator, a สำหรับ Android
```

## ทำงานกับ Claude Code

ในโฟลเดอร์นี้มี Claude Code agent และ skill เตรียมไว้ให้แล้ว:
- `.claude/agents/` — 1 agent ต่อโมดูล (`vault-core-descriptor`, `vault-core-policy`, `custody-service`, `mobile-signer`, `lender-monitor`) ใช้ agent ที่ตรงกับโมดูลที่กำลังทำ
- `.claude/skills/` — ความรู้ร่วม: `bitcoin-fundamentals` (พื้นฐาน Bitcoin), `policy-engine-review` (checklist รีวิว policy engine), `testnet-workflow` (วิธีทดสอบบน testnet), `design-tokens` (ดีไซน์ระบบของแอป)

## กฎที่ต้องรู้ก่อนเริ่ม

- **LTV และราคาบังคับขาย (liquidation price) ต้องคำนวณเสมอ ห้าม hardcode** — ดูเหตุผลใน `docs/design-notes.md`
- **การเปลี่ยนแปลงใน `policy` module ของ vault-core ต้องมีคนรีวิว 2 คนเสมอ** ไม่ใช่แค่คนเขียน
- **State machine transition ใน `custody-service` ต้อง idempotent** — ส่ง request ซ้ำต้องไม่ทำให้เกิด double-spend
- อย่าเริ่มสร้าง UniFFI/native binding ล่วงหน้าก่อน `vault-core` เสร็จ — ใช้ mock ไปก่อน (แอป mobile ตอนนี้ mock อยู่แล้วทั้งหมด)

## ติดขัดตรงไหน

เช็ค `docs/05-progress-and-next-steps.md` ก่อนว่ามีคนทำหรือวางแผนไว้แล้วหรือยัง ถ้าไม่แน่ใจเรื่อง scope หรือ ownership ถามในทีมก่อนเริ่มลงมือ
