import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, walletFont } from '../../theme';

interface Props {
  eyebrow: string;
  title: string;
  subtitle?: string;
}

// The eyebrow-label / H1 / body-subtitle stack repeated at the top of every
// wallet-flow screen (Add Key, Pair Device, Hardware Wallet, ...).
export function ScreenHeader({ eyebrow, title, subtitle }: Props) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.eyebrow}>{eyebrow}</Text>
      <Text style={styles.title}>{title}</Text>
      {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: 8 },
  eyebrow: {
    fontFamily: walletFont,
    fontSize: 10.5,
    fontWeight: '600',
    color: colors.teal,
  },
  title: {
    fontFamily: walletFont,
    fontSize: 28,
    fontWeight: '500',
    color: colors.green900,
    lineHeight: 33,
  },
  subtitle: {
    fontFamily: walletFont,
    fontSize: 13.5,
    fontWeight: '300',
    color: colors.gray700,
    lineHeight: 19.5,
  },
});
