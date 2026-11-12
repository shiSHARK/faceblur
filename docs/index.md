<!-- SPDX-License-Identifier: Apache-2.0 -->
# FaceBlur documentation

FaceBlur covers the faces that your face-detection model finds with opaque masks, on the device, and writes a JPEG
without the source file's metadata. It is a tool for data minimisation. Detection can miss faces. It contains no
network code.

## Guides

| Guide | For |
|---|---|
| [FaceBlur on Android](guides/android.md) | Android apps in Kotlin or Java |
| [FaceBlur in a Swift app](guides/swift.md) | iOS apps that use the `faceblur-swift` package |
| [FaceBlur in a Kotlin Multiplatform app on iOS](guides/kmp-ios.md) | Kotlin Multiplatform apps that run on iOS |
| [Providing your model](guides/providing-your-model.md) | The model, its profile and its SHA-256 |
| [Score threshold](guides/score-threshold.md) | Choosing and checking the threshold; per-photo limits |
| [Threading, cancellation and memory](guides/threading-and-memory.md) | Running off the main thread, cancelling, memory |

## Reference

- [Architecture](architecture/ARCHITECTURE.md)
- [Decision records](adr/)
- [Building and testing](ops/BUILDING.md)
- [Changelog](../CHANGELOG.md)
