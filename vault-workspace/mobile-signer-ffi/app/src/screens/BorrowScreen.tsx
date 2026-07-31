import React, { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Slider from '@react-native-community/slider';
import { colors, risk } from '../theme';
import { Card } from '../components/Card';
import { PrimaryButton } from '../components/PrimaryButton';
import {
  VaultState,
  btcValueThb,
  calcLiquidationPrice,
  calcLtv,
  formatBtc,
  formatPct,
  formatThb,
  freeBtc,
  maxInitialBorrow,
  riskZone,
} from '../mockVault';

const MIN = 20_000;
const STEP = 10_000;

export function BorrowScreen({
  vault,
  onBack,
  onConfirm,
}: {
  vault: VaultState;
  onBack: () => void;
  onConfirm: (collateralBtc: number, amountThb: number) => void;
}) {
  // A new contract pledges from whatever BTC is still free — see docs/design-notes.md
  const collateral = freeBtc(vault);
  const collateralFiat = btcValueThb(collateral, vault.btcPriceThb);
  const max = Math.max(MIN, maxInitialBorrow(collateral, vault.btcPriceThb));
  const [amount, setAmount] = useState(Math.min(150_000, max));

  const { ltv, zone, liquidationPrice } = useMemo(() => {
    const ltv = calcLtv(amount, collateral, vault.btcPriceThb);
    return { ltv, zone: riskZone(ltv), liquidationPrice: calcLiquidationPrice(amount, collateral) };
  }, [amount, collateral, vault.btcPriceThb]);

  return (
    <View style={styles.container}>
      <PrimaryButton label="← กลับ" onPress={onBack} variant="ghost" />
      <Text style={styles.eyebrow}>BORROW THB</Text>

      <View style={styles.amountBlock}>
        <Text style={styles.amountLabel}>คุณต้องการกู้</Text>
        <View style={styles.amountRow}>
          <Text style={styles.baht}>฿</Text>
          <Text style={styles.amount}>{amount.toLocaleString('en-US')}</Text>
        </View>
        <Text style={styles.collateralNote}>
          สัญญาใหม่ · ค้ำด้วย {formatBtc(collateral)} BTC ที่ยังว่าง ≈ {formatThb(collateralFiat)}
        </Text>
      </View>

      <Slider
        style={{ width: '100%', height: 32 }}
        minimumValue={MIN}
        maximumValue={max}
        step={STEP}
        value={amount}
        onValueChange={setAmount}
        minimumTrackTintColor={colors.teal}
        maximumTrackTintColor={colors.gray200}
        thumbTintColor={colors.teal}
      />
      <View style={styles.rangeRow}>
        <Text style={styles.rangeLabel}>฿{MIN.toLocaleString('en-US')}</Text>
        <Text style={styles.rangeLabel}>สูงสุด {formatThb(max)}</Text>
      </View>

      <Card style={styles.resultCard}>
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>LTV · ราคาบังคับขาย</Text>
          <Text style={[styles.resultValue, { color: risk[zone] }]}>
            {formatPct(ltv, 0)} · {formatThb(liquidationPrice)}
          </Text>
        </View>
        <View style={styles.hr} />
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>ดอกเบี้ย</Text>
          <Text style={styles.resultValue}>{vault.interestRatePct}% ต่อปี</Text>
        </View>
        <View style={styles.hr} />
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>รับเงินเข้า</Text>
          <Text style={styles.resultValue}>พร้อมเพย์ ···4821</Text>
        </View>
      </Card>

      <Text style={styles.note}>
        หากราคาบิตคอยน์ลดลงจนสัดส่วนหนี้ต่อหลักประกันถึง 80% ระบบจะขายบิตคอยน์ที่ค้ำไว้บางส่วนเพื่อชำระหนี้
        คุณจะได้รับการแจ้งเตือนที่ 65% และ 72% ก่อนเสมอ
      </Text>

      <PrimaryButton
        label={`ยืนยันการกู้ ${formatThb(amount)}`}
        onPress={() => onConfirm(collateral, amount)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 20, paddingTop: 60, gap: 10 },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase', marginTop: 8, textAlign: 'center' },
  amountBlock: { alignItems: 'center', marginTop: 8 },
  amountLabel: { fontSize: 11, fontWeight: '700', letterSpacing: 0.8, color: colors.gray400, textTransform: 'uppercase' },
  amountRow: { flexDirection: 'row', alignItems: 'baseline', gap: 4, marginTop: 4 },
  baht: { fontSize: 24, color: colors.gray400 },
  amount: { fontSize: 42, fontWeight: '600', color: colors.ink, letterSpacing: -0.5 },
  collateralNote: { fontSize: 13, color: colors.gray400, marginTop: 2, textAlign: 'center' },
  rangeRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: -8 },
  rangeLabel: { fontSize: 10.5, color: colors.gray300 },
  resultCard: { marginTop: 4 },
  resultRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  resultLabel: { fontSize: 13, color: colors.gray700 },
  resultValue: { fontSize: 15, fontWeight: '600', color: colors.ink },
  hr: { height: 1, backgroundColor: colors.gray200, marginVertical: 9 },
  note: { fontSize: 12, lineHeight: 18, color: colors.gray700 },
});
