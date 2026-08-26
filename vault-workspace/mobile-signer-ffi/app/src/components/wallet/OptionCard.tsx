import React, { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, shadow, walletFont, walletRadii } from '../../theme';
import { Badge } from './Badge';

interface Props {
  icon: ReactNode;
  title: string;
  subtitle: string;
  badges?: string[];
  onPress?: () => void;
}

// Tappable option row with a leading icon tile — used on Add Key ("mebit on
// this device" / "mebit on another device" / "Hardware wallet").
export function OptionCard({ icon, title, subtitle, badges, onPress }: Props) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.card, pressed && { opacity: 0.85 }]}>
      <View style={styles.iconTile}>{icon}</View>
      <View style={styles.text}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.subtitle}>{subtitle}</Text>
        {badges && badges.length > 0 && (
          <View style={styles.badgeRow}>
            {badges.map((b) => (
              <Badge key={b} label={b} />
            ))}
          </View>
        )}
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
    padding: 15,
    gap: 12,
    ...shadow.card,
    shadowOpacity: 0.14,
  },
  iconTile: {
    width: 44,
    height: 44,
    borderRadius: walletRadii.icon,
    backgroundColor: colors.mintTint,
    borderWidth: 1,
    borderColor: colors.gray100,
    alignItems: 'center',
    justifyContent: 'center',
  },
  text: { flex: 1, gap: 2 },
  title: {
    fontFamily: walletFont,
    fontSize: 15.5,
    fontWeight: '600',
    color: colors.green900,
  },
  subtitle: {
    fontFamily: walletFont,
    fontSize: 12,
    fontWeight: '300',
    color: colors.gray700,
  },
  badgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 6 },
});
