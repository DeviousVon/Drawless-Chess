# Five-language tablet accessibility and layout matrix

**Recorded:** July 21, 2026  
**Device:** TAB R6 Ultra, Android 13 / API 33, ARM64  
**Application:** `com.drawlesschess.debug`, recorded `0.3.0-debug` worktree build

## Result

The physical-tablet portion of the localization, large-font, restart, layout, and TalkBack gate
passed for:

- English (`en-US`)
- French (`fr`)
- German (`de`)
- Latin American Spanish (`es-419`)
- Brazilian Portuguese (`pt-BR`)

The phone portion of the combined phone/tablet release gate remains outstanding.

## Executed matrix

- Five locales at the tablet's normal and 200% test font scales.
- Portrait home, theme picker, gameplay, and completed-victory layouts.
- Landscape completed-defeat layout.
- Forty successful deterministic capture invocations, including reruns after correcting the
  test-only forced-locale resource provider.
- Fifty retained final PNGs under
  `build/locale-accessibility-matrix/tablet-20260721/`.
- Complete recorded instrumentation suite: 72/72 tests passed in 101.742 seconds.
- TalkBack was temporarily enabled alongside the tablet's pre-existing accessibility service.
  Touch exploration bound successfully, and a fresh-process accessibility tree was retained for
  every locale under `build/locale-accessibility-matrix/tablet-20260721/talkback/`.
- The five accessibility trees contain localized Quick Play, opponent, configuration summary,
  custom-game, theme, statistics, options, rules, license, privacy, and offline-status labels.

Expected compact-header ellipsizing appears at 200% font scale. Scrollable dialogs and result
content remain reachable; no overlapping action controls, missing localized actions, or clipped
non-scrollable required content was found in the retained matrix.

## Harness correction

`StoreScreenshotHarness.ForcedLocale` originally supplied localized `LocalContext` and
`LocalConfiguration` values but not `LocalResources`. Dialog subcomposition therefore fell back
to English even while the underlying screen used the requested locale. The test-only wrapper now
also supplies `localizedContext.resources`; the affected five-locale normal/200% theme-dialog
matrix was rebuilt, rerun, and visually rechecked.

## Restoration

After the TalkBack and locale-switch checks, the tablet was restored to:

- font scale `0.85`;
- no Drawless app-specific locale;
- TalkBack disabled; and
- the original Bitwarden accessibility service still enabled.
