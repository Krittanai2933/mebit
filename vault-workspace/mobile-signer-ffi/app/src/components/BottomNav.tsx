import React from 'react';
import { StyleSheet, Text, View, Pressable } from 'react-native';
import { colors } from '../theme';
import { Screen } from '../types';

const TABS: { key: Screen; label: string; icon: string }[] = [
  { key: 'home', label: 'WALLET', icon: '◈' },
  { key: 'borrow', label: 'BORROW', icon: '฿' },
  { key: 'activity', label: 'ACTIVITY', icon: '≡' },
  { key: 'portfolio', label: 'PORTFOLIO', icon: '◔' },
  { key: 'settings', label: 'SETTINGS', icon: '⚙' },
];

// Only rendered on the four tab-root screens (home, activity, portfolio,
// settings) — stack screens (receive, borrow, loan, repay, ...) use a back
// button instead, per docs/design-notes.md.
export function BottomNav({ active, onNavigate }: { active: Screen; onNavigate: (s: Screen) => void }) {
  return (
    <View style={styles.bar}>
      {TABS.map((tab) => {
        const isActive = active === tab.key;
        return (
          <Pressable key={tab.key} style={styles.tab} onPress={() => onNavigate(tab.key)}>
            <Text style={[styles.icon, isActive && styles.iconActive]}>{tab.icon}</Text>
            <Text style={[styles.label, isActive && styles.labelActive]}>{tab.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: colors.gray200,
    backgroundColor: colors.white,
    paddingTop: 8,
    paddingBottom: 20,
  },
  tab: { flex: 1, alignItems: 'center', gap: 4 },
  icon: { fontSize: 18, color: colors.gray400 },
  iconActive: { color: colors.teal },
  label: { fontSize: 9.5, fontWeight: '600', letterSpacing: 0.4, color: colors.gray400 },
  labelActive: { color: colors.teal },
});
