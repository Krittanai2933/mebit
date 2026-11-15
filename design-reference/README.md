# Design reference

The current visual source for the wallet and custody UI is Penpot **Organize**. Its companion **Organize · Design System** page defines shared colors, typography, geometry, actions, fields, cards, wallet patterns, and navigation. See the [flow inventory](../docs/design-notes.md) and [page-level summary](../docs/06-mobile-app-design-mebit.md).

- [colors_and_type.css](colors_and_type.css) records current Organize screen references and retains the legacy lending palette. The --org-* values are colors and card radius captured from the Organize Settings screen; treat Penpot Design System as canonical if a token differs.
- [fonts/](fonts/) contains Kanit TTFs and its OFL license, bundled for the app. Penpot Organize uses Anuphan; this repository does not vendor Anuphan.
- [logos/](logos/) contains mebit/Mapboss logo marks.

The older Claude Design and lending prototype assets are historical references. Their device-frame components android-frame.jsx and ios-frame.jsx are preview scaffolding, not reusable app code.
