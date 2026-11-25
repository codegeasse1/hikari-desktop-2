# HIKARI (hikari-desktop-2)

Desktop streaming client — **HIKARI** — in package `codegeasse.hikari.desktop`.

Four extension engines, all wired fresh:

| Engine | Format | Execution |
|---|---|---|
| CloudStream | `.cs3` repos (`plugins.json` + `manifest.json` + `classes.dex`) | Fresh dex→JVM bridge (`dex-tools` + ASM), child-first class loading, host API shims |
| Hikari | `.hiki` (same packaging as CloudStream) | Same bridge, `HikariProvider` interface |
| Nuvio | repo `manifest.json` + scraper `.js` | Rhino JS runtime, Nuvio-compatible host API (`fetch`, cheerio-style DOM, crypto, URL, timers) |
| Stremio | addon `manifest.json` HTTP API | Native HTTP client (catalog/meta/stream) |

Install design: every engine installs in the background (bounded thread pool),
mirror/URL races run in parallel with first-byte-wins, the UI acknowledges the
tap instantly with per-item progress. No sequential mirror ladders anywhere.

## Build

- JDK 17 (Temurin), Gradle 8.11.1
- `gradle :app:build` — compile + unit tests
- `gradle :app:jpackageInput` — flat dir for `jpackage`
- CI (`.github/workflows/build.yml`) builds the Windows `.exe` installer on every push to `main`.

## Layout

- `app/src/main/kotlin/codegeasse/hikari/desktop/` — `core` (net/store/fx/images),
  `model`, `engine` (common interface), `stremio`, `nuvio`, `cs` (CloudStream dex
  bridge + shims), `hiki`, `install`, `ui`, `player`.
- `app/src/main/java/{com/lagradost,android}/` — host API shims the extensions
  link against (CloudStream `MainAPI`/`nicehttp`, legacy `Plugin`, `android.content`
  `Context`/`SharedPreferences`, `Uri`, `Log`, …). Fresh code, same FQNs/signatures.
- `app/src/main/java/codegeasse/hikari/desktop/cs/` — vendored dex→JVM
  converter (`DexConverter` + low-memory SSA passes, from the CloudStream-desktop
  lineage) plus `Dex.kt` (hash-keyed jar cache, child-first class loading).
- `app/src/main/resources/theme.css` — the whole design system.
- `packaging/hikari.ico` — installer/app icon. `app/src/main/resources/hikari.png`
  (extracted 256px PNG from the same icon) is the window icon.

## Default sources

- CloudStream: `https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json`
  (JVM `.jar` preferred, `.cs3` converted on demand).
- Hikari: `…/hikari-extensions/main/repo.json` + `repo-desktop.json`.
- Nuvio: six community scraper manifests, code cached under `~/.hikari2/plugins/nuvio/`.
- Stremio: Cinemeta ships built-in; any `…/manifest.json` addon URL can be added.

All user data lives under `~/.hikari2/` (providers, history, favorites, settings,
dex cache). Nothing is uploaded anywhere.
