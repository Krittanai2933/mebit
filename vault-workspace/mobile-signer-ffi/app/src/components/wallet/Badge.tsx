import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, walletFont, walletRadii } from '../../theme';

// Small pill label used on Option/Key cards for vendor names, verification
// status, etc. — see Penpot "Badge" (Add Key / Keyring / Choose Keys).
export function Badge({ label }: { label: string }) {
  return (
    <View style={styles.badge}>
      <Text style={styles.label} numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    height: 24,
    paddingHorizontal: 7,
    borderRadius: walletRadii.pill,
    borderWidth: 1,
    borderColor: colors.gray100,
    backgroundColor: colors.mintTint,
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'flex-start',
  },
  label: {
    fontFamily: walletFont,
    fontSize: 10.5,
    fontWeight: '500',
    color: colors.gray700,
  },
});
