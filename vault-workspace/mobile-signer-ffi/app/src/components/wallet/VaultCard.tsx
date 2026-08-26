import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, shadow, walletFont, walletRadii } from '../../theme';

interface Props {
  kind: string; // e.g. "SINGLE-SIG", "2-OF-3"
  title: string;
  subtitle: string;
  amountBtc: string;
  onPress?: () => void;
}

// Vault summary card on the Vaults list screen — accent bar, policy-kind
// eyebrow, name, key-holder subtitle, and balance.
export function VaultCard({ kind, title, subtitle, amountBtc, onPress }: Props) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}>
      <View style={styles.accentBar} />
      <View style={styles.body}>
        <Text style={styles.kind}>{kind}</Text>
        <Text style={styles.title}>{title}</Text>
        <View style={styles.row}>
          <Text style={styles.subtitle} numberOfLines={1}>
            {subtitle}
          </Text>
          <Text style={styles.amount}>{amountBtc}</Text>
        </View>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    backgroundColor: colors.white,
    borderRadius: walletRadii.cardLg,
    borderWidth: 1,
    borderColor: colors.gray100,
    padding: 18,
    gap: 14,
    ...shadow.card,
    shadowOpacity: 0.14,
  },
  accentBar: {
    width: 4,
    borderRadius: 2,
    backgroundColor: colors.teal,
  },
  body: { flex: 1, gap: 6 },
  kind: {
    fontFamily: walletFont,
    fontSize: 10,
    fontWeight: '600',
    letterSpacing: 0.4,
    color: colors.gray400,
  },
  title: {
    fontFamily: walletFont,
    fontSize: 17.5,
    fontWeight: '600',
    color: colors.green900,
  },
  row: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between', marginTop: 8 },
  subtitle: {
    flex: 1,
    fontFamily: walletFont,
    fontSize: 11.5,
    fontWeight: '300',
    color: colors.gray400,
  },
  amount: {
    fontFamily: walletFont,
    fontSize: 18,
    fontWeight: '700',
    color: colors.green900,
  },
});
