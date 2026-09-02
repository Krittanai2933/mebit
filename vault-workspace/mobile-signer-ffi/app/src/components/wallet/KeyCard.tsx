import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, walletFont, walletRadii } from '../../theme';
import { Badge } from './Badge';

interface Props {
  monogram: string;
  title: string;
  subtitle: string;
  status?: string;
  onPress?: () => void;
}

// Flat row for a single key in the Keyring list — monogram tile, title,
// fingerprint/subtitle, and a trailing status pill (e.g. "ยืนยันแล้ว").
export function KeyCard({ monogram, title, subtitle, status, onPress }: Props) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}>
      <View style={styles.monogram}>
        <Text style={styles.monogramText}>{monogram}</Text>
      </View>
      <View style={styles.text}>
        <Text style={styles.title} numberOfLines={1}>
          {title}
        </Text>
        <Text style={styles.subtitle} numberOfLines={1}>
          {subtitle}
        </Text>
      </View>
      {status ? <Badge label={status} /> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.white,
    borderRadius: walletRadii.card,
    borderWidth: 1,
    borderColor: colors.gray100,
    padding: 12,
    gap: 12,
  },
  monogram: {
    width: 38,
    height: 38,
    borderRadius: walletRadii.chip,
    backgroundColor: colors.mintTint,
    alignItems: 'center',
    justifyContent: 'center',
  },
  monogramText: {
    fontFamily: walletFont,
    fontSize: 10.5,
    fontWeight: '700',
    color: colors.teal,
  },
  text: { flex: 1, gap: 2 },
  title: {
    fontFamily: walletFont,
    fontSize: 14.5,
    fontWeight: '600',
    color: colors.green900,
  },
  subtitle: {
    fontFamily: walletFont,
    fontSize: 11,
    fontWeight: '300',
    color: colors.gray400,
  },
});
