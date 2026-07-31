import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, radii } from '../theme';
import { PrimaryButton } from '../components/PrimaryButton';
import { MOCK_SEED_WORDS } from '../mockVault';

export function SeedBackupScreen({ onNext }: { onNext: () => void }) {
  const [confirmed, setConfirmed] = useState(false);

  return (
    <View style={styles.container}>
      <View style={styles.eyebrowRow}>
        <View style={styles.eyebrowDot} />
        <Text style={styles.eyebrow}>STEP 2 · BACKUP</Text>
      </View>
      <Text style={styles.title}>จดวลีกู้คืน 12 คำ</Text>
      <Text style={styles.body}>
        วลีนี้คือกุญแจของกระเป๋า ใครก็ตามที่มีวลีนี้ถือว่าเป็นเจ้าของบิตคอยน์ทั้งหมด กรุณาจดลงกระดาษ
        อย่าถ่ายภาพ และอย่าเก็บไว้ในคลาวด์
      </Text>

      <ScrollView style={styles.scroll} contentContainerStyle={{ paddingBottom: 8 }}>
        <View style={styles.grid}>
          {MOCK_SEED_WORDS.map((word, i) => (
            <View key={word} style={styles.wordChip}>
              <Text style={styles.wordIndex}>{i + 1}</Text>
              <Text style={styles.word}>{word}</Text>
            </View>
          ))}
        </View>

        <View style={styles.warnBox}>
          <View style={styles.warnBar} />
          <Text style={styles.warnText}>ทีมงานจะไม่ขอวลีกู้คืนจากคุณไม่ว่ากรณีใด หากมีคนขอ นั่นคือการหลอกลวง</Text>
        </View>
      </ScrollView>

      <Pressable style={styles.checkRow} onPress={() => setConfirmed((c) => !c)}>
        <View style={[styles.checkbox, confirmed && styles.checkboxChecked]}>
          {confirmed && <Text style={styles.checkMark}>✓</Text>}
        </View>
        <Text style={styles.checkLabel}>จดครบแล้วและเก็บไว้ในที่ปลอดภัย</Text>
      </Pressable>

      <PrimaryButton label="ยืนยัน" onPress={onNext} disabled={!confirmed} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 24, paddingTop: 60, gap: 10 },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDot: { width: 4, height: 12, borderRadius: 999, backgroundColor: colors.greenLeaf },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1.2, color: colors.teal, textTransform: 'uppercase' },
  title: { fontSize: 24, fontWeight: '500', color: colors.ink },
  body: { fontSize: 13, lineHeight: 20, fontWeight: '300', color: colors.gray700 },
  scroll: { flexGrow: 0, marginTop: 4 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, backgroundColor: colors.white, borderRadius: radii.md, padding: 14 },
  wordChip: { flexDirection: 'row', alignItems: 'baseline', gap: 6, width: '46%' },
  wordIndex: { fontSize: 11, color: colors.gray300, width: 16, textAlign: 'right' },
  word: { fontSize: 14, fontWeight: '500', color: colors.ink },
  warnBox: {
    marginTop: 12,
    flexDirection: 'row',
    gap: 10,
    backgroundColor: 'rgba(252,195,48,0.14)',
    borderRadius: radii.sm + 4,
    padding: 12,
  },
  warnBar: { width: 4, alignSelf: 'stretch', borderRadius: 999, backgroundColor: colors.yellow },
  warnText: { flex: 1, fontSize: 12.5, lineHeight: 18, color: colors.gray700 },
  checkRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 6 },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: colors.gray300,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxChecked: { backgroundColor: colors.teal, borderColor: colors.teal },
  checkMark: { color: colors.white, fontSize: 13, fontWeight: '700' },
  checkLabel: { fontSize: 13, color: colors.gray700, flex: 1 },
});
