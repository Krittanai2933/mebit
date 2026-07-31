import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';
import { PrimaryButton } from '../components/PrimaryButton';

// Combined splash + onboarding, per the revised design (docs/design-notes.md)
// — there is no separate splash screen anymore, just this one.
export function OnboardingScreen({
  onCreate,
  onImport,
}: {
  onCreate: () => void;
  onImport: () => void;
}) {
  return (
    <View style={styles.container}>
      <View style={styles.logoRow}>
        <Text style={styles.logo}>mebit</Text>
        <View style={styles.dot} />
      </View>

      <View style={styles.hero}>
        <View style={styles.headlineRow}>
          <View style={styles.yellowBar} />
          <Text style={styles.headline}>
            ถือกุญแจเอง{'\n'}
            <Text style={styles.headlineAccent}>ใช้มูลค่าได้</Text>
            {'\n'}ไม่ต้องขาย
          </Text>
        </View>
        <Text style={styles.tagline}>
          กระเป๋าบิตคอยน์ที่คุณเก็บกุญแจไว้เอง พร้อมวงเงินบาทที่ใช้บิตคอยน์ของคุณค้ำประกัน — ไม่ต้องขายเพื่อใช้เงิน
        </Text>
      </View>

      <View style={styles.actions}>
        <PrimaryButton label="สร้างกระเป๋าใหม่" onPress={onCreate} />
        <PrimaryButton label="นำเข้ากระเป๋าเดิม" onPress={onImport} variant="outline" />
        <Text style={styles.poweredBy}>powered by mapboss</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 24, paddingTop: 24, justifyContent: 'space-between' },
  logoRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 12 },
  logo: { fontSize: 22, fontWeight: '600', color: colors.teal, letterSpacing: -0.3 },
  dot: { width: 7, height: 7, borderRadius: 999, backgroundColor: colors.yellow, marginTop: 8 },
  hero: { flex: 1, justifyContent: 'center', gap: 20 },
  headlineRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  yellowBar: { width: 5, height: 96, borderRadius: 999, backgroundColor: colors.yellow, marginTop: 6 },
  headline: { fontSize: 36, lineHeight: 42, fontWeight: '400', color: colors.ink, letterSpacing: -0.3, flexShrink: 1 },
  headlineAccent: { color: colors.teal },
  tagline: { fontSize: 15, lineHeight: 23, fontWeight: '300', color: colors.gray700 },
  actions: { gap: 10, paddingBottom: 14 },
  poweredBy: { textAlign: 'center', color: colors.gray400, fontSize: 11, letterSpacing: 0.4, opacity: 0.7, marginTop: 4 },
});
