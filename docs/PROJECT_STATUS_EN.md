# Scope and architecture as of 14 October 2026

| Capability | Implemented state | Boundary |
| --- | --- | --- |
| Camera and dual-eye view | Integrated on phone | Same mono image for both eyes |
| Cloud captions and translation | Real service checks | Accuracy and delay not comprehensively measured |
| Speaker colours | Integrated | Cloud segment IDs, not person recognition |
| Environmental classification | Local YAMNet on Android | 521 model labels are not 521 validated categories |
| USB direction | Physical board exercised | CH340, 115200 baud, calibration required |
| Blue glow and labels | Native and wireless | Temporal matching, multi-source identity unknown |
| Shoulder speech/restatement | Real cloud/audio check and internal confirmation | Meaning preservation needs evaluation |
| Physical controls | Integrated | Device-specific calibration |
| Observer console | Integrated and tested | Optional trusted-LAN view/control |
| Face-anchored bubbles | Future work | No face tracking, voice-face binding or world anchors |
| Target-user benefit | Proposed evaluation | No documented target-community trial |

The 4 October interim report predates USB, environmental and PTT integration. Its team roles and problem research remain useful; its status statements are superseded by the 10–12 October release records.

## Data and compute

The Android phone runs CameraX capture, LocalStereoEngine and per-eye geometry. SharedPhoneAudio supplies 16 kHz PCM to cloud speech and local classification. EnvironmentAudioFramer uses 15,600-sample windows with a 7,680-sample hop. LiteRT runs fixed YAMNet weights. A stable circular mean of USB angles within a time window provides only provisional category/bearing association.

`AliyunSpeech.kt` configures `qwen-audio-3.1-asr-flash-streaming` and `qwen3.8-livetranslate-flash-realtime`. The tested live voice is `Tina`. PttClient/PttProtocol request generated restatement and audio. These are existing models, not team-trained models. Evaluators need provider access to those exact model IDs.

GenAI handles transcription, translation and restatement. YAMNet is a discriminative classifier. USB hardware supplies direction. Neither model invents a missing bearing. Camera images are not sent to the cloud speech service.

YAMNet SHA-256: `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`. File size 4,126,810 bytes. Input FLOAT32 [15600], output FLOAT32 [1,521]. See `environment-reference/MODEL-PROVENANCE.md`.

The edge cue uses head-relative horizontal bearing: 0 front, 90 right, 180 rear, 270 left. Native Canvas/AGSL and software wireless JPEG paths share visual rules. Cached geometry and intensity tables address the earlier multi-second encoding stall.

The optional receiver uses one upstream stream and clears stale frames. It listens on 127.0.0.1 and guards writes with Host/Origin and CSRF checks. Windows DPAPI stores the viewing token. Phone keys use Android Keystore AES/GCM.
