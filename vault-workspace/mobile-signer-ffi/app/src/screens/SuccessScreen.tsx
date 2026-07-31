import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme';
import { PrimaryButton } from '../components/PrimaryButton';
import { SuccessInfo } from '../types';
import { mockTxid } from '../mockVault';

export function SuccessScreen({ info, onDone }: { info: SuccessInfo; onDone: () => void }) {
  const txid = mockTxid();
  return (
    <View style={styles.container}>
      <View style={styles.center}>
        <View style={styles.checkCircle}>
          <Text style={styles.check}>✓</Text>
        </View>
        <Text style={styles.title}>{info.title}</Text>
        <Text style={styles.detail}>{info.detail}</Text>

        {info.breakdown && info.breakdown.length > 0 && (
          <View style={styles.breakdown}>
            {info.breakdown.map((row) => (
              <View key={row.label} style={styles.breakdownRow}>
                <Text style={styles.breakdownLabel}>{row.label}</Text>
                <Text style={styles.breakdownValue}>{row.value}</Text>
              </View>
            ))}
          </View>
        )}

        <Text style={styles.txid} numberOfLines={1}>
          txid: {txid.slice(0, 16)}…
        </Text>
      </View>
      <PrimaryButton label="กลับหน้าแรก" onPress={onDone} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.green900,
    padding: 28,
    paddingTop: 140,
    paddingBottom: 48,
    justifyContent: 'space-between',
  },
  center: { alignItems: 'center', width: '100%' },
  checkCircle: {
    width: 84,
    height: 84,
    borderRadius: 999,
    backgroundColor: colors.green,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 22,
  },
  check: { fontSize: 38, color: colors.white, fontWeight: '700' },
  title: { fontSize: 24, fontWeight: '500', color: colors.white, textAlign: 'center' },
  detail: { fontSize: 14, color: 'rgba(255,255,255,0.72)', marginTop: 10, textAlign: 'center' },
  breakdown: {
    width: '100%',
    marginTop: 20,
    borderTopWidth: 1,
    borderBottomWidth: 1,
    borderColor: 'rgba(255,255,255,0.14)',
    paddingVertical: 16,
    gap: 12,
  },
  breakdownRow: { flexDirection: 'row', justifyContent: 'space-between' },
  breakdownLabel: { fontSize: 13.5, color: 'rgba(255,255,255,0.6)' },
  breakdownValue: { fontSize: 15, fontWeight: '600', color: colors.white },
  txid: { fontSize: 11, color: 'rgba(255,255,255,0.4)', marginTop: 18 },
});
