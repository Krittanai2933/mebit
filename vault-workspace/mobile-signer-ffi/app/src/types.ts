export type Screen =
  | 'onboarding'
  | 'seed'
  | 'faceid'
  | 'home'
  | 'receive'
  | 'borrow'
  | 'loan'
  | 'repay'
  | 'success'
  | 'activity'
  | 'portfolio'
  | 'settings';

export interface SuccessInfo {
  title: string;
  detail: string;
  next: Screen;
  breakdown?: { label: string; value: string }[];
}
