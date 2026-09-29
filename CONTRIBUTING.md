# Contributing to the TRS Launcher

> **Deutsch:** Diese Anleitung gibt es ausführlicher auf Deutsch in den Docs:
> [Mitmachen](https://trs-launcher.theredstonee.de/docs/de/developers/contributing).

Thanks for helping! Bug fixes, features, translations and docs are all welcome. This file is the short version; the
full guides are in the docs:

- [Build from source](https://trs-launcher.theredstonee.de/docs/en/developers/build-from-source)
- [Contributing](https://trs-launcher.theredstonee.de/docs/en/developers/contributing)
- [Translating](https://trs-launcher.theredstonee.de/docs/en/developers/translating)

## Report bugs and ideas – not in GitHub issues

GitHub issues are turned off for this repository. Please use:

- **Bugs and ideas:** the [issue tracker on the website](https://trs-launcher.theredstonee.de/issues) (you can also
  report a bug from inside the game: TRS menu → Report bug). The [roadmap](https://trs-launcher.theredstonee.de/roadmap)
  shows what is planned.
- **Questions and help:** [GitHub Discussions](https://github.com/theredstonee/TRS-Launcher/discussions) or the
  [Discord server](https://dc.theredstonee.de).
- **Security problems:** never in public – see [SECURITY.md](SECURITY.md).

For bigger changes, open an entry in the tracker or ask on Discord first, so the work fits the plan.

## Workflow

1. **Fork** the repository and create a branch from `main` (from `website` if you change the website or the TRS
   API in `api/`).
2. **Make your change.** Keep a pull request to one topic. Write commit messages in English.
3. **Run the checks** for the part you changed (below).
4. **Open a pull request** against the branch you started from. Fill in the template: what changes for players, how
   you tested it, screenshots for UI changes. Link the tracker entry, e.g.
   `Closes https://trs-launcher.theredstonee.de/issues/123`.

Pull requests are merged with **squash merge**: your branch becomes one commit on `main`, with the pull request title
as its message, so you don't need to clean up your history.

## Checks

The CI runs automatically on every pull request and only for the parts you changed. The one check that has to be green
is **`ci-ok`** – it sums up all the others:

| Check | Runs when you change | What it does |
|---|---|---|
| `launcher-windows`, `launcher-linux` | `app/`, `src-tauri/`, `scripts/`, `tests/`, `public/`, `packaging/`, `package.json` … | type check, frontend tests, frontend build, Clippy, Rust tests, Linux package metadata |
| `client-mod` | `client-mod/`, `data/` | unit tests of `client-mod/common`, compiles Fabric 1.21.11 and 26.3, Forge 1.8.9 and Forge 1.7.10 |
| `changelog` | every pull request | a new point in `CHANGELOG.md` in English and German (see below) |

Run them locally before you ask for a review:

```bash
# Launcher
pnpm typecheck
pnpm test
pnpm generate
cd src-tauri
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# TRS Client (build everything, or like the CI only single versions)
cd client-mod
./gradlew build
./gradlew -PtrsVersions=1.21.11 :common:test :fabric:1.21.11:compileJava

# Website/API (branch website)
cd api
pnpm typecheck && pnpm lint && pnpm test
```

Clippy warnings count as errors. Add tests for new logic: Vitest files in `tests/`, Rust unit tests next to the code,
JUnit tests in `client-mod/common`.

## Changelog (English + German)

Every change that players notice gets an entry in [`CHANGELOG.md`](CHANGELOG.md) under `## Unreleased`, with the same
points in **English and German**:

```md
## Unreleased

### English
- **Log search.** The log view now finds text in folded stack traces too.

### Deutsch
- **Log-Suche.** Die Log-Ansicht findet Text jetzt auch in eingeklappten Stacktraces.
```

Write for players, not developers: what changes for them, in plain words, without file or function names. If you
can't write German, write the English point and say so in the pull request – a maintainer will help.

The `changelog` check fails if a pull request adds no new point in both languages. Changes that players don't notice
(CI, refactoring, tests, docs) get the label **`no-changelog`** from a maintainer instead.

Everyone whose commits end up in a release is thanked by their GitHub name in the release notes and in the launcher's
"What's new" window – automatically, you don't need to do anything.

## Code style

- **Logic lives in `trs-core`** (`src-tauri/crates/core`); the Tauri layer stays thin and the core checks every input.
- **The webview gets no file system, network or shell permissions** – everything goes through dedicated commands.
- **No hard-coded text in the interface.** Every text is a key in `app/locales/*.json`; keys are typed, so typos fail
  the type check. Errors reach the interface only as a stable code plus a readable message.
- **TRS Client:** shared logic goes into `client-mod/common` and stays **Java 8**; version differences are written as
  Stonecutter comments (see [`client-mod/README.md`](client-mod/README.md)). Fair play: no reach or hitbox changes, no
  auto-clicking, no cave view on the minimap.
- Follow the style of the file you are editing. Comments in the code are mostly German – English is fine too.
- Never commit secrets, API keys or `.env` files.

## Translating

The launcher texts are in `app/locales/<language>.json` (English is the source), the TRS Client texts in
`client-mod/common/src/main/resources/assets/trsclient/i18n/`. The
[translation guide](https://trs-launcher.theredstonee.de/docs/en/developers/translating) explains placeholders, plural
forms and how to add a new language.

## License

The TRS Launcher and the TRS Client are licensed under the
[GNU General Public License v3.0](LICENSE) (GPL-3.0-only). By contributing, you agree that your contribution is
released under the same license.

Please be kind to each other – see the [Code of Conduct](CODE_OF_CONDUCT.md).
