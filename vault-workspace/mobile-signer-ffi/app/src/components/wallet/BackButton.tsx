import React from 'react';
import { Pressable, StyleSheet } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { colors, walletRadii } from '../../theme';

// The circular back chevron in the top-left of every wallet-flow screen.
export function BackButton({ onPress }: { onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.button, pressed && { opacity: 0.6 }]}>
      <Svg width={12} height={20} viewBox="0 0 12 20">
        <Path
          d="M9.8 1.7 2.1 10l7.7 8.3"
          fill="none"
          stroke={colors.green900}
          strokeWidth={2.2}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </Svg>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    width: 36,
    height: 36,
    borderRadius: walletRadii.pill,
    borderWidth: 1,
    borderColor: colors.gray100,
    backgroundColor: colors.white,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
