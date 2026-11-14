# Source license audit

Checked **14 November 2026** against official public repositories and the preserved RG2F 4.241017.6 source snapshot. This report records evidence and unresolved coverage; it does not assign one license to the whole APK.

The Android port is based on desktop commit [`533007b98fa815fc77762dac8e2008ac639b4e53`](https://github.com/pro-007-m/rg2-desktop/tree/533007b98fa815fc77762dac8e2008ac639b4e53). No teaching material was removed during this audit.

## Confirmed license text

[`Tohaman/RG2.kotlin`](https://github.com/Tohaman/RG2.kotlin/tree/a1755567246a90bca00020b38d58f6a1482511d1), commit `a1755567246a90bca00020b38d58f6a1482511d1` (last repository update 4 May 2020), contains an Apache License 2.0 ending with `Copyright Rozov Anton`. The exact license file is included in [LICENSES/Apache-2.0-RG2.txt](../LICENSES/Apache-2.0-RG2.txt); SHA-256 `b479fce41fb16e9e910c60b767ec47469cedc02365125d2cb4b31677bf3d0023`. No separate NOTICE or specific teaching-text/vector exclusion was found in the inspected license/readme/source notices. This is evidence about that revision, not a blanket grant for a later APK.

## Comparison with the preserved APK corpus

The port contains 507 material records representing **505 unique source strings: 486 HTML lessons and 19 plain texts**. Matching uses original APK strings and their offsets, rather than modified interface output.

| Official source snapshot | Root license found | Matching teaching strings / 505 | Exact asset SHA-256 matches / 2063 | Same-stem candidates only | No same-stem candidate |
| --- | --- | ---: | ---: | ---: | ---: |
| RG2.kotlin · `a1755567246a90bca00020b38d58f6a1482511d1` | Apache-2.0 | 442 | 0 | 1671 | 392 |
| [RG2.kotlin.new](https://github.com/Tohaman/RG2.kotlin.new/tree/70d40ac896b2acef1ae493f07cb46e6e1fb0fb28) · `70d40ac896b2acef1ae493f07cb46e6e1fb0fb28` | None found | 476 | 1 | 1743 | 319 |
| [RG2_flutter](https://github.com/Tohaman/RG2_flutter/tree/6a597d460c6edb0d6c6be87e853e737cd307c1fb) · `6a597d460c6edb0d6c6be87e853e737cd307c1fb` | None found | 0 | 1 | 0 | 2062 |

The 442 matches against the Apache snapshot comprise 115 normalized HTML matches and another 327 visible-text matches. They cover 423 of the 486 HTML lessons and all 19 plain texts; **63 HTML lessons remain unmatched**. Normalization is limited to Android string escaping, HTML entities and whitespace; HTML comparison also normalizes image paths/extensions to their basenames. Formula notation and punctuation are retained. Matching visible text does not establish identical markup or images.

The older Apache snapshot contains 2,277 drawable XML files and 39 PNG files. Its lack of exact PNG hashes is compatible with later XML-to-PNG conversion, but the audit did not compare vector geometry or rendered pixels. The 1,671 same-stem candidates therefore remain candidates. The other 392 assets may be renamed or later additions; unmatched names do not establish their author or license.

One exact bitmap correspondence in the later `.new` snapshot is `frame_15.png` to the APK's `images/advices/frame_12.png`; the Flutter launcher corresponds to the APK's `images/android.png`. The older repository's Apache license is not automatically applied to those later snapshots.

## Preserved representation and notices

All original APK asset bytes are retained. Teaching strings are stored in JSON; image references are rewritten to the original packaged PNG paths; UI layouts, styles and algorithm controls are separate adaptations. Original source text, formulas, links and author references remain available in the source corpus. Current preservation checks are in [source-preservation.json](source-preservation.json).

The copied notices and component licenses are described in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Three.js carries its MIT notice. AndroidX WebKit 1.18.0 declares Apache-2.0 in its Google Maven metadata and contains a bundled license copied from its AAR. Older upstream Google billing/player SDK binaries are not copied into this port.

## Remaining scope

The 63 unmatched later HTML lessons, bitmap-to-licensed-vector correspondence, later image additions and independently imported solver collections require their own source/permission verification. SpeedCubeDB formula URL/hash provenance is retained in [solver-sources.json](solver-sources.json); it does not itself establish a new license grant. Branding and a claim of official publisher status are separate from the documented source attribution.

This report supports preserving identified licenses and accurately describing provenance. It does not state that every imported component has been cleared for public store redistribution.
