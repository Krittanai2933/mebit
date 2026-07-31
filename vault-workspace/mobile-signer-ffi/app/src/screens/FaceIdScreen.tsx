import React, { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';
import { PrimaryButton } from '../components/PrimaryButton';

export function FaceIdScreen({ onDone }: { onDone: () => void }) {
  const [enrolling, setEnrolling] = useState(false);

  function enroll() {
    setEnrolling(true);
    setTimeout(onDone, 700); // mock biometric enrollment delay
  }

  return (
    <View style={styles.container}>
      <View style={styles.eyebrowRow}>
        <View style={styles.eyebrowDot} />
        <Text style={styles.eyebrow}>STEP 3 · SECURITY</Text>
      </View>
      <View style={styles.center}>
        <View style={styles.iconCircle}>
          <Text style={styles.icon}>🛡️</Text>
        </View>
        <Text style={styles.title}>เปิดใช้ Face ID{'\n'}เพื่อเข้าแอป</Text>
        <Text style={styles.body}>
          ใช้ Face ID เพื่อปลดล็อกแอปและยืนยันรายการ กุญแจของคุณยังถูกเก็บไว้บนเครื่องนี้เท่านั้น
        </Text>
      </View>
      <View style={styles.actions}>
        <PrimaryButton label="เปิดใช้งาน" onPress={enroll} loading={enrolling} />
        <PrimaryButton label="ข้ามไปก่อน" onPress={onDone} variant="ghost" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.mintTint, padding: 24, paddingTop: 60, justifyContent: 'space-between' },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  eyebrowDot: { width: 4, height: 12, borderRadius: 999, backgroundColor: colors.greenLeaf },
  eyebrow: { fontSize: 11, fontWeight: '700', letterSpacing: 1.2, color: colors.teal, textTransform: 'uppercase' },
  center: { flex: 1, alignItems: 'flex-start', justifyContent: 'center', gap: 20 },
  iconCircle: { width: 76, height: 76, alignItems: 'center', justifyContent: 'center' },
  icon: { fontSize: 44 },
  title: { fontSize: 27, fontWeight: '500', lineHeight: 33, color: colors.ink },
  body: { fontSize: 15, lineHeight: 22, fontWeight: '300', color: colors.gray700 },
  actions: { gap: 10, paddingBottom: 14 },
});
