import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
import { colors, walletFont, walletRadii } from '../../theme';

interface Props {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary';
  disabled?: boolean;
  loading?: boolean;
}

// Primary/Secondary button pair from the wallet-first flow (Pair Device,
// Confirm Key, Keyring, Policy, ...). Kept separate from the older
// PrimaryButton so the lending screens' existing look/API doesn't shift.
export function WalletButton({ label, onPress, variant = 'primary', disabled, loading }: Props) {
  const isPrimary = variant === 'primary';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || loading}
      style={({ pressed }) => [
        styles.base,
        isPrimary ? styles.primary : styles.secondary,
        (pressed || disabled) && { opacity: 0.7 },
      ]}
    >
      {loading ? (
        <ActivityIndicator color={isPrimary ? colors.white : colors.teal} />
      ) : (
        <Text style={[styles.label, isPrimary ? styles.labelPrimary : styles.labelSecondary]}>{label}</Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    height: 54,
    borderRadius: walletRadii.chip + 4,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 20,
  },
  primary: {
    backgroundColor: colors.teal,
  },
  secondary: {
    backgroundColor: colors.white,
    borderWidth: 1.5,
    borderColor: colors.teal,
  },
  label: {
    fontFamily: walletFont,
    fontSize: 15.5,
    fontWeight: '600',
  },
  labelPrimary: {
    color: colors.white,
  },
  labelSecondary: {
    color: colors.teal,
  },
});
