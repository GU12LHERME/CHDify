**English** | [Português (Brasil)](README.pt-BR.md)

# CHDify

<img src="tools/icon/chdify_512.png" width="96" align="right" alt="CHDify icon">

Native Android disk image converter powered by the official MAME chdman/CHD library, compiled for ARM64. Works offline without root, Termux or additional apps.

## Download

[Download the latest APK](https://github.com/GU12LHERME/CHDify/releases/latest) · [GitHub Actions](https://github.com/GU12LHERME/CHDify/actions) · [Release notes (Portuguese)](RELEASE_NOTES.md)

| Requirement | Details |
|---|---|
| Android | 10 or later (API 29) |
| Architecture | arm64-v8a |
| Package | com.conversorchd |

## Features

- Convert ISO, BIN/CUE and GDI to CHD; extract DVD CHD to ISO, CD CHD to BIN/CUE and GD-ROM CHD to GDI.
- Fast, Balanced and Maximum lossless compression profiles.
- Batch conversion, persistent queue, progress, cancellation and local history.
- CHD inspection, integrity verification and recompression.
- Safe output handling, optional verified source deletion, SD card and USB support.
- Console folders and multidisc M3U playlists.
- OLED-friendly interface with touch and physical controller navigation.
- Multicore compression, foreground service and support for files larger than 4 GB.

## Usage

Select files or a folder, choose an output location and compression profile, then start the queue. Keep original files until you have verified the output and emulator compatibility. Not all emulators support CHD for every platform.

## Development

See the [technical documentation (Portuguese)](docs/DEVELOPMENT.md) for architecture, benchmarks, tests, signing and MAME updates.

Requirements: JDK 17, Android SDK 36, NDK 27.2.12479018 and CMake 3.22.1.

```bash
./gradlew assembleDebug assembleRelease
```

APKs are generated under `app/build/outputs/apk/`.

## Known limitations

NRG and non-seekable cloud/network streams are unsupported. Interrupted conversions restart from the beginning. CD extraction omits subchannel data, as with chdman. Android 15+ limits dataSync foreground services to six hours per day.

## Releases

Update the app version and release notes, then create a matching numeric version tag. GitHub Actions builds and tests the app and publishes the signed ARM64 APK with SHA256SUMS.txt. Existing releases are preserved.

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md), [BSD-3-Clause license](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).

MAME is a trademark of Gregory Ember. This project is not affiliated with MAMEdev.

For detailed features, compression benchmarks, controls and safety rules, see the [complete Portuguese documentation](README.pt-BR.md).
