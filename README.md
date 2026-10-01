# X4 Flasher (Android)

Flashes an app image (e.g. CrossPoint firmware.bin) onto an Xteink X4 from an Android phone
over USB-C OTG. No PC.
# X4 Flasher (Android) - built entirely on claude AI
# Building the first version of this app took only 10 minutes!!!
## Disclaimer: ⚠️Try at your own risk, Please don't blame me if you bricks your device, blame claude 😂⚠️
Flashes an app image (e.g. CrossPoint `firmware.bin`) onto an Xteink X4 from an Android phone
over USB-C OTG. ROM-bootloader only, no stub, no PC.

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

## Continuous USB reader (experimental)
Tick box. Keeps 4 bulk-IN requests queued at all times instead of one, so the phone never stops polling the X4 between packets. Aimed at the random dropped bytes seen on stub reads. Off by default.


## Signed release build (optional)

The Debug APK under Artifacts already installs and runs fine — "release" only matters if you
want a smaller, non-debuggable build or a permanent download link. To enable it:

1. Add these four **repository secrets** (Settings -> Secrets and variables -> Actions -> New
   repository secret) using the keystore generated for you:
   - `X4_KEYSTORE_B64` — the keystore file, base64-encoded (one long line)
   - `X4_KEYSTORE_PASSWORD`
   - `X4_KEY_ALIAS` — `x4flasher`
   - `X4_KEY_PASSWORD` (same as the store password here)
2. Push again (or re-run the workflow). A `x4-flasher-release` artifact will appear alongside
   the debug one.
3. To also get a permanent link instead of an Actions artifact (artifacts expire), push a tag:
   `git tag v0.2 && git push origin v0.2`. The workflow then attaches the APK to a GitHub
   Release at Releases on the repo page.

Keep the `.jks` file and its passwords somewhere safe outside the repo — losing them means a
future update can never be signed to match this install without uninstalling it first.

## Not done
X3 / X4 Pro (different layouts), stub-based fast writes, restoring a backup from the app.
## Not done yet
- Backup of stock firmware (ROM loader can't read flash; needs the esptool stub loader)
- Partition-table check before writing
- X3 / X4 Pro (different layouts; deliberately unsupported)
- Untested on hardware

## How to use?
- Download the apk from [Release](https://github.com/Dhayanithi-K/Xteink-android-app/releases)
-  section and install on your android device.
- Connect your xteink x4 device to your android using usb C to C cable and make sure the xteink x4 is not sleeping.
- Download the desired firmware.bin file (eg: crosspoint/ inky / Witch(hunt) Reader - basically whichever you like in your android device
- open the app and select the firmware and wait for it to verify it.
- click Flash to X4.
- wait for the xteink x4 device to reboot
- Done
## Screenshot
<img width="1080" height="2400" alt="1000118342" src="https://github.com/user-attachments/assets/1fe17fc7-44d4-479b-a4bb-9fa0d1efe699" />
