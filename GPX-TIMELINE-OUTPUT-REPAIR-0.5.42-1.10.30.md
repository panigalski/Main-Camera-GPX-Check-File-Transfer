# GPX Timeline / Backup / OUTPUT Repair

## Release pair

- Main App: **0.5.42** (`versionCode 65`)
- Client App: **1.10.30** (`versionCode 77`)
- These versions are intended to be used together.

## Evidence from the supplied recording

Recording: `260817_161856570.mp4`

The supplied GPX files show two different defects:

| File | Points | Time span | Maximum consecutive gap |
|---|---:|---:|---:|
| Main App `260817_161856570.gpx` | 105 | 37.999 s | **15.045 s** |
| Client `260817_161856570_backup.gpx` | 2 | 1.000 s | 1.000 s |

The Main GPX gap is between `2026-08-17T14:20:06.070Z` and `2026-08-17T14:20:21.115Z`.
The Client backup contains only `2026-08-17T14:19:45Z` and `2026-08-17T14:19:46Z`.

The shared MP4 is 856,271,499 bytes. The available Google Drive connector has a 256 MiB raw-download ceiling, so the exact MP4 binary could not be downloaded into this environment for byte-level re-extraction. The repair is therefore grounded in the supplied GPX pair, source-code tracing, and a synthetic ISO-BMFF/CAMM regression that reproduces a mid-recording type-6 GPS clock jump while presentation time stays continuous.

## Root cause 1 — Main App false CAMM time gap

CAMM type 6 contains an absolute GPS clock, but each CAMM sample also has a presentation timestamp (PTS) on the MP4 media timeline. The old parser used the type-6 absolute clock for every point. A mid-recording jump/correction of that GPS clock therefore appeared as a false GPX hole even when the MP4/CAMM presentation timeline itself was continuous.

### 0.5.42 repair

- MP4/CAMM presentation time is authoritative for **relative per-sample timing**.
- Type-6 GPS time is used only to establish/validate the **absolute movie start** and for diagnostics.
- Every canonical point is mapped as `canonicalMovieStart + CAMM presentation time`.
- Near-duplicate type-5/type-6 representations of the same fix are collapsed conservatively.
- A real CAMM PTS gap is still preserved. The repair does not manufacture data through a genuine recording gap.
- The parser reports timeline strategy, raw GPS-clock discontinuities, decoded point count and canonical point count in diagnostics.

A synthetic regression uses raw type-6 times `0s, 1s, 17s, 18s` while PTS is `0s, 1s, 2s, 3s`. The repaired result is correctly `0s, 1s, 2s, 3s`.

## Root cause 2 — Client backup inherited Camera GPX defects

The old Client tried to reproduce Camera GPX timestamps with phone coordinates. If exact matching failed, it used the Camera GPX first/last timestamps as a fallback interval. Any missing/incorrect Camera GPX timing could therefore shrink or distort the smartphone backup. The supplied backup collapsing to two points is consistent with that dependency.

### 1.10.30 repair

- Main 0.5.42 publishes the **full MP4 start/end interval** independently of extracted GPX points.
- Client uses phone fixes actually collected during that full MP4 interval.
- Client no longer clones Camera-GPX timestamps in the matched 0.5.42/1.10.30 pair.
- The GPS request interval is 250 ms where the Android provider supports it.
- Every genuine phone fix is retained.
- Normal slower phone cadence is densified to 250 ms **only when the real gap is <= 5.000 s**.
- A phone-GPS outage over 5 seconds is never interpolated away.
- Interpolated points are explicitly marked with provider `interpolated`.
- If there are no genuine phone fixes during the recording, no fictional GPX is created; the item remains retryable while retained phone history exists.

Recent queue entries within the Client's retained 14-day phone-GPS history use a versioned processing identity so defective backups created by pre-1.10.30 logic can be rebuilt exactly once after upgrade. Older history remains marked processed instead of flooding the service with recordings whose phone history is already gone.

## 5-second classification rule

The Main App classifies the **real extracted/canonical CAMM points before densification**:

- maximum consecutive gap **<= 5.000 s** → `GOOD`
- any consecutive gap **> 5.000 s** → `FAILED`
- extraction, validation, or processing failure after the normal retry policy → `ERROR`

Interpolation is not used to convert a real >5 s source gap into GOOD.

## OUTPUT folder layout restored

```text
OUTPUT/
├── GOOD/
│   ├── GOOD.TXT
│   └── 17-08-2026/
│       ├── 260817_....mp4
│       ├── 260817_....gpx
│       └── 260817_...._backup.gpx   # after manual Send GPX Files
├── FAILED/
│   ├── FAILED.TXT
│   └── 17-08-2026/
│       ├── 260817_....mp4
│       ├── 260817_....gpx
│       └── 260817_...._backup.gpx
└── ERROR/
    ├── ERROR.TXT
    └── 17-08-2026/
        ├── 260817_....mp4
        └── 260817_...._backup.gpx   # possible when video interval is readable and phone fixes exist
```

- Date is derived from the Labpano MP4 filename where possible, not from later processing time.
- `GOOD.TXT`, `FAILED.TXT`, and `ERROR.TXT` are cumulative reports in their matching status folders.
- Monitoring preflights/creates the status folders and report files.
- A stale finalized zero-byte MP4 is preserved under ERROR rather than silently deleted.

## Pending-media API / phone backup support

Main 0.5.42 adds `videoStartMillis` and `videoEndMillis` to durable pending rows. Client 1.10.30 requests `includeMediaOnly=1`, so an ERROR MP4 can still receive a smartphone backup when its movie timeline is readable even if Camera GPX extraction failed.

Older clients remain compatible because media-only rows are opt-in. Older Main Apps remain supported by a Client legacy fallback that uses the Camera GPX extent, but the corrected full-interval behavior requires the matched 0.5.42/1.10.30 pair.

## Manual "Send GPX Files"

The manual upload now carries the recording classification. A phone backup is copied to:

`OUTPUT/<GOOD|FAILED|ERROR>/dd-MM-yyyy/<video>_backup.gpx`

The camera still verifies size and SHA-256 before the Client marks the item sent. Regenerating a defective phone backup replaces the logical pending-send item by `date folder + filename`, so a changed SAF document URI cannot leave a stale queue entry.

## Validation performed

Fresh pure-Kotlin regression build/run from the current sources:

- `MAIN_GPX_REPAIR_TESTS_OK`
- `CLIENT_GPX_DENSIFIER_TESTS_OK`

Coverage includes:

- synthetic mid-recording +15 s type-6 GPS-clock jump with continuous CAMM PTS;
- stable absolute GPS-clock offset handling;
- edit-list timeline mapping;
- exact 5.000 s GOOD / 5.001 s FAILED boundary;
- GOOD / FAILED / ERROR + date output routing;
- 1 Hz phone cadence densified at 250 ms;
- no interpolation over a 5.001 s phone-GPS outage;
- interpolation permitted across exactly 5.000 s;
- pending-media ERROR interval parsing and classified manual upload tests in the Android test source.

Additional source/package checks:

- XML parses cleanly in both projects;
- no merge-conflict markers;
- Gradle wrapper JAR present in both projects;
- Client JVM unit-test `org.json` dependency retained;
- latest Android-bound Kotlin edits show no syntax-error diagnostics in host compiler scans.

A full Android Gradle build cannot be completed in this environment because Gradle 7.6.4 is not cached completely and network access to the distribution server is unavailable. The GitHub workflow should run `./gradlew testDebugUnitTest assembleDebug` with the Android SDK/dependencies available.

## Recommended physical-device acceptance

1. Install Main 0.5.42 and Client 1.10.30 together.
2. Enable Automatic Backup before recording.
3. Record one MP4 long enough to produce several GPS fixes.
4. Confirm Main moves it to the correct `GOOD/FAILED/ERROR/dd-MM-yyyy/` path.
5. Compare MP4 duration, Main GPX first/last timestamps, max gap, and Client backup first/last timestamps.
6. For a known good continuous recording, confirm no artificial ~15 s gap appears.
7. Press **Send GPX Files** and verify the `_backup.gpx` appears beside the matching camera recording in the same status/date folder.
