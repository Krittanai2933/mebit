import React from 'react';
import { Alert, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';
import { Card } from '../components/Card';
import { PrimaryButton } from '../components/PrimaryButton';
import { RiskArc } from '../components/RiskArc';
import {
  VaultState,
  accruedInterestThb,
  btcValueThb,
  contractLiquidationPrice,
  contractLtv,
  formatBtc,
  formatThb,
  priceDropToLiquidationPct,
  riskZone,
} from '../mockVault';

function notImplemented() {
  Alert.alert('Demo', 'ยังไม่พร้อมใช้งานใน skeleton นี้');
}

export function LoanDashboardScreen({
  vault,
  contractId,
  onBack,
  onRepay,
}: {
  vault: VaultState;
  contractId: number;
  onBack: () => void;
  onRepay: () => void;
}) {
  const contract = vault.contracts.find((c) => c.id === contractId) ?? vault.contracts[0];
  const ltv = contractLtv(contract, vault.btcPriceThb);
  const zone = riskZone(ltv);
  const liquidationPrice = contractLiquidationPrice(contract);
  const drop = priceDropToLiquidationPct(vault.btcPriceThb, liquidationPrice);
  const collateralFiat = btcValueThb(contract.collateralBtc, vault.btcPriceThb);
  const interest = accruedInterestThb(contract, vault.interestRatePct);

  return (
    <View style={styles.container}>
      <PrimaryButton label="← กลับ" onPress={onBack} variant="ghost" />
      <View style={styles.headerBlock}>
        <Text style={styles.eyebrow}>LOAN DASHBOARD</Text>
        <Text style={styles.contractLabel}>{contract.label}</Text>
      </View>

      <Card style={styles.riskCard} dark>
        <RiskArc ltv={ltv} zone={zone} />
        <View style={styles.priceRow}>
          <View>
            <Text style={styles.priceLabel}>ราคาบังคับขาย</Text>
            <Text style={styles.priceValue}>{formatThb(liquidationPrice)}</Text>
          </View>
          <View style={{ alignItems: 'flex-end' }}>
            <Text style={styles.priceLabel}>ราคาวันนี้</Text>
            <Text style={[styles.priceValue, { color: '#009B68' }]}>{formatThb(vault.btcPriceThb)}</Text>
          </View>
        </View>
        <Text style={styles.dropNote}>ราคาต้องลดลงอีก {drop}% จากวันนี้ก่อนจะถึงจุดบังคับขาย</Text>
      </Card>

      <View style={styles.statsGrid}>
        <View style={styles.statCell}>
          <Text style={styles.statLabel}>หนี้คงเหลือ</Text>
          <Text style={styles.statValue}>{formatThb(contract.debtThb)}</Text>
        </View>
        <View style={styles.statCell}>
          <Text style={styles.statLabel}>หลักประกัน</Text>
          <Text style={styles.statValue}>{formatBtc(contract.collateralBtc)} BTC</Text>
        </View>
        <View style={styles.statCell}>
          <Text style={styles.statLabel}>มูลค่าหลักประกัน</Text>
          <Text style={styles.statValue}>{formatThb(collateralFiat)}</Text>
        </View>
        <View style={styles.statCell}>
          <Text style={styles.statLabel}>ดอกเบี้ยค้างชำระ</Text>
          <Text style={styles.statValue}>{formatThb(interest)}</Text>
        </View>
      </View>

      <View style={styles.actions}>
        <PrimaryButton label="ชำระคืน" onPress={onRepay} />
        <View style={styles.actionsRow}>
          <PrimaryButton label="เพิ่ม BTC ค้ำ" variant="outline" onPress={notImplemented} />
          <PrimaryButton label="ถอน BTC" variant="outline" onPress={notImplemented} />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 20, paddingTop: 60, gap: 14 },
  headerBlock: { alignItems: 'center' },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase' },
  contractLabel: { fontSize: 13, fontWeight: '500', color: colors.ink, marginTop: 2 },
  riskCard: { alignItems: 'center' },
  priceRow: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: 16, paddingTop: 14, borderTopWidth: 1, borderTopColor: 'rgba(255,255,255,0.14)' },
  priceLabel: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.6, color: 'rgba(255,255,255,0.6)', textTransform: 'uppercase' },
  priceValue: { fontSize: 20, fontWeight: '600', color: colors.white, marginTop: 3 },
  dropNote: { alignSelf: 'flex-start', fontSize: 12, lineHeight: 18, color: 'rgba(255,255,255,0.72)', marginTop: 10 },
  statsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  statCell: { width: '47.5%', backgroundColor: colors.white, borderRadius: 20, padding: 15 },
  statLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 0.6, color: colors.gray400, textTransform: 'uppercase' },
  statValue: { fontSize: 20, fontWeight: '600', color: colors.ink, marginTop: 4, letterSpacing: -0.2 },
  actions: { gap: 10, marginTop: 4 },
  actionsRow: { flexDirection: 'row', gap: 10 },
});
