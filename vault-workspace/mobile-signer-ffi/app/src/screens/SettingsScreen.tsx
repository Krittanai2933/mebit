import React, { useState } from 'react';
import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../theme';
import { BottomNav } from '../components/BottomNav';
import { Screen } from '../types';

function notImplemented() {
  Alert.alert('Demo', 'ยังไม่พร้อมใช้งานใน skeleton นี้');
}

function Toggle({ value, onToggle }: { value: boolean; onToggle: () => void }) {
  return (
    <Pressable
      style={[styles.toggleTrack, value && styles.toggleTrackOn]}
      onPress={onToggle}
    >
      <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
    </Pressable>
  );
}

function Row({
  title,
  subtitle,
  trailing,
  onPress,
  isLast,
}: {
  title: string;
  subtitle?: string;
  trailing?: React.ReactNode;
  onPress?: () => void;
  isLast?: boolean;
}) {
  return (
    <Pressable
      style={[styles.row, !isLast && styles.rowBorder]}
      onPress={onPress}
      disabled={!onPress}
    >
      <View style={{ flex: 1 }}>
        <Text style={styles.rowTitle}>{title}</Text>
        {subtitle && <Text style={styles.rowSubtitle}>{subtitle}</Text>}
      </View>
      {trailing ?? <Text style={styles.chevron}>›</Text>}
    </Pressable>
  );
}

function GroupHeader({ label }: { label: string }) {
  return (
    <View style={styles.groupHead}>
      <View style={styles.groupDot} />
      <Text style={styles.groupTitle}>{label}</Text>
    </View>
  );
}

export function SettingsScreen({ onNavigate }: { onNavigate: (s: Screen) => void }) {
  const [faceId, setFaceId] = useState(true);
  const [ltvAlert, setLtvAlert] = useState(true);

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.eyebrowRow}>
          <View style={styles.eyebrowDot} />
          <Text style={styles.eyebrow}>SETTINGS</Text>
        </View>

        <View style={styles.identityCard}>
          <View style={styles.identityTop}>
            <View style={styles.avatar}>
              <Text style={styles.avatarText}>ธ</Text>
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.identityName}>ธนกฤต วงศ์อนันต์</Text>
              <Text style={styles.identitySub}>ยืนยันตัวตนแล้ว · KYC ระดับ 2</Text>
            </View>
            <Text style={styles.chevronDark}>›</Text>
          </View>
          <View style={styles.identityDivider} />
          <View style={styles.identityBottom}>
            <View style={styles.yellowBar} />
            <View style={{ flex: 1 }}>
              <Text style={styles.identityHint}>กุญแจกระเป๋าเก็บไว้บนเครื่องนี้เท่านั้น</Text>
              <Text style={styles.identityBackup}>สำรองวลีกู้คืนแล้ว · 14 มิ.ย. 2569</Text>
            </View>
          </View>
        </View>

        <GroupHeader label="SECURITY & KEYS" />
        <View style={styles.group}>
          <Row title="วลีกู้คืน 12 คำ" subtitle="ดูและตรวจสอบการสำรอง" onPress={notImplemented} />
          <Row
            title="ปลดล็อกด้วย Face ID"
            subtitle="ใช้ยืนยันทุกรายการที่ส่งออก"
            trailing={<Toggle value={faceId} onToggle={() => setFaceId((v) => !v)} />}
          />
          <Row title="เปลี่ยนรหัส PIN" subtitle="6 หลัก" onPress={notImplemented} />
          <Row title="อุปกรณ์ที่เชื่อมต่อ" subtitle="2 เครื่อง" onPress={notImplemented} isLast />
        </View>

        <GroupHeader label="LOAN ALERTS" />
        <View style={styles.group}>
          <Row
            title="แจ้งเตือนเมื่อ LTV สูงขึ้น"
            subtitle="แจ้งที่ 65% และ 72% เสมอ"
            trailing={<Toggle value={ltvAlert} onToggle={() => setLtvAlert((v) => !v)} />}
          />
          <Row title="แจ้งเตือนราคาบิตคอยน์" subtitle="ต่ำกว่า ฿4,000,000" onPress={notImplemented} />
          <Row title="บัญชีรับเงิน" subtitle="พร้อมเพย์ ···4821" onPress={notImplemented} isLast />
        </View>

        <GroupHeader label="PREFERENCES" />
        <View style={styles.group}>
          <Row
            title="สกุลเงินอ้างอิง"
            trailing={
              <View style={styles.trailingRow}>
                <Text style={styles.trailingValue}>THB</Text>
                <Text style={styles.chevron}>›</Text>
              </View>
            }
            onPress={notImplemented}
          />
          <Row
            title="ภาษา"
            trailing={
              <View style={styles.trailingRow}>
                <Text style={styles.trailingValue}>ไทย</Text>
                <Text style={styles.chevron}>›</Text>
              </View>
            }
            onPress={notImplemented}
          />
          <Row title="ช่วยเหลือและติดต่อ" onPress={notImplemented} isLast />
        </View>

        <View style={styles.noteBox}>
          <View style={styles.noteBar} />
          <Text style={styles.noteText}>
            การลบแอปไม่ได้ลบบิตคอยน์ของคุณ แต่ถ้าไม่มีวลีกู้คืน จะไม่มีใครกู้กระเป๋านี้คืนได้ รวมถึงทีมงาน mebit
          </Text>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerBrand}>powered by mapboss</Text>
          <Text style={styles.footerVersion}>mebit v1.0.0 · mapboss.co.th</Text>
        </View>
      </ScrollView>
      <BottomNav active="settings" onNavigate={onNavigate} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.mintTint },
  content: { padding: 20, paddingTop: 50, gap: 14 },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDot: { width: 4, height: 12, borderRadius: 999, backgroundColor: colors.greenLeaf },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase' },
  identityCard: { backgroundColor: colors.green900, borderRadius: radii.lg - 4, padding: 18 },
  identityTop: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 14,
    backgroundColor: 'rgba(255,255,255,0.1)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: { fontSize: 17, fontWeight: '600', color: colors.white },
  identityName: { fontSize: 16, fontWeight: '600', color: colors.white },
  identitySub: { fontSize: 12.5, color: 'rgba(255,255,255,0.62)', marginTop: 1 },
  chevronDark: { fontSize: 20, color: 'rgba(255,255,255,0.5)' },
  identityDivider: { height: 1, backgroundColor: 'rgba(255,255,255,0.14)', marginVertical: 14 },
  identityBottom: { flexDirection: 'row', gap: 10 },
  yellowBar: { width: 4, alignSelf: 'stretch', borderRadius: 999, backgroundColor: colors.yellow },
  identityHint: { fontSize: 12, color: 'rgba(255,255,255,0.62)' },
  identityBackup: { fontSize: 13, fontWeight: '500', color: '#00CC89', marginTop: 2 },
  groupHead: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4 },
  groupDot: { width: 4, height: 11, borderRadius: 999, backgroundColor: colors.greenLeaf },
  groupTitle: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.8, color: colors.teal, textTransform: 'uppercase' },
  group: { backgroundColor: colors.white, borderRadius: radii.md + 4, paddingHorizontal: 16 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 58 },
  rowBorder: { borderBottomWidth: 1, borderBottomColor: '#E4EAE9' },
  rowTitle: { fontSize: 15, fontWeight: '500', color: colors.ink },
  rowSubtitle: { fontSize: 12, color: colors.gray400, marginTop: 1 },
  chevron: { fontSize: 18, color: colors.gray300 },
  trailingRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  trailingValue: { fontSize: 14, color: colors.gray400 },
  toggleTrack: {
    width: 46,
    height: 28,
    borderRadius: 999,
    backgroundColor: colors.gray200,
    padding: 3,
    justifyContent: 'center',
  },
  toggleTrackOn: { backgroundColor: colors.teal },
  toggleThumb: { width: 22, height: 22, borderRadius: 999, backgroundColor: colors.white },
  toggleThumbOn: { alignSelf: 'flex-end' },
  noteBox: { flexDirection: 'row', gap: 10 },
  noteBar: { width: 4, alignSelf: 'stretch', borderRadius: 999, backgroundColor: colors.yellow },
  noteText: { flex: 1, fontSize: 12.5, lineHeight: 19, color: colors.gray700 },
  footer: { alignItems: 'center', gap: 6, paddingBottom: 6 },
  footerBrand: { fontSize: 12, color: colors.gray400, opacity: 0.7 },
  footerVersion: { fontSize: 11, color: '#A8A8A8' },
});
