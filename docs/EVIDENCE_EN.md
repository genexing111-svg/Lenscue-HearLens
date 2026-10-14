# Evidence and limitations

These entries distinguish prior device validation from packaging checks. They are not a clinical study or an accessibility outcome evaluation.

## Latest release, 11–12 October 2026

Source: `v0.14.1-验证记录.md`. Redmi K80 Pro, Android 16 / API 36, app 0.14.1 / code 28.

| Check | Result | Interpretation |
| --- | --- | --- |
| Android JVM suite | 140 passed, 0 failed | Recorded for release build |
| Lint | 0 errors, 14 existing warnings | Warnings remain |
| Receiver suite | 5 passed | Rerun on copied source 14 Oct: 5 passed |
| APK signature / 16 KB alignment | Passed in release record | Debug evaluation signing |
| Real-board sample | 2,135 JPEGs / 100.035 s | 1,856 frames observed with glow present |
| Typical phone / observer | About 59 / 22 fps | Rendering/delivery, not speech latency |
| Active-glow composition | Mean 22.8 ms, max 40.5 ms | 124 valid/fresh state samples |
| Unexpected pause | 2.85 seconds | Cause unresolved |

Separate synthetic native pressure checks: 1920-wide JPEG90, 28.9 fps over 6.515 s; 2560-wide JPEG90, 19.1 fps over 6.503 s. Fixed public scenes and generated directions do not validate a physical microphone array. Four-glow composition/encoding averaged 68.2 ms and peaked at 182.1 ms across 45 frames at 2560×1152.

## PTT, 10 October 2026

Source: `v0.13.0-验证记录.md`. A fixed public input of 4.20 s produced recognised text, restated text and about 4.24 s of 24 kHz PCM. Playback count stayed 0 while held and became 1 after release and recording stop. Internal user subsequently confirmed shoulder speech, release playback and camera slider operation. This does not establish intelligibility for all speech patterns or faithful meaning preservation in every case.

## Unknowns and next evaluation

No target-user trial, complete current cost model, language-by-noise accuracy matrix, end-to-end caption-delay distribution, proven multi-source identity, outdoor mobility study, or all-day comfort/battery result is documented.

Proposed next step: 3–5 caption users compare the same short seated explanation using phone captions and HearLens. Record comprehension, repeated questions, settings-task completion, comfort, preference and reasons. Separately measure recognition errors and caption delay under stated conditions. Small exploratory results guide iteration, not population-level claims.

## Reproduce checks

`gradlew.bat assembleDebug testDebugUnitTest lintDebug`

`node --test desktop/receiver/test/*.test.mjs`

Device fixture checks require an Android device and test APK; arguments are in the dated records. Fixed public render tests do not require microphone/cloud use. Do not silently start real recording or cloud diagnostics.
