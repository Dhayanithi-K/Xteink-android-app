# X4 Flasher (Android) - built entirely on claude AI
# Building the first version of this app took only 10 minutes!!!
## Disclaimer: ⚠️Try at your own risk, Please don't blame me if you bricks your device, blame claude 😂⚠️
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
## How to use?
- Download the apk from [Release](https://github.com/Dhayanithi-K/Xteink-android-app/releases)
-  section and install on your android device.
- Connect your xteink x4 device to your android using usb C to C cable
- Download the desired firmware.bin file (eg: crosspoint/ inky / Witch(hunt) Reader - basically whichever you like in your android device
- open the app and select the firmware and wait for it to verify it.
- click Flash to X4.
- wait for the xteink x4 device to reboot
- Done
