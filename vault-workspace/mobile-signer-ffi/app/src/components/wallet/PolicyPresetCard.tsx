import React from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';
import { colors, walletFont } from '../../theme';

interface Props {
  label: string; // e.g. "2-of-3"
  sublabel: string; // e.g. "ใช้บ่อยที่สุด"
  selected?: boolean;
  onPress?: () => void;
}

// One tile in the M-of-N preset row on the Policy screen. Selected preset
// gets an inverted dark fill; the rest stay on white with a hairline border.
export function PolicyPresetCard({ label, sublabel, selected, onPress }: Props) {
  return (
    <Pressable
      onPress={onPress}
      style={[styles.card, selected ? styles.selected : styles.unselected]}
    >
      <Text style={[styles.label, selected ? styles.labelSelected : styles.labelUnselected]}>{label}</Text>
      <Text style={[styles.sublabel, selected ? styles.sublabelSelected : styles.sublabelUnselected]}>
        {sublabel}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    width: 113,
    height: 64,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    borderWidth: 1,
  },
  selected: {
    backgroundColor: colors.green900,
    borderColor: colors.green900,
  },
  unselected: {
    backgroundColor: colors.white,
    borderColor: colors.gray100,
  },
  label: {
    fontFamily: walletFont,
    fontSize: 15.5,
    fontWeight: '700',
  },
  labelSelected: { color: colors.white },
  labelUnselected: { color: colors.green900 },
  sublabel: {
    fontFamily: walletFont,
    fontSize: 9.5,
    fontWeight: '400',
  },
  sublabelSelected: { color: 'rgba(255,255,255,0.66)' },
  sublabelUnselected: { color: colors.gray400 },
});
