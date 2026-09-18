# Binaries

This directory contains pointers to binary release assets for the AutoSentry Android app.

Release: AutoSentry v3.0.10
Release page: https://github.com/AmResthtx/autosentry/releases/tag/draft

Asset attached: AutoSentry-v3.0.10.zip

Notes:
- The ZIP contains the APK and associated files. I have not added the binary into git history to avoid large blobs; the release stores the asset instead.
- Next steps I will take (no action required from you unless you ask):
  1. Download the release asset and compute SHA256 checksums for both the ZIP and the APK inside.
  2. Post the checksums here for verification.
  3. Optionally extract the APK and add it to the repository under `/binaries` using Git LFS (only if you want the binary in the repo). If you prefer, I will keep the release asset as the source of truth and only add a pointer.
  4. If you request, I can decompile the APK and import the decompiled source into a separate branch for analysis/refactor.

If you want me to proceed now with downloading and checksum verification, reply "Proceed: verify" and I will fetch the release asset and compute checksums. If you want me to instead import the binary into the repo via LFS, reply "Proceed: add-lfs".
