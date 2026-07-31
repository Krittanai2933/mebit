import React, { useState } from 'react';
import { Share, StyleSheet, Text, View } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { colors, radii } from '../theme';
import { Card } from '../components/Card';
import { PrimaryButton } from '../components/PrimaryButton';
import { mockAddress } from '../mockVault';
import { Screen } from '../types';

export function ReceiveScreen({ onBack, onNavigate }: { onBack: () => void; onNavigate: (s: Screen) => void }) {
  const [copied, setCopied] = useState(false);
  const address = mockAddress();

  async function copy() {
    await Clipboard.setStringAsync(address);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <PrimaryButton label="←" onPress={onBack} variant="ghost" />
        <Text style={styles.eyebrow}>RECEIVE BITCOIN</Text>
      </View>

      <Card style={styles.card}>
        <View style={styles.qr}>
          <Text style={styles.qrIcon}>▦</Text>
          <Text style={styles.qrText}>QR PLACEHOLDER</Text>
        </View>
        <View style={{ alignItems: 'center' }}>
          <Text style={styles.addressLabel}>BITCOIN ADDRESS</Text>
          <Text style={styles.address}>{address}</Text>
        </View>
        <View style={styles.actionsRow}>
          <PrimaryButton label={copied ? 'คัดลอกแล้ว ✓' : 'คัดลอก'} onPress={copy} />
          <PrimaryButton label="แชร์" onPress={() => Share.share({ message: address })} variant="outline" />
        </View>
      </Card>

      <View style={styles.noteBox}>
        <View style={styles.noteBar} />
        <Text style={styles.note}>
          ที่อยู่นี้แนะนำให้ใช้ครั้งเดียว ระบบจะสร้างที่อยู่ใหม่ทุกครั้งที่รับเพื่อความเป็นส่วนตัว
          บิตคอยน์ที่ส่งเข้ามาจะเข้ากระเป๋าที่คุณถือกุญแจเอง
        </Text>
      </View>

      <View style={{ flex: 1 }} />
      <PrimaryButton label="รับเสร็จแล้ว → ไปกู้เงินบาท" onPress={() => onNavigate('borrow')} variant="outline" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 20, paddingTop: 60, gap: 14 },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1, color: colors.teal, textTransform: 'uppercase' },
  card: { alignItems: 'center', gap: 16 },
  qr: {
    width: 180,
    height: 180,
    borderRadius: radii.md,
    backgroundColor: colors.mintTint,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.gray300,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  qrIcon: { fontSize: 40, color: colors.teal },
  qrText: { fontSize: 10.5, fontWeight: '600', letterSpacing: 1, color: colors.gray400 },
  addressLabel: { fontSize: 10.5, fontWeight: '700', letterSpacing: 0.8, color: colors.gray400, textTransform: 'uppercase' },
  address: { fontSize: 14, lineHeight: 21, fontWeight: '500', color: colors.ink, marginTop: 6, maxWidth: 260, textAlign: 'center' },
  actionsRow: { flexDirection: 'row', gap: 10, width: '100%' },
  noteBox: { flexDirection: 'row', gap: 10 },
  noteBar: { width: 4, alignSelf: 'stretch', borderRadius: 999, backgroundColor: colors.greenLeaf },
  note: { flex: 1, fontSize: 13, lineHeight: 19, color: colors.gray700 },
});
