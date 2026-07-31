import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import { colors, risk } from '../theme';
import { RiskZone, formatPct, riskLabel } from '../mockVault';

// Port of the "Risk A — arc to liquidation" visualization from the original
// mebit design (docs/design-notes.md): the arc's full sweep IS the 80%
// liquidation threshold, so distance-to-liquidation reads as an angle, not a
// number you have to do math on.

const ARC_LENGTH = 301.6; // path length of the M19,120 A96,96 0 0 1 211,120 arc

interface Props {
  ltv: number;
  zone: RiskZone;
  size?: number;
}

export function RiskArc({ ltv, zone, size = 230 }: Props) {
  const color = risk[zone];
  const filled = Math.min(ltv / 0.8, 1) * ARC_LENGTH;
  return (
    <View style={{ width: size, alignItems: 'center' }}>
      <Svg viewBox="0 0 230 132" width={size} height={(size * 132) / 230}>
        <Path
          d="M19 120 A96 96 0 0 1 211 120"
          fill="none"
          stroke={colors.gray100}
          strokeWidth={15}
          strokeLinecap="round"
        />
        <Path
          d="M19 120 A96 96 0 0 1 211 120"
          fill="none"
          stroke={color}
          strokeWidth={15}
          strokeLinecap="round"
          strokeDasharray={`${filled} ${ARC_LENGTH}`}
        />
      </Svg>
      <View style={styles.labelWrap}>
        <Text style={[styles.pct, { color }]}>{formatPct(ltv, 0)}</Text>
        <Text style={[styles.status, { color }]}>{riskLabel(zone)}</Text>
      </View>
      <View style={styles.axis}>
        <Text style={styles.axisLabel}>0%</Text>
        <Text style={styles.axisLabel}>บังคับขาย 80%</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  labelWrap: { marginTop: -34, alignItems: 'center' },
  pct: { fontSize: 38, fontWeight: '700', letterSpacing: -0.5 },
  status: { fontSize: 13, fontWeight: '600', marginTop: 2 },
  axis: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: 4 },
  axisLabel: { fontSize: 10, color: colors.gray300 },
});
