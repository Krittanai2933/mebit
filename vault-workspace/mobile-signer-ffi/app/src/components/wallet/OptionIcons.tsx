import React from 'react';
import { View } from 'react-native';
import Svg, { Ellipse, Path, Rect } from 'react-native-svg';
import { colors } from '../../theme';

// Small glyphs for OptionCard's icon tile, ported from the Penpot "Option
// icon" vectors on the Add Key screen.

export function PhoneIcon() {
  return (
    <Svg width={15} height={23} viewBox="0 0 15 23">
      <Path
        d="M2 2a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2Z"
        fill="none"
        stroke={colors.teal}
        strokeWidth={2}
      />
    </Svg>
  );
}

export function OtherDeviceIcon() {
  const dots = [
    { x: 12, y: 12, color: colors.teal },
    { x: 25, y: 12, color: colors.mintAccent },
    { x: 12, y: 25, color: colors.mintAccent },
    { x: 25, y: 25, color: colors.teal },
  ];
  return (
    <View style={{ width: 32, height: 32 }}>
      <Svg width={32} height={32} viewBox="0 0 32 32">
        {dots.map((d, i) => (
          <Path
            key={i}
            d={`M${d.x - 3.5} ${d.y - 3.5}h7v7h-7Z`}
            fill={d.color}
          />
        ))}
      </Svg>
    </View>
  );
}

export function HardwareWalletIcon() {
  return (
    <Svg width={33} height={23} viewBox="0 0 33 23">
      <Rect x={1} y={1} width={22} height={14} rx={3} fill="none" stroke={colors.teal} strokeWidth={2} />
      <Ellipse cx={19.5} cy={8} rx={2.5} ry={2.5} fill={colors.yellow} />
    </Svg>
  );
}
