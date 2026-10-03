# TRS: nur 64 Bit (Telefon + Emulator), Mindest-API wie die App (siehe UPSTREAM.md, Patch 1).
APP_PLATFORM := android-24
APP_STL := c++_shared
APP_ABI := arm64-v8a x86_64
# 16-KB-Seiten (Android 15+ Geräte wie neue Pixel): ELF-Segmente auf 16 KB ausrichten.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
