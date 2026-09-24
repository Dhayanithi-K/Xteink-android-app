# X4 Flasher (Android)

Flashes an app image (e.g. CrossPoint firmware.bin) onto an Xteink X4 from an Android phone
over USB-C OTG. No PC.

## Buttons
- **Flash to X4**: image -> app0 (0x10000), MD5 verify, blank otadata, reset.
  Optional pre-check reads the partition table via the stub and refuses non-standard layouts.
- **Inspect**: read-only. Prints partition table, otadata state, which slot boots, whether each slot holds an app.
- **Back up flash**: dumps all 16 MB (MD5 checked) to a file you choose. Restore later with the PC web flasher.
- **Flash into inactive slot** (tick box): writes to the slot that is not booting, verifies, then switches boot to it. The running firmware stays as fallback.
- **Swap slot**: points otadata at the other OTA slot (only if it holds a valid app).

## How it works
ROM bootloader for all writes (proven path). The esptool flasher stub (v1.11.1, downloaded and
SHA-256-checked by the workflow, not stored in the repo) is used only for reading flash.

## Build
Push to GitHub; Actions builds a debug APK (Actions -> run -> Artifacts).

## USB glitches
Stub reads are lock-step and MD5-checked. On a short/corrupt block the app resets the chip, reloads the stub and retries; backups read in shrinking pieces.

## Not done
X3 / X4 Pro (different layouts), stub-based fast writes, restoring a backup from the app.
