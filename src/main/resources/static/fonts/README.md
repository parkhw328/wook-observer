# Local web fonts

These fonts are served by Wook Observer itself. No Google Fonts API, CDN or internet connection is used at runtime.

- **Noto Sans KR variable**, weights 100–900: [Google Fonts source](https://github.com/google/fonts/tree/main/ofl/notosanskr). Korean and other non-Latin glyphs; original TrueType file, no subset modification.
- **JetBrains Mono 2.304**, weights 400/500/600/700: [JetBrains source](https://github.com/JetBrains/JetBrainsMono/tree/v2.304). Latin letters and numbers; original WOFF2 files.

Both use the SIL Open Font License 1.1; see the adjacent `*-OFL.txt` files. `manifest.json` records each download URL, byte size and SHA-256. CSS restricts JetBrains Mono to Latin ranges and falls back to Noto Sans KR for Korean.
