# mebit app verification map

This directory is the maintained source for verifying what a user sees and does in the mebit mobile app. Read this index before you drive the app, then use the matching feature file as the recipe. `verify.sh` is `../scripts/verify.sh`; `../SKILL.md` explains its commands.

## Baseline preconditions

- `verify.sh doctor` passes, and `verify.sh start` has just finished in this run.
- The emulator is `emulator-5580`, portrait, started by this run. `start` uninstalls the app first, so the app data is empty.
- Never drive an emulator or simulator that this run did not start.
- `start` uses the first AVD that `emulator -list-avds` prints, or `VERIFY_AVD`. The recipes passed on the nine AVDs listed in `SKILL.md`. A different AVD changes the API level in the greeting text.

## Driving conventions

- Start every recipe from the baseline state unless its preconditions say otherwise.
- Find controls by visible text first, then `desc:`, then `id:`. Pass no coordinates to `tap`.
- Treat every command as literal. Keep quoted strings unchanged, including the `na kub` in the button label.
- Follow every `tap` with `see` or `gone`. Wait on the UI, never on `sleep`.
- Drive Android with `see`, `gone`, `tap`, `shot` and `drawer`. Drive iOS with `ios-start`, `ios-see`, `ios-gone`, `ios-tap`, `ios-shot` and `ios-stop`.
- Name each `shot` label after the sub-feature ID it proves, for example `home-reveal`.

## Proof and skip reporting

- Capture the user action and the resulting state, not only the final screen.
- UI proof is the `NN-LABEL.png` and `NN-LABEL.xml` pair, plus `steps.tsv` from the same run directory.
- Look at every PNG you cite. Images are not in the accessibility tree.
- Record the feature ID and the entry point used with every artifact.
- Report an unreachable path with the attempted command and the unmet precondition.
- Do not report a skipped entry point as verified through a different path. A recipe that passed on Android does not verify iOS, and the reverse.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the user-visible behavior. It then uses exactly four H2 sections in this order.

1. `Sub-features` lists short IDs with one line for each behavior.
2. `How to get to it (user POV)` lists every user entry point.
3. `Driving it with verify.sh` starts with `Preconditions:` and uses bold-lead bullets that pair each user action with an exact command and the observable result.
4. `Gotchas` lists traps that can waste or invalidate a verification run.

Keep implementation details out of the map. Name only user paths, stable handles, required state, commands, and observable proof.

## Features

- [Home greeting](./home-greeting.md) covers the legacy template button/greeting (kept for launch smoke).
- [App launch](./app-launch.md) covers opening the app from the launcher icon, what survives leaving it, a cold start, and the iOS launch.
- [Movie Page welcome](./movie-page-welcome.md) covers Welcome, catalog, and create CTA.
- [Movie Page onboarding](./movie-page-onboarding.md) covers 00.* software key → backup → home.
- [Movie Page keys](./movie-page-keys.md) covers AddKey three sources, T13, T14.
- [Movie Page vault](./movie-page-vault.md) covers policy → BSMS file → list/detail/T20.
- [Movie Page send](./movie-page-send.md) covers S* and M* (mocked chain/PSBT).

## Not mapped yet

Lending / loan dashboard screens are out of Movie Page scope. Live HW unlock+xpub smoke needs a Jade or Safe 7 on BLE.
