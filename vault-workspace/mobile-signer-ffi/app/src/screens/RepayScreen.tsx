import React, { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../theme';
import { PrimaryButton } from '../components/PrimaryButton';
import {
  VaultState,
  calcLiquidationPrice,
  calcLtv,
  formatPct,
  formatThb,
} from '../mockVault';

type PayMethod = 'promptpay' | 'qr';

export function RepayScreen({
  vault,
  contractId,
  onBack,
  onConfirm,
}: {
  vault: VaultState;
  contractId: number;
  onBack: () => void;
  onConfirm: (repayThb: number) => void;
}) {
  const contract = vault.contracts.find((c) => c.id === contractId) ?? vault.contracts[0];
  const debt = contract.debtThb;
  const presets = useMemo(() => {
    const fixed = [10_000, 50_000, 100_000].filter((v) => v < debt).map((v) => ({ label: formatThb(v), value: v }));
    return [...fixed, { label: 'ทั้งหมด', value: debt }];
  }, [debt]);

  const [amount, setAmount] = useState(Math.min(50_000, debt));
  const [method, setMethod] = useState<PayMethod>('promptpay');

  const afterLoan = Math.max(debt - amount, 0);
  const afterLtv = calcLtv(afterLoan, contract.collateralBtc, vault.btcPriceThb);
  const afterLiq = afterLoan > 0 ? calcLiquidationPrice(afterLoan, contract.collateralBtc) : 0;

  return (
    <View style={styles.container}>
      <PrimaryButton label="← กลับ" onPress={onBack} variant="ghost" />
      <Text style={styles.eyebrow}>REPAY · {contract.label}</Text>

      <View style={styles.amountBlock}>
        <Text style={styles.amountLabel}>จำนวนที่ชำระ</Text>
        <View style={styles.amountRow}>
          <Text style={styles.baht}>฿</Text>
          <Text style={styles.amount}>{amount.toLocaleString('en-US')}</Text>
        </View>
        <Text style={styles.sub}>{contract.label} · หนี้คงเหลือ {formatThb(debt)}</Text>
      </View>

      <View style={styles.presetRow}>
        {presets.map((p) => {
          const active = p.value === amount;
          return (
            <Pressable
              key={p.value}
              style={[styles.presetChip, active && styles.presetChipActive]}
              onPress={() => setAmount(p.value)}
            >
              <Text style={[styles.presetLabel, active && styles.presetLabelActive]}>{p.label}</Text>
            </Pressable>
          );
        })}
      </View>

      <View style={styles.methodCard}>
        <Pressable style={styles.methodRow} onPress={() => setMethod('promptpay')}>
          <View style={[styles.radio, method === 'promptpay' && styles.radioActive]} />
          <View style={{ flex: 1 }}>
            <Text style={styles.methodTitle}>พร้อมเพย์ ···4821</Text>
            <Text style={styles.methodSub}>ตัดจากบัญชีทันที</Text>
          </View>
        </Pressable>
        <View style={styles.methodDivider} />
        <Pressable style={styles.methodRow} onPress={() => setMethod('qr')}>
          <View style={[styles.radio, method === 'qr' && styles.radioActive]} />
          <View style={{ flex: 1 }}>
            <Text style={styles.methodTitle}>สแกน QR ชำระเงิน</Text>
            <Text style={styles.methodSub}>ชำระผ่านแอปธนาคารอื่น</Text>
          </View>
        </Pressable>
      </View>

      <View style={styles.resultBox}>
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>หนี้หลังชำระ</Text>
          <Text style={styles.resultValue}>{formatThb(afterLoan)}</Text>
        </View>
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>LTV หลังชำระ</Text>
          <Text style={[styles.resultValue, { color: colors.greenLeaf }]}>{formatPct(afterLtv, 0)}</Text>
        </View>
        <View style={styles.resultRow}>
          <Text style={styles.resultLabel}>ราคาบังคับขายใหม่</Text>
          <Text style={styles.resultValue}>{afterLiq > 0 ? formatThb(afterLiq) : '—'}</Text>
        </View>
      </View>

      <PrimaryButton label="ยืนยันการชำระ" onPress={() => onConfirm(amount)} />
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
  sub: { fontSize: 13, color: colors.gray400, marginTop: 2 },
  presetRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  presetChip: {
    minHeight: 44,
    paddingHorizontal: 14,
    justifyContent: 'center',
    borderWidth: 1.5,
    borderColor: colors.gray200,
    borderRadius: radii.md - 4,
    backgroundColor: colors.white,
  },
  presetChipActive: { borderColor: colors.teal, backgroundColor: 'rgba(0,115,104,0.06)' },
  presetLabel: { fontSize: 13.5, fontWeight: '500', color: colors.gray700 },
  presetLabelActive: { color: colors.teal, fontWeight: '600' },
  methodCard: { backgroundColor: colors.white, borderRadius: radii.lg - 4, paddingHorizontal: 18 },
  methodRow: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 60 },
  methodDivider: { height: 1, backgroundColor: colors.gray200 },
  radio: { width: 22, height: 22, borderRadius: 999, borderWidth: 1.5, borderColor: colors.gray300 },
  radioActive: { borderWidth: 6, borderColor: colors.teal },
  methodTitle: { fontSize: 15, fontWeight: '500', color: colors.ink },
  methodSub: { fontSize: 12, color: colors.gray400 },
  resultBox: { backgroundColor: 'rgba(77,184,72,0.1)', borderRadius: radii.md + 2, padding: 16, gap: 8 },
  resultRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  resultLabel: { fontSize: 13.5, color: colors.gray700 },
  resultValue: { fontSize: 15, fontWeight: '600', color: colors.ink },
});
