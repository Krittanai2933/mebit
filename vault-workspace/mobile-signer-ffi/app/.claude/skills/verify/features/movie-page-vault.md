# Movie Page vault policy → list

A user sets personal_custody policy, chooses verified keys, reviews a real multisig address from UniFFI, exports BSMS as file text (not QR), and opens vault list/detail/T20.

## Sub-features

- `mp-policy-personal` stays on personal_custody; decaying opens T16.
- `mp-vault-created-bsms` shows BSMS file body after create.
- `mp-vault-list-detail` lists created vaults and opens detail/actions.

## How to get to it (user POV)

- Keyring first vault → Policy → Choose keys → Review → Create → Vaults.
- Catalog jumps to POLICY / VAULT CREATED / VAULTS / T20 when state is seeded.

## Driving it with verify.sh

Preconditions:

- At least one verified key in the keyring (run onboarding + Add Key match, or seed via prior steps). For a thin smoke with empty keys, catalog → POLICY still shows the board.

- **Policy.** Catalog → `id:catalog_vault/policy`, `see id:screen_vault/policy`, `see id:policy_personal`. Optional: `tap id:cta_policy_decaying` → `see id:screen_critical/t16-decaying`. Shot `mp-policy-personal`.
- **Created BSMS.** After a successful create path, `see id:screen_vault/created`, `see id:bsms_file_notice`, `see id:bsms_body`. Confirm no QR affordance. Shot `mp-vault-created-bsms`.
- **List/detail/T20.** `tap id:cta_open_vaults` or catalog vaults, `see id:screen_vaults`, open a vault, `see id:screen_vaults/detail`, `tap id:cta_vault_actions`, `see id:screen_critical/t20`, `see id:t20_bsms`. Shot `mp-vault-list-detail`.

## Gotchas

- Review needs N verified keys with real tpubs; HW/mebit mocks mint real software keys labeled as HW.
- BSMS text is a file-shaped export string until BIP-129 FFI exists.
