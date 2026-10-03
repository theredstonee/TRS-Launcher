# androidnsbypass (vendored)

- Upstream: https://github.com/alexytomi/androidnsbypass (git submodule of Amethyst-Android)
- Commit: `9f57982abf007aacc45becfc9328ae06fa6bbe3b`
- License: MIT (`LICENSE`); parts derived from liblinkernsbypass by Billy Laws, BSD-2-Clause
  (`LICENSE-BSD-2-Clause`).
- Content: `src/main/cpp/` → `cpp/` (unchanged), `README.md`.

## Local patches

None to the sources. Built with ndk-build from `../amethyst/jni/Android.mk` (module
`androidnsbypass`) instead of upstream's CMake/Prefab setup.
