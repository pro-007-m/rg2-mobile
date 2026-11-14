# Third-party notices and source attribution

This file records the sources and component licenses used by RG2 Mobile. It does **not** apply one license to the complete APK or establish redistribution permission for every imported lesson and image. The remaining source-coverage questions are recorded in [the source audit](docs/source-license-audit.md).

## Desktop foundation

The mobile port is based on [RG2 Desktop](https://github.com/pro-007-m/rg2-desktop/tree/533007b98fa815fc77762dac8e2008ac639b4e53), commit `533007b98fa815fc77762dac8e2008ac639b4e53`. Android-specific changes include a new native wrapper, phone/tablet layouts, touch gestures, system Back, clipboard and document integration, local backup validation and lifecycle handling. The Windows executable, .NET runtime and Windows updater are not Android dependencies.

The public source is not represented here as a license grant for all components of the mobile application.

## RG2 / RubicsGuide teaching materials

The imported library is a preserved snapshot from **RG2F 4.241017.6**, Android package `ru.tohaman.rg2`. The APK recorded by the migration has SHA-256 `401c4aed132f05a773a68fc61d2003f00fb5df6035bd03f7855757988b7396f7`.

The original author's public repository [Tohaman/RG2.kotlin](https://github.com/Tohaman/RG2.kotlin/tree/a1755567246a90bca00020b38d58f6a1482511d1), commit `a1755567246a90bca00020b38d58f6a1482511d1`, contains an Apache License 2.0 with this attribution:

> Copyright Rozov Anton

The upstream license is copied **without modification** to [LICENSES/Apache-2.0-RG2.txt](LICENSES/Apache-2.0-RG2.txt) and `data/licenses/Apache-2.0-RG2.txt`. Its SHA-256 is `b479fce41fb16e9e910c60b767ec47469cedc02365125d2cb4b31677bf3d0023`. No separate upstream NOTICE file was found in that inspected revision; this document is the port's source-attribution summary.

The audit found correspondence for **442 of 505** unique teaching strings from the later APK against that Apache-licensed revision: 115 matches after limited HTML/whitespace/image-path normalization and a further 327 matches of visible text. The other **63 HTML lessons were not matched** to that revision. Visible-text matches do not establish identical markup, attached images, or rights to later changes. The Apache license is not extended here to the unmatched later material.

Changes in representation: teaching strings were extracted from the APK into JSON; navigation was reconstructed from its constant arrays; image references were changed to relative paths to packaged raster files; the interface, algorithm controls and styles were rebuilt for desktop and Android. Original teaching text, formulas, source links and author references remain in the preserved source materials.

**PNG preservation and unresolved image correspondence:** all 2,063 original APK asset files are retained byte-for-byte. The inspected older Apache source primarily contains drawable XML. No exact PNG SHA-256 match was found against its 39 PNG files; 1,671 APK assets have same-stem candidates, which is not a geometric or pixel-level verification of XML-to-PNG conversion. Rasterized/vector correspondence and later additions remain to be established. Retaining the APK's PNG bytes is not presented as proof of their license.

The later public repositories `Tohaman/RG2.kotlin.new` and `Tohaman/RG2_flutter` were also compared. No root license grant was found in those inspected snapshots; their public availability is not treated as an independent redistribution license. RG2 branding is used to identify provenance, without a claim of official affiliation or a trademark license.

## Three.js

The solver includes **Three.js 0.187.0** and selected addons. Copyright © 2010–2026 Three.js authors. License: **MIT**.

The original notice is retained at `data/solver/vendor/LICENSE.three.txt`, with copies in [LICENSES/MIT-Three.txt](LICENSES/MIT-Three.txt) and `data/licenses/MIT-Three.txt`. The release and integrity records remain in `data/solver/vendor/version.json` and `addons-version.json`. Addon imports were adapted to local relative modules; the mobile renderer adds touch/pinch handling around the existing model.

Upstream: [Three.js](https://github.com/mrdoob/three.js).

## AndroidX WebKit

The Android shell depends on **`androidx.webkit:webkit:1.18.0`**, published by **The Android Open Source Project**, under **Apache License 2.0**. This component's declaration was verified in its Google Maven POM, and its license was extracted without modification from `META-INF/androidx/webkit/webkit/LICENSE.txt` in the resolved AAR.

Copies are provided in [LICENSES/Apache-2.0-AndroidX-WebKit.txt](LICENSES/Apache-2.0-AndroidX-WebKit.txt) and `data/licenses/Apache-2.0-AndroidX-WebKit.txt`. SHA-256 of the copied text: `809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0`.

Sources: [AndroidX WebKit release](https://developer.android.com/jetpack/androidx/releases/webkit#1.18.0), [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.18.0/webkit-1.18.0.pom). Transitive dependencies retain their own distributed notices; this document is not a complete dependency SBOM.

## Additional solver formula sources

The local solver catalog includes functional algorithm notations imported from RG2 and SpeedCubeDB. Source URLs, fetched-page hashes, case coverage and import scope are retained in [docs/solver-sources.json](docs/solver-sources.json) and the local solver catalog. The earlier RG2 Apache license is not asserted to cover independently imported later collections. This provenance record does not itself grant a new license to any third-party source.

## Packaged notices and scope

`data/licenses/NOTICE.txt` and the component license copies are included by the Android build's complete web-asset synchronization. The preserved materials remain available; this notice addition removes no lesson, diagram or formula. These notices describe identified sources and the limits of the audit, rather than declaring the complete package cleared for every distribution channel.
