<!-- SPDX-License-Identifier: Apache-2.0 -->
# Contributing to FaceBlur

## Version 0.1.0: issues, not pull requests

For version 0.1.0 this project accepts **issues**, not pull requests. Please open an issue for a bug, a question or a
suggestion. Pull requests from outside the maintainer are not reviewed or merged in this version; this may change in a
later version and will be announced here.

When you open an issue:

- Say what you did, what you expected and what happened, with the FaceBlur version, the platform and the OS version.
- Do **not** attach photos of people, your model files or any personal data. Describe the problem in words, with
  numbers (image size, number of faces) if you can.
- Report a suspected privacy problem (a face left visible although the documented setup was used, metadata in an
  output file) privately, as described in [SECURITY.md](SECURITY.md), never as a public issue. Other bugs are issues.

## Sign-off (Developer Certificate of Origin)

If pull requests are accepted in a later version, every commit must carry a sign-off line that states that you have the
right to submit it under the project's licence (Apache-2.0). Add it with `git commit -s`; the line reads
`Signed-off-by: Your Name <your.email@example.com>` and must name the commit's author. The sign-off certifies the
following (Developer Certificate of Origin, version 1.1):

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## Rules for any contribution

These rules apply to code, tests and documents, and are checked by the repository's hooks and build:

- Masks are opaque. No blur, pixelation, transparency or partial style, and no setting that can make a mask smaller than
  the default.
- No network code in the library; builds check the dependencies and the merged manifests.
- No file, image or model is added without being registered; images that show faces are limited to approved
  public-domain photographs. Never add photos of people.
- No secrets, local paths, user or device names in code, comments, documents or commit messages.
- Describe FaceBlur as data minimisation. Do not claim legal compliance, security or certification, and do not claim
  that all faces are found.

To build and test the repository, see [docs/ops/BUILDING.md](docs/ops/BUILDING.md).
