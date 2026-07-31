import React, { PropsWithChildren } from 'react';
import { StyleSheet, View, ViewStyle } from 'react-native';
import { colors, radii, shadow } from '../theme';

export function Card({
  children,
  style,
  dark = false,
}: PropsWithChildren<{ style?: ViewStyle; dark?: boolean }>) {
  return <View style={[styles.card, dark ? styles.dark : styles.light, style]}>{children}</View>;
}

const styles = StyleSheet.create({
  card: {
    borderRadius: radii.lg,
    padding: 18,
    ...shadow.soft,
  },
  light: {
    backgroundColor: colors.white,
  },
  dark: {
    backgroundColor: colors.green900,
  },
});
