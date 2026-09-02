import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { colors, walletFont } from '../../theme';
import { BackButton } from '../../components/wallet/BackButton';
import { Badge } from '../../components/wallet/Badge';
import { KeyCard } from '../../components/wallet/KeyCard';
import { OptionCard } from '../../components/wallet/OptionCard';
import { HardwareWalletIcon, OtherDeviceIcon, PhoneIcon } from '../../components/wallet/OptionIcons';
import { PolicyPresetCard } from '../../components/wallet/PolicyPresetCard';
import { ScreenHeader } from '../../components/wallet/ScreenHeader';
import { SelectableKeyRow } from '../../components/wallet/SelectableKeyRow';
import { VaultCard } from '../../components/wallet/VaultCard';
import { WalletButton } from '../../components/wallet/WalletButton';

// Visual QA page for the wallet-first component set ported from the Penpot
// design (see mebit CLAUDE.md — wallet-first pivot, 2026-08-25). Not part of
// the real app flow; wired into App.tsx behind a dev-only screen key so all
// components/states can be eyeballed against the Penpot mock in one place.
export function ComponentPlaygroundScreen({ onBack }: { onBack: () => void }) {
  const [selectedPreset, setSelectedPreset] = useState('2-of-3');
  const [selectedKeys, setSelectedKeys] = useState<Record<string, boolean>>({
    iphone: true,
    ledger: true,
    ipad: false,
  });

  function toggleKey(key: string) {
    setSelectedKeys((s) => ({ ...s, [key]: !s[key] }));
  }

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <BackButton onPress={onBack} />

      <ScreenHeader
        eyebrow="COMPONENT PLAYGROUND"
        title="ส่วนประกอบ UI"
        subtitle="ทดสอบทุกส่วนประกอบที่ดึงมาจาก Penpot ในที่เดียว"
      />

      <Section title="Buttons">
        <WalletButton label="สแกนแล้ว ดำเนินการต่อ" onPress={() => {}} variant="primary" />
        <WalletButton label="รอเชื่อมต่ออุปกรณ์" onPress={() => {}} variant="secondary" />
        <WalletButton label="กำลังโหลด" onPress={() => {}} variant="primary" loading />
        <WalletButton label="ปิดใช้งาน" onPress={() => {}} variant="primary" disabled />
      </Section>

      <Section title="Badge">
        <View style={styles.row}>
          <Badge label="Ledger" />
          <Badge label="Trezor" />
          <Badge label="ยืนยันแล้ว" />
        </View>
      </Section>

      <Section title="Option card">
        <OptionCard
          icon={<PhoneIcon />}
          title="mebit บนเครื่องนี้"
          subtitle="สร้าง seed ใหม่เก็บใน secure enclave ของเครื่อง"
          onPress={() => {}}
        />
        <OptionCard
          icon={<OtherDeviceIcon />}
          title="mebit บนเครื่องอื่น"
          subtitle="สแกน QR จับคู่ แล้วดึง xpub จากเครื่องนั้นมา"
          onPress={() => {}}
        />
        <OptionCard
          icon={<HardwareWalletIcon />}
          title="Hardware wallet"
          subtitle="อุปกรณ์แยกที่เก็บกุญแจไว้ออฟไลน์"
          badges={['Ledger', 'Trezor', 'Coldcard', 'BitBox02', 'Jade']}
          onPress={() => {}}
        />
      </Section>

      <Section title="Key card">
        <KeyCard monogram="iOS" title="iPhone ของฉัน" subtitle="mebit บนเครื่องนี้ · 3a9f 21c4" status="ยืนยันแล้ว" />
        <KeyCard monogram="HW" title="Ledger เล่มดำ" subtitle="Ledger Nano S+ · 7f2c a91b" status="ยืนยันแล้ว" />
        <KeyCard monogram="?" title="iPad ของฉัน" subtitle="ยังไม่ได้จับคู่" />
      </Section>

      <Section title="Vault card">
        <VaultCard kind="SINGLE-SIG" title="กระเป๋าใช้จ่าย" subtitle="กุญแจเดียว · iPhone ของฉัน" amountBtc="0.0312" />
        <VaultCard kind="2-OF-3" title="Vault ครอบครัว" subtitle="3 กุญแจในคลัง · ยืนยันแล้ว" amountBtc="1.1232" />
      </Section>

      <Section title="Policy preset">
        <View style={styles.row}>
          {['2-of-2', '2-of-3', '3-of-5'].map((p) => (
            <PolicyPresetCard
              key={p}
              label={p}
              sublabel={p === '2-of-3' ? 'ใช้บ่อยที่สุด' : p === '2-of-2' ? 'ปลอดภัยพื้นฐาน' : 'กระจายมากขึ้น'}
              selected={selectedPreset === p}
              onPress={() => setSelectedPreset(p)}
            />
          ))}
        </View>
      </Section>

      <Section title="Selectable key row">
        <SelectableKeyRow
          title="iPhone ของฉัน"
          subtitle="mebit บนเครื่องนี้ · 3a9f 21c4"
          selected={!!selectedKeys.iphone}
          onPress={() => toggleKey('iphone')}
        />
        <SelectableKeyRow
          title="Ledger เล่มดำ"
          subtitle="Ledger Nano S+ · 7f2c a91b"
          selected={!!selectedKeys.ledger}
          onPress={() => toggleKey('ledger')}
        />
        <SelectableKeyRow
          title="iPad ของฉัน"
          subtitle="ยังไม่ได้จับคู่"
          selected={!!selectedKeys.ipad}
          onPress={() => toggleKey('ipad')}
        />
      </Section>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.sectionBody}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.mintTint },
  content: { padding: 20, paddingTop: 60, gap: 24, paddingBottom: 60 },
  section: { gap: 12 },
  sectionTitle: {
    fontFamily: walletFont,
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.6,
    color: colors.gray400,
    textTransform: 'uppercase',
  },
  sectionBody: { gap: 10 },
  row: { flexDirection: 'row', gap: 10, flexWrap: 'wrap' },
});
