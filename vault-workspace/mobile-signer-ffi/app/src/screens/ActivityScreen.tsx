import React, { useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../theme';
import { Card } from '../components/Card';
import { BottomNav } from '../components/BottomNav';
import { Screen } from '../types';

type Filter = 'all' | 'btc' | 'loan';

interface ActivityItem {
  id: string;
  type: 'btc' | 'loan';
  month: string;
  title: string;
  subtitle: string;
  amount?: string;
  amountColor?: string;
  detail?: string;
  detailColor?: string;
  iconColor: string;
  iconBg: string;
  glyph: string;
}

const ITEMS: ActivityItem[] = [
  {
    id: 'jul-repay',
    type: 'loan',
    month: 'กรกฎาคม 2569',
    title: 'ชำระคืนสินเชื่อ',
    subtitle: '30 ก.ค. 09:12 · พร้อมเพย์ ···4821',
    amount: '-฿50,000',
    detail: 'LTV 50% → 43%',
    detailColor: colors.greenLeaf,
    iconColor: colors.teal,
    iconBg: 'rgba(0,115,104,0.1)',
    glyph: '↓',
  },
  {
    id: 'jul-receive',
    type: 'btc',
    month: 'กรกฎาคม 2569',
    title: 'รับบิตคอยน์',
    subtitle: '28 ก.ค. 14:03 · ยืนยัน 6/6',
    amount: '+0.052 BTC',
    detail: '≈ ฿270,712',
    iconColor: colors.greenLeaf,
    iconBg: 'rgba(77,184,72,0.14)',
    glyph: '↓',
  },
  {
    id: 'jul-alert',
    type: 'loan',
    month: 'กรกฎาคม 2569',
    title: 'แจ้งเตือน LTV ถึง 65%',
    subtitle: '26 ก.ค. 21:40 · ราคาบิตคอยน์ลดลง',
    detail: 'เฝ้าระวัง',
    detailColor: '#C89020',
    iconColor: '#C89020',
    iconBg: 'rgba(252,195,48,0.2)',
    glyph: '!',
  },
  {
    id: 'jul-borrow',
    type: 'loan',
    month: 'กรกฎาคม 2569',
    title: 'กู้เงินบาท',
    subtitle: '21 ก.ค. 10:28 · เข้าพร้อมเพย์ ···4821',
    amount: '฿500,000',
    detail: 'ค้ำ 0.20 BTC',
    iconColor: colors.teal,
    iconBg: 'rgba(0,115,104,0.1)',
    glyph: '↑',
  },
  {
    id: 'jul-lock',
    type: 'btc',
    month: 'กรกฎาคม 2569',
    title: 'ล็อกบิตคอยน์เป็นหลักประกัน',
    subtitle: '21 ก.ค. 10:26 · กุญแจยังอยู่กับคุณ',
    amount: '0.20 BTC',
    iconColor: colors.teal,
    iconBg: 'rgba(0,115,104,0.1)',
    glyph: '→',
  },
  {
    id: 'jun-receive',
    type: 'btc',
    month: 'มิถุนายน 2569',
    title: 'รับบิตคอยน์',
    subtitle: '14 มิ.ย. 18:55 · ยืนยัน 6/6',
    amount: '+0.360 BTC',
    detail: '≈ ฿1,874,160',
    iconColor: colors.greenLeaf,
    iconBg: 'rgba(77,184,72,0.14)',
    glyph: '↓',
  },
];

const MONTHS = ['กรกฎาคม 2569', 'มิถุนายน 2569'];

export function ActivityScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [filter, setFilter] = useState<Filter>('all');
  const visible = ITEMS.filter((i) => filter === 'all' || i.type === filter);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <View style={styles.eyebrowRow}>
            <View style={styles.eyebrowDot} />
            <Text style={styles.eyebrow}>ACTIVITY</Text>
          </View>
          <Pressable
            style={styles.iconButton}
            onPress={() => Alert.alert('Demo', 'ยังไม่พร้อมใช้งานใน skeleton นี้')}
          >
            <Text>≡</Text>
          </Pressable>
        </View>

        <Card style={styles.summaryCard}>
          <View style={{ flex: 1 }}>
            <Text style={styles.summaryLabel}>รับเข้าเดือนนี้</Text>
            <Text style={[styles.summaryValue, { color: colors.greenLeaf }]}>+0.052 BTC</Text>
          </View>
          <View style={styles.summaryDivider} />
          <View style={{ flex: 1 }}>
            <Text style={styles.summaryLabel}>กู้เดือนนี้</Text>
            <Text style={styles.summaryValue}>฿500,000</Text>
          </View>
          <View style={styles.summaryDivider} />
          <View style={{ flex: 1 }}>
            <Text style={styles.summaryLabel}>ชำระคืน</Text>
            <Text style={styles.summaryValue}>฿50,000</Text>
          </View>
        </Card>

        <View style={styles.filterRow}>
          {(
            [
              ['all', 'ทั้งหมด'],
              ['btc', 'บิตคอยน์'],
              ['loan', 'สินเชื่อ'],
            ] as [Filter, string][]
          ).map(([key, label]) => {
            const active = filter === key;
            return (
              <Pressable
                key={key}
                style={[styles.chip, active && styles.chipActive]}
                onPress={() => setFilter(key)}
              >
                <Text style={[styles.chipLabel, active && styles.chipLabelActive]}>{label}</Text>
              </Pressable>
            );
          })}
        </View>

        {MONTHS.map((month) => {
          const rows = visible.filter((i) => i.month === month);
          if (!rows.length) return null;
          return (
            <View key={month}>
              <Text style={styles.monthLabel}>{month}</Text>
              {rows.map((item) => (
                <View key={item.id} style={styles.row}>
                  <View style={[styles.rowIcon, { backgroundColor: item.iconBg }]}>
                    <Text style={{ color: item.iconColor, fontWeight: '700' }}>{item.glyph}</Text>
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.rowTitle}>{item.title}</Text>
                    <Text style={styles.rowSub}>{item.subtitle}</Text>
                  </View>
                  {item.amount ? (
                    <View style={{ alignItems: 'flex-end' }}>
                      <Text style={styles.rowAmount}>{item.amount}</Text>
                      {item.detail && (
                        <Text style={[styles.rowDetail, item.detailColor && { color: item.detailColor }]}>
                          {item.detail}
                        </Text>
                      )}
                    </View>
                  ) : (
                    item.detail && (
                      <Text style={[styles.rowDetail, item.detailColor && { color: item.detailColor }]}>
                        {item.detail}
                      </Text>
                    )
                  )}
                </View>
              ))}
            </View>
          );
        })}
      </ScrollView>
      <BottomNav active="activity" onNavigate={onNavigate} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.mintTint },
  content: { padding: 20, paddingTop: 50, gap: 14 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDot: { width: 4, height: 12, borderRadius: 999, backgroundColor: colors.greenLeaf },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase' },
  iconButton: {
    width: 36,
    height: 36,
    borderRadius: 12,
    backgroundColor: colors.white,
    alignItems: 'center',
    justifyContent: 'center',
  },
  summaryCard: { flexDirection: 'row' },
  summaryDivider: { width: 1, backgroundColor: colors.gray200, marginHorizontal: 12 },
  summaryLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 0.6, color: colors.gray400, textTransform: 'uppercase' },
  summaryValue: { fontSize: 16, fontWeight: '600', color: colors.ink, marginTop: 4 },
  filterRow: { flexDirection: 'row', gap: 8 },
  chip: {
    minHeight: 36,
    paddingHorizontal: 16,
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: colors.gray200,
    borderRadius: radii.pill,
    backgroundColor: colors.white,
  },
  chipActive: { backgroundColor: colors.teal, borderColor: colors.teal },
  chipLabel: { fontSize: 13, fontWeight: '500', color: colors.gray700 },
  chipLabelActive: { color: colors.white },
  monthLabel: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.8, color: colors.gray400, textTransform: 'uppercase', marginTop: 4 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 60,
    borderBottomWidth: 1,
    borderBottomColor: '#E4EAE9',
  },
  rowIcon: { width: 34, height: 34, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  rowTitle: { fontSize: 14.5, fontWeight: '500', color: colors.ink },
  rowSub: { fontSize: 12, color: colors.gray400, marginTop: 1 },
  rowAmount: { fontSize: 14.5, fontWeight: '600', color: colors.ink },
  rowDetail: { fontSize: 11.5, color: colors.gray400, marginTop: 1 },
});
