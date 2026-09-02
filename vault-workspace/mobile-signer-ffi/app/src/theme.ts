// mebit / Mapboss design tokens, ported from
// ../../../design-reference/colors_and_type.css for React Native (no CSS
// custom properties or @font-face there, so this is the JS-object
// equivalent). See ../../../docs/design-notes.md and
// ../../../.claude/skills/design-tokens/SKILL.md for the full reference.
//
// FC Vision (the real Mapboss typeface) isn't bundled here — its .otf files
// are proprietary and not in this repo (see design-reference/README.md).
// This falls back to the system sans-serif until someone drops the font
// files into ./assets/fonts and wires them up with expo-font.

export const colors = {
  teal: '#007368',
  tealDeep: '#003E38',
  green: '#009B68',
  greenLeaf: '#4DB848',
  mintAccent: '#00A191',

  green900: '#06312D',
  green850: '#0A3632',
  green800: '#0E3A35',

  yellow: '#FCC330',
  orange: '#F8981C',

  ink: '#1E1E1E',
  gray700: '#4B4B4B',
  gray500: '#545454',
  gray400: '#7A7A7A',
  gray300: '#C4C4C4',
  gray200: '#DFDFDF',
  gray100: '#E9EEED',
  offWhite: '#F8F8F8',
  mintTint: '#F2F8F7',
  white: '#FFFFFF',
} as const;

export const risk = {
  safe: colors.greenLeaf,
  watch: colors.yellow,
  danger: colors.orange,
};

export const radii = {
  sm: 8,
  md: 16,
  lg: 28,
  pill: 999,
};

export const shadow = {
  card: {
    shadowColor: colors.green900,
    shadowOpacity: 0.16,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 10 },
    elevation: 6,
  },
  soft: {
    shadowColor: colors.green900,
    shadowOpacity: 0.12,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 6 },
    elevation: 3,
  },
} as const;

export const font = {
  display: undefined, // system default until FC Vision is wired up
};

// New "wallet-first" screens (Add Key / Pair Device / Keyring / Policy / ...,
// see Penpot "Page 1") use IBM Plex Sans Thai instead of FC Vision — a real,
// redistributable typeface with proper Thai support, picked for this design
// pass. Not bundled yet (no @expo-google-fonts/ibm-plex-sans-thai + expo-font
// wiring), so this falls back to the system sans-serif the same way
// `font.display` does above until someone loads it.
export const walletFont = 'IBM Plex Sans Thai';

// Corner radii used by the wallet-first component set, kept separate from
// `radii` above so existing screens' arithmetic (radii.lg - 4, etc.) doesn't
// shift if these ever need to diverge.
export const walletRadii = {
  checkbox: 7,
  chip: 12,
  icon: 14,
  card: 18,
  cardLg: 20,
  pill: 999,
} as const;
