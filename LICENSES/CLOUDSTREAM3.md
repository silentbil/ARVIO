# CloudStream3 library vendor notice

The CloudStream SDK source vendored into `app/src/sideload/kotlin/com/lagradost/`
is derived from the `library/` module of
[recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)
at tag **v4.4.0** (2024-07-25), licensed **GPL-3.0**.

Because this source is linked into arvio's **sideload flavor** APK, that
specific build is distributed under GPL-3.0. This repository already makes
the source available at
<https://gitlab.com/arvio1/ARVIO>, satisfying GPL-3's source-distribution
requirement for sideload builds.

The **play flavor** APK contains none of this code — the `sideload` source
set is compiled only into `assembleSideloadRelease`. The play APK's
license is therefore unaffected by this vendoring.

Modifications made by arvio:
- Merged KMP `expect`/`actual` pairs into plain Android-Kotlin files
- Stripped `actual` keywords from merged files
