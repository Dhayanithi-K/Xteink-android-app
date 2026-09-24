# X4 Flasher (Android)

Flashes an app image (e.g. CrossPoint `firmware.bin`) onto an Xteink X4 from an Android phone
over USB-C OTG. ROM-bootloader only, no stub, no PC.

What it does: reset into download mode -> SYNC -> confirm ESP32-C3 -> write image to app0 (0x10000)
-> MD5 verify -> blank otadata (bootloader then boots app0) -> reset.

Refuses: non-ESP32-C3 images, images without an app descriptor, images > 0x640000 bytes.

## Build without a PC
Push this folder to a GitHub repo. The workflow in .github/workflows/build.yml builds a debug APK
(Actions -> latest run -> Artifacts). Install it on the phone.

## Not done yet
- Backup of stock firmware (ROM loader can't read flash; needs the esptool stub loader)
- Partition-table check before writing
- X3 / X4 Pro (different layouts; deliberately unsupported)
- Untested on hardware
