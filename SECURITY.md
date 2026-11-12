# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | yes |
| earlier | no |

Only the latest 0.1.x release receives security fixes.

## Reporting a vulnerability

Please report vulnerabilities privately. Do not open a public issue for them.

Use GitHub private vulnerability reporting for this repository: open the
"Security" tab, choose "Report a vulnerability" and describe the problem
(Security Advisories). Include the affected version, steps to reproduce and,
if you can, a minimal input that triggers it. Do not attach photos of real
people. Synthetic or public-domain images are fine.

We will acknowledge a report as soon as we can, keep you informed, and credit
you in the advisory if you wish. This is a small project; there is no
fixed response time.

## Scope

In scope: the FaceBlur library code in this repository, for example a face
that stays visible in an output although it was detected, metadata that
survives in an output, or a copy of a photo left behind.

Out of scope:

- The detection model. The developer who uses the library supplies the model
  and pins its SHA-256 hash. FaceBlur checks the hash and refuses models that
  reference external data before the runtime reads the file, but it cannot
  make a malicious model safe. Load only models you trust, ideally from your
  app bundle.
- Vulnerabilities in third-party components (ONNX Runtime, the operating
  system image decoders). Please report those upstream. We will update the
  pinned version when a fixed one is available.
- Photos that contain faces the detector does not find (see below).

## What FaceBlur claims

- It covers detected faces with opaque masks.
- It removes metadata from the output images.
- FaceBlur contains no network code.

Nothing more is claimed. In particular, detection can miss faces. FaceBlur is
data minimisation: it reduces the personal data in a photo, and the result
may still show people the detector missed. Treat outputs as personal data and
review the results where the outcome matters.

## Known issue: ONNX Runtime 1.20.0 on iOS

FaceBlur runs with ONNX Runtime 1.20.0 on iOS, an older runtime that lacks
some later memory-safety fixes. FaceBlur requires the SHA-256 of your model
and refuses models that reference external data before the runtime reads the
file, but it cannot make a malicious model safe: load only models you trust,
ship them inside your app and hard-code their hash.
