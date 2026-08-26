import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { colors, walletFont, walletRadii } from '../../theme';

interface Props {
  title: string;
  subtitle: string;
  selected: boolean;
  onPress?: () => void;
}

// Checkbox row on the "Choose Keys" screen. The Penpot mock only shows the
// checked state (the demo copy is "2/2 selected"), so the unchecked visuals
// here are a judgment call: plain white card, unfilled checkbox outline.
export function SelectableKeyRow({ title, subtitle, selected, onPress }: Props) {
  return (
    <Pressable onPress={onPress} style={[styles.card, selected ? styles.selected : styles.unselected]}>
      <View style={[styles.checkbox, selected ? styles.checkboxOn : styles.checkboxOff]}>
        {selected && (
          <Svg width={14} height={14} viewBox="0 0 14 14">
            <Path
              d="M2.5 7.2 5.6 10.3 11.5 3.7"
              fill="none"
              stroke={colors.white}
              strokeWidth={2.4}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </Svg>
        )}
      </View>
      <View style={styles.text}>
        <Text style={styles.title} numberOfLines={1}>
          {title}
        </Text>
        <Text style={styles.subtitle} numberOfLines={1}>
          {subtitle}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 64,
    paddingHorizontal: 16,
    borderRadius: walletRadii.card,
    borderWidth: 1.5,
    gap: 14,
  },
  selected: {
    backgroundColor: colors.mintTint,
    borderColor: colors.teal,
  },
  unselected: {
    backgroundColor: colors.white,
    borderColor: colors.gray200,
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: walletRadii.checkbox,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxOn: {
    backgroundColor: colors.teal,
    borderColor: colors.teal,
  },
  checkboxOff: {
    backgroundColor: 'transparent',
    borderColor: colors.gray300,
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
