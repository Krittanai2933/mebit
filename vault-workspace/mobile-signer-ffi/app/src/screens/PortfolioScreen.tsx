import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radii, risk } from '../theme';
import { Card } from '../components/Card';
import { PrimaryButton } from '../components/PrimaryButton';
import { BottomNav } from '../components/BottomNav';
import {
  VaultState,
  contractLiquidationPrice,
  contractLtv,
  formatBtc,
  formatPct,
  formatThb,
  freeBtc,
  netWorthThb,
  pledgedBtc,
  portfolioLtv,
  riskLabel,
  riskZone,
  sortContractsByRisk,
  totalDebtThb,
} from '../mockVault';
import { Screen } from '../types';

// Brighter variants of the risk colors for use on the dark net-position panel.
const DARK_ZONE_COLOR = { safe: '#009B68', watch: risk.watch, danger: risk.danger };

export function PortfolioScreen({
  vault,
  onNavigate,
  onSelectContract,
}: {
  vault: VaultState;
  onNavigate: (s: Screen) => void;
  onSelectContract: (id: number) => void;
}) {
  const pledged = pledgedBtc(vault.contracts);
  const free = freeBtc(vault);
  const debt = totalDebtThb(vault.contracts);
  const ltv = portfolioLtv(vault.contracts, vault.btcPriceThb);
  const zone = riskZone(ltv);
  const ranked = sortContractsByRisk(vault.contracts, vault.btcPriceThb);

  const allocationSegments = [
    ...vault.contracts.map((c) => ({ btc: c.collateralBtc, color: c.color })),
    { btc: free, color: colors.gray200 },
  ];

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <View style={styles.eyebrowRow}>
            <View style={styles.eyebrowDot} />
            <Text style={styles.eyebrow}>PORTFOLIO</Text>
          </View>
          <Text style={styles.count}>{vault.contracts.length} สัญญา</Text>
        </View>

        <Card style={styles.netCard} dark>
          <View style={styles.eyebrowRowDark}>
            <View style={styles.eyebrowDotYellow} />
            <Text style={styles.eyebrowDark}>NET POSITION</Text>
          </View>
          <View style={styles.netValueRow}>
            <Text style={styles.netBaht}>฿</Text>
            <Text style={styles.netValue}>{Math.round(netWorthThb(vault)).toLocaleString('en-US')}</Text>
          </View>
          <View style={styles.netStatsRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.netStatLabel}>บิตคอยน์ทั้งหมด</Text>
              <Text style={styles.netStatValue}>{formatBtc(vault.btcHeld)} BTC</Text>
            </View>
            <View style={styles.netDivider} />
            <View style={{ flex: 1 }}>
              <Text style={styles.netStatLabel}>หนี้รวม</Text>
              <Text style={styles.netStatValue}>{formatThb(debt)}</Text>
            </View>
            <View style={styles.netDivider} />
            <View style={{ flex: 1 }}>
              <Text style={styles.netStatLabel}>LTV รวม</Text>
              <Text style={[styles.netStatValue, { color: DARK_ZONE_COLOR[zone] }]}>{formatPct(ltv, 0)}</Text>
            </View>
          </View>
        </Card>

        <Card style={styles.allocCard}>
          <View style={styles.allocHeader}>
            <Text style={styles.allocTitle}>COLLATERAL ALLOCATION</Text>
            <Text style={styles.allocSub}>
              ค้ำอยู่ {formatBtc(pledged)} / {formatBtc(vault.btcHeld)} BTC
            </Text>
          </View>
          <View style={styles.allocBar}>
            {allocationSegments.map((seg, i) => (
              <View
                key={i}
                style={{ flex: Math.max(seg.btc, 0.001), backgroundColor: seg.color, height: '100%' }}
              />
            ))}
          </View>
          <View style={styles.legend}>
            {vault.contracts.map((c) => (
              <View key={c.id} style={styles.legendRow}>
                <View style={[styles.legendSwatch, { backgroundColor: c.color }]} />
                <Text style={styles.legendLabel}>{c.label}</Text>
                <Text style={styles.legendValue}>{formatBtc(c.collateralBtc)} BTC</Text>
              </View>
            ))}
            <View style={styles.legendRow}>
              <View style={[styles.legendSwatch, { backgroundColor: colors.gray200 }]} />
              <Text style={styles.legendLabel}>ว่าง ถอนได้ทันที</Text>
              <Text style={styles.legendValue}>{formatBtc(free)} BTC</Text>
            </View>
          </View>
        </Card>

        <View style={styles.sectionHead}>
          <View style={styles.sectionHeadLeft}>
            <View style={styles.sectionDot} />
            <Text style={styles.sectionTitle}>ACTIVE LOANS</Text>
          </View>
          <Text style={styles.sortedLabel}>เรียงตามความเสี่ยง</Text>
        </View>

        <View style={{ gap: 10 }}>
          {ranked.map((c) => {
            const cLtv = contractLtv(c, vault.btcPriceThb);
            const cZone = riskZone(cLtv);
            const liq = contractLiquidationPrice(c);
            return (
              <Pressable
                key={c.id}
                style={[styles.loanCard, { borderLeftColor: c.color }]}
                onPress={() => {
                  onSelectContract(c.id);
                  onNavigate('loan');
                }}
              >
                <View style={styles.loanTopRow}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.loanLabel}>{c.label}</Text>
                    <Text style={styles.loanSub}>
                      เปิด {c.openedLabel} · ค้ำ {formatBtc(c.collateralBtc)} BTC
                    </Text>
                  </View>
                  <View style={{ alignItems: 'flex-end' }}>
                    <Text style={styles.loanDebt}>{formatThb(c.debtThb)}</Text>
                    <Text style={styles.loanSub}>ดอกเบี้ย {vault.interestRatePct}%</Text>
                  </View>
                </View>
                <View style={styles.loanBarTrack}>
                  <View
                    style={[
                      styles.loanBarFill,
                      { width: `${Math.min(cLtv / 0.8, 1) * 100}%`, backgroundColor: risk[cZone] },
                    ]}
                  />
                </View>
                <View style={styles.loanBottomRow}>
                  <Text style={[styles.loanLtv, { color: risk[cZone] }]}>
                    LTV {formatPct(cLtv)} · {riskLabel(cZone)}
                  </Text>
                  <Text style={styles.loanSub}>บังคับขายที่ {formatThb(liq)}</Text>
                </View>
              </Pressable>
            );
          })}
        </View>

        <View style={styles.noteBox}>
          <View style={styles.noteBar} />
          <Text style={styles.noteText}>
            แต่ละสัญญาค้ำแยกกัน การบังคับขายจะเกิดกับสัญญาที่ถึงเกณฑ์เท่านั้น ไม่กระทบสัญญาอื่นหรือบิตคอยน์ส่วนที่ว่าง
          </Text>
        </View>

        <PrimaryButton label="เปิดสัญญาใหม่" onPress={() => onNavigate('borrow')} />
      </ScrollView>
      <BottomNav active="portfolio" onNavigate={onNavigate} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.mintTint },
  content: { padding: 20, paddingTop: 50, gap: 14 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDot: { width: 4, height: 12, borderRadius: 999, backgroundColor: colors.greenLeaf },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase' },
  count: { fontSize: 12, fontWeight: '500', color: colors.gray700 },
  netCard: {},
  eyebrowRowDark: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDotYellow: { width: 4, height: 11, borderRadius: 999, backgroundColor: colors.yellow },
  eyebrowDark: { fontSize: 10, fontWeight: '700', letterSpacing: 1, color: 'rgba(255,255,255,0.62)', textTransform: 'uppercase' },
  netValueRow: { flexDirection: 'row', alignItems: 'baseline', gap: 5, marginTop: 6 },
  netBaht: { fontSize: 24, fontWeight: '400', color: 'rgba(255,255,255,0.6)' },
  netValue: { fontSize: 36, fontWeight: '600', color: colors.white, letterSpacing: -0.5 },
  netStatsRow: { flexDirection: 'row', marginTop: 16 },
  netDivider: { width: 1, backgroundColor: 'rgba(255,255,255,0.16)', marginHorizontal: 12 },
  netStatLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 0.6, color: 'rgba(255,255,255,0.55)', textTransform: 'uppercase' },
  netStatValue: { fontSize: 16, fontWeight: '600', color: colors.white, marginTop: 3 },
  allocCard: {},
  allocHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline' },
  allocTitle: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.6, color: colors.gray400, textTransform: 'uppercase' },
  allocSub: { fontSize: 12, fontWeight: '500', color: colors.gray700 },
  allocBar: { flexDirection: 'row', height: 10, borderRadius: 999, overflow: 'hidden', marginTop: 12, gap: 2 },
  legend: { marginTop: 12, gap: 8 },
  legendRow: { flexDirection: 'row', alignItems: 'center', gap: 9 },
  legendSwatch: { width: 8, height: 8, borderRadius: 2 },
  legendLabel: { flex: 1, fontSize: 13, color: colors.gray700 },
  legendValue: { fontSize: 13, fontWeight: '600', color: colors.ink },
  sectionHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 6 },
  sectionHeadLeft: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  sectionDot: { width: 4, height: 11, borderRadius: 999, backgroundColor: colors.greenLeaf },
  sectionTitle: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.8, color: colors.teal, textTransform: 'uppercase' },
  sortedLabel: { fontSize: 12, color: colors.gray400 },
  loanCard: {
    backgroundColor: colors.white,
    borderRadius: radii.lg - 6,
    padding: 16,
    borderLeftWidth: 4,
  },
  loanTopRow: { flexDirection: 'row', gap: 10 },
  loanLabel: { fontSize: 15.5, fontWeight: '600', color: colors.ink },
  loanSub: { fontSize: 12, color: colors.gray400, marginTop: 2 },
  loanDebt: { fontSize: 17, fontWeight: '600', color: colors.ink },
  loanBarTrack: { marginTop: 12, height: 6, borderRadius: 999, backgroundColor: colors.gray100, overflow: 'hidden' },
  loanBarFill: { height: '100%', borderRadius: 999 },
  loanBottomRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', marginTop: 8 },
  loanLtv: { fontSize: 12.5, fontWeight: '500' },
  noteBox: { flexDirection: 'row', gap: 10 },
  noteBar: { width: 4, alignSelf: 'stretch', borderRadius: 999, backgroundColor: colors.yellow },
  noteText: { flex: 1, fontSize: 12.5, lineHeight: 19, color: colors.gray700 },
});
