import React from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, risk } from '../theme';
import { Card } from '../components/Card';
import { PrimaryButton } from '../components/PrimaryButton';
import { BottomNav } from '../components/BottomNav';
import {
  VaultState,
  btcValueThb,
  formatBtc,
  formatPct,
  formatThb,
  freeBtc,
  maxInitialBorrow,
  portfolioLtv,
  riskZone,
  totalDebtThb,
  worstContract,
} from '../mockVault';
import { Screen } from '../types';

function notImplemented() {
  Alert.alert('Demo', 'ยังไม่พร้อมใช้งานใน skeleton นี้');
}

export function HomeScreen({ vault, onNavigate }: { vault: VaultState; onNavigate: (s: Screen) => void }) {
  const free = freeBtc(vault);
  const credit = maxInitialBorrow(free, vault.btcPriceThb);
  const debt = totalDebtThb(vault.contracts);
  const ltv = portfolioLtv(vault.contracts, vault.btcPriceThb);
  const zone = riskZone(ltv);
  const worst = worstContract(vault.contracts, vault.btcPriceThb);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <View style={styles.logoRow}>
            <Text style={styles.logo}>mebit</Text>
            <View style={styles.dot} />
          </View>
          <View style={styles.pricePill}>
            <View style={styles.priceDot} />
            <Text style={styles.priceText}>1 BTC = {formatThb(vault.btcPriceThb)}</Text>
          </View>
        </View>

        <Text style={styles.eyebrow}>TOTAL BITCOIN</Text>
        <View style={styles.balanceRow}>
          <Text style={styles.balance}>{formatBtc(vault.btcHeld)}</Text>
          <Text style={styles.balanceUnit}>BTC</Text>
        </View>
        <Text style={styles.balanceThb}>≈ {formatThb(btcValueThb(vault.btcHeld, vault.btcPriceThb))}</Text>

        <Card style={styles.summaryCard}>
          <View style={styles.summaryRow}>
            <View style={styles.summaryCol}>
              <Text style={styles.summaryLabelGreen}>AVAILABLE CREDIT</Text>
              <Text style={styles.summaryValue}>{formatThb(credit)}</Text>
              <Text style={styles.summaryHint}>จาก {formatBtc(free)} BTC ที่ยังว่าง</Text>
            </View>
            <View style={styles.divider} />
            <View style={styles.summaryCol}>
              <Text style={styles.summaryLabel}>TOTAL DEBT</Text>
              <Text style={styles.summaryValue}>{formatThb(debt)}</Text>
              <Text style={styles.summaryHint}>
                {vault.contracts.length} สัญญา · ดอกเบี้ย {vault.interestRatePct}% ต่อปี
              </Text>
            </View>
          </View>
          <View style={styles.hr} />
          <Pressable onPress={() => onNavigate('portfolio')}>
            <View style={styles.ltvRow}>
              <Text style={styles.ltvLabel}>LTV รวม</Text>
              <Text style={[styles.ltvValue, { color: risk[zone] }]}>{formatPct(ltv, 0)}</Text>
            </View>
            <View style={styles.barTrack}>
              <View
                style={[styles.barFill, { width: `${Math.min(ltv / 0.8, 1) * 100}%`, backgroundColor: risk[zone] }]}
              />
            </View>
            <View style={styles.axisRow}>
              <Text style={styles.axisLabel}>
                เสี่ยงสุด · {worst.contract.label} ที่ {formatPct(worst.ltv, 0)}
              </Text>
              <Text style={styles.axisLabel}>บังคับขาย 80%</Text>
            </View>
          </Pressable>
        </Card>

        <Pressable style={styles.borrowCta} onPress={() => onNavigate('borrow')}>
          <View style={styles.yellowBar} />
          <View style={{ flex: 1 }}>
            <Text style={styles.borrowTitle}>กู้เงินบาท</Text>
            <Text style={styles.borrowSubtitle}>ใช้บิตคอยน์ค้ำประกัน ไม่ต้องขาย</Text>
          </View>
          <Text style={styles.chevron}>›</Text>
        </Pressable>

        <View style={styles.quickRow}>
          <PrimaryButton label="รับ" onPress={() => onNavigate('receive')} variant="outline" />
          <PrimaryButton label="ส่ง" onPress={notImplemented} variant="outline" />
          <PrimaryButton label="สลับ" onPress={notImplemented} variant="outline" />
        </View>

        <View style={styles.sectionHead}>
          <View style={styles.sectionDot} />
          <Text style={styles.sectionTitle}>RECENT ACTIVITY</Text>
        </View>
        <View style={styles.activityRow}>
          <View style={[styles.activityIcon, { backgroundColor: 'rgba(77,184,72,0.14)' }]}>
            <Text style={{ color: colors.greenLeaf }}>↓</Text>
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.activityTitle}>รับบิตคอยน์</Text>
            <Text style={styles.activitySub}>28 ก.ค. · ยืนยันแล้ว</Text>
          </View>
          <View style={{ alignItems: 'flex-end' }}>
            <Text style={styles.activityAmount}>+0.052 BTC</Text>
            <Text style={styles.activitySub}>≈ ฿270,712</Text>
          </View>
        </View>
        <View style={styles.activityRow}>
          <View style={[styles.activityIcon, { backgroundColor: 'rgba(0,115,104,0.1)' }]}>
            <Text style={{ color: colors.teal }}>↑</Text>
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.activityTitle}>กู้เงินบาท · สัญญา #1</Text>
            <Text style={styles.activitySub}>21 ก.ค. · เข้าพร้อมเพย์</Text>
          </View>
          <View style={{ alignItems: 'flex-end' }}>
            <Text style={styles.activityAmount}>฿500,000</Text>
            <Text style={styles.activitySub}>LTV 48%</Text>
          </View>
        </View>
      </ScrollView>
      <BottomNav active="home" onNavigate={onNavigate} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.mintTint },
  content: { padding: 20, paddingTop: 50, gap: 14 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  logoRow: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  logo: { fontSize: 19, fontWeight: '600', color: colors.teal, letterSpacing: -0.3 },
  dot: { width: 6, height: 6, borderRadius: 999, backgroundColor: colors.yellow, marginTop: 7 },
  pricePill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    height: 32,
    paddingHorizontal: 12,
    borderRadius: 999,
    backgroundColor: colors.white,
  },
  priceDot: { width: 6, height: 6, borderRadius: 999, backgroundColor: colors.greenLeaf },
  priceText: { fontSize: 12, fontWeight: '500', color: colors.gray700 },
  eyebrow: { fontSize: 10.5, fontWeight: '700', letterSpacing: 1, color: colors.gray400, textTransform: 'uppercase', marginTop: 4 },
  balanceRow: { flexDirection: 'row', alignItems: 'baseline', gap: 8 },
  balance: { fontSize: 44, fontWeight: '400', color: colors.ink, letterSpacing: -0.5 },
  balanceUnit: { fontSize: 18, color: colors.gray400 },
  balanceThb: { fontSize: 16, color: colors.gray700, marginTop: -6 },
  summaryCard: { marginTop: 4 },
  summaryRow: { flexDirection: 'row' },
  summaryCol: { flex: 1 },
  divider: { width: 1, backgroundColor: colors.gray200, marginHorizontal: 14 },
  summaryLabelGreen: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.6, color: colors.greenLeaf, textTransform: 'uppercase' },
  summaryLabel: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.6, color: colors.gray400, textTransform: 'uppercase' },
  summaryValue: { fontSize: 20, fontWeight: '600', color: colors.ink, marginTop: 4, letterSpacing: -0.2 },
  summaryHint: { fontSize: 11.5, color: colors.gray400, marginTop: 1 },
  hr: { height: 1, backgroundColor: colors.gray200, marginVertical: 14 },
  ltvRow: { flexDirection: 'row', alignItems: 'baseline', gap: 8 },
  ltvLabel: { fontSize: 11, fontWeight: '700', color: colors.gray400, letterSpacing: 0.6 },
  ltvValue: { fontSize: 16, fontWeight: '700' },
  barTrack: { marginTop: 8, height: 6, borderRadius: 999, backgroundColor: colors.gray100, overflow: 'hidden' },
  barFill: { height: '100%', borderRadius: 999 },
  axisRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 },
  axisLabel: { fontSize: 10, color: colors.gray300 },
  borrowCta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    minHeight: 64,
    borderRadius: 20,
    backgroundColor: colors.teal,
    paddingHorizontal: 20,
  },
  yellowBar: { width: 4, height: 34, borderRadius: 999, backgroundColor: colors.yellow },
  borrowTitle: { fontSize: 18, fontWeight: '600', color: colors.white },
  borrowSubtitle: { fontSize: 12.5, fontWeight: '300', color: 'rgba(255,255,255,0.72)', marginTop: 1 },
  chevron: { fontSize: 22, color: colors.white },
  quickRow: { flexDirection: 'row', gap: 10 },
  sectionHead: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 6 },
  sectionDot: { width: 4, height: 11, borderRadius: 999, backgroundColor: colors.greenLeaf },
  sectionTitle: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.8, color: colors.teal, textTransform: 'uppercase' },
  activityRow: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 52 },
  activityIcon: { width: 34, height: 34, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  activityTitle: { fontSize: 14.5, fontWeight: '500', color: colors.ink },
  activitySub: { fontSize: 12, color: colors.gray400, marginTop: 1 },
  activityAmount: { fontSize: 14.5, fontWeight: '600', color: colors.ink },
});
