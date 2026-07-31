import React, { useState } from 'react';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaView, StyleSheet } from 'react-native';

import { Screen, SuccessInfo } from './src/types';
import {
  VaultState,
  initialVaultState,
  calcLtv,
  contractLiquidationPrice,
  formatPct,
  formatThb,
} from './src/mockVault';
import { colors } from './src/theme';

import { OnboardingScreen } from './src/screens/OnboardingScreen';
import { SeedBackupScreen } from './src/screens/SeedBackupScreen';
import { FaceIdScreen } from './src/screens/FaceIdScreen';
import { HomeScreen } from './src/screens/HomeScreen';
import { ReceiveScreen } from './src/screens/ReceiveScreen';
import { BorrowScreen } from './src/screens/BorrowScreen';
import { LoanDashboardScreen } from './src/screens/LoanDashboardScreen';
import { RepayScreen } from './src/screens/RepayScreen';
import { SuccessScreen } from './src/screens/SuccessScreen';
import { ActivityScreen } from './src/screens/ActivityScreen';
import { PortfolioScreen } from './src/screens/PortfolioScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';

const NEW_CONTRACT_COLORS = ['#FCC330', '#4DB848', '#009B68', '#F8981C'];

// Deliberately simple state-based navigation, no react-navigation — this
// mirrors the tiny state machine the original mebit Claude Design prototype
// used, see docs/design-notes.md. Good enough for a skeleton that's meant to
// be played with, not shipped; swap in real navigation once screens multiply.
export default function App() {
  const [screen, setScreen] = useState<Screen>('onboarding');
  const [vault, setVault] = useState<VaultState>(initialVaultState);
  const [selectedContractId, setSelectedContractId] = useState(initialVaultState.contracts[0].id);
  const [success, setSuccess] = useState<SuccessInfo | null>(null);

  function goHome() {
    setScreen('home');
  }

  function confirmBorrow(collateralBtc: number, amountThb: number) {
    setVault((v) => {
      const id = Math.max(0, ...v.contracts.map((c) => c.id)) + 1;
      const contract = {
        id,
        label: `สัญญา #${id} · สัญญาใหม่`,
        openedLabel: 'วันนี้',
        daysOpen: 0,
        collateralBtc,
        debtThb: amountThb,
        color: NEW_CONTRACT_COLORS[(id - 1) % NEW_CONTRACT_COLORS.length],
      };
      return { ...v, contracts: [...v.contracts, contract] };
    });
    setScreen('portfolio');
  }

  function confirmRepay(repayThb: number) {
    const contract = vault.contracts.find((c) => c.id === selectedContractId);
    if (!contract) return;
    const remaining = Math.max(0, contract.debtThb - repayThb);
    const ltv = calcLtv(remaining, contract.collateralBtc, vault.btcPriceThb);
    const liq = remaining > 0 ? contractLiquidationPrice({ ...contract, debtThb: remaining }) : 0;

    setVault((v) => ({
      ...v,
      contracts: v.contracts.map((c) => (c.id === selectedContractId ? { ...c, debtThb: remaining } : c)),
    }));
    setSuccess({
      title: 'ชำระคืนสำเร็จ',
      detail: 'ยอดหนี้ลดลงแล้ว บิตคอยน์ที่ค้ำประกันของคุณยังอยู่ในกระเป๋าที่คุณถือกุญแจเอง',
      next: 'loan',
      breakdown: [
        { label: 'ชำระแล้ว', value: formatThb(repayThb) },
        { label: 'หนี้คงเหลือ', value: formatThb(remaining) },
        { label: 'LTV', value: remaining > 0 ? formatPct(ltv, 0) : '0%' },
      ],
    });
    setScreen('success');
  }

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar style={screen === 'success' ? 'light' : 'dark'} />
      {screen === 'onboarding' && (
        <OnboardingScreen onCreate={() => setScreen('seed')} onImport={goHome} />
      )}
      {screen === 'seed' && <SeedBackupScreen onNext={() => setScreen('faceid')} />}
      {screen === 'faceid' && <FaceIdScreen onDone={goHome} />}
      {screen === 'home' && <HomeScreen vault={vault} onNavigate={setScreen} />}
      {screen === 'receive' && <ReceiveScreen onBack={goHome} onNavigate={setScreen} />}
      {screen === 'borrow' && <BorrowScreen vault={vault} onBack={goHome} onConfirm={confirmBorrow} />}
      {screen === 'loan' && (
        <LoanDashboardScreen
          vault={vault}
          contractId={selectedContractId}
          onBack={goHome}
          onRepay={() => setScreen('repay')}
        />
      )}
      {screen === 'repay' && (
        <RepayScreen
          vault={vault}
          contractId={selectedContractId}
          onBack={() => setScreen('loan')}
          onConfirm={confirmRepay}
        />
      )}
      {screen === 'success' && success && (
        <SuccessScreen info={success} onDone={() => setScreen(success.next)} />
      )}
      {screen === 'activity' && <ActivityScreen onNavigate={setScreen} />}
      {screen === 'portfolio' && (
        <PortfolioScreen vault={vault} onNavigate={setScreen} onSelectContract={setSelectedContractId} />
      )}
      {screen === 'settings' && <SettingsScreen onNavigate={setScreen} />}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.mintTint },
});
