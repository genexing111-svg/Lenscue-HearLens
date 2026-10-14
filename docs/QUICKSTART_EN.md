# Evaluator quick start

1. Install the supplied evaluation APK or build with the Gradle wrapper. Grant Android camera/microphone permissions when needed. Start in phone preview before using the passive viewer.
2. Choose a camera and enter dual-eye mode. Adjust eye size and position, then insert the phone into the viewer.
3. Enter your own supported Alibaba Cloud Model Studio key in phone speech configuration. Select a mode and start explicitly. Cloud features require a network and may incur provider charges.
4. Pair SC-B03 through Android. Calibrate mismatched physical keys in the app. A opens settings, X selects caption language, Y toggles environmental perception, shoulder is push-to-talk.
5. Connect a compatible CH340 board using USB OTG. Accept Android USB permission, select 115200 baud / automatic parsing, set a forward reference and verify left/right calibration.

## Optional computer

Use Node.js 22+ from its official source. Run `node desktop/receiver/launcher.mjs start --no-browser`, then open `http://127.0.0.1:18780`. Put phone and computer on the same trusted Wi-Fi/hotspot. Enable phone wireless sharing, enter its full generated viewing link in the local receiver and keep its token private. No cloud key is returned by that UI. Do not publish the phone's ports to the internet.

Stop with `node desktop/receiver/launcher.mjs stop`.

## Repeatable live sequence

- Say a short sentence while pointing to an object. Observe captions over the scene.
- Change language/size with the remote.
- Make one controlled sound from one direction. Explain that sound-type/bearing matching is provisional.
- Hold the shoulder key, speak, release. Confirm generated playback starts only after recording stops.
- Show the observer view, if available.

Use consenting speakers and a short stationary session. Label any prepared interface reproduction as scripted, not a live-test recording. Without a key/network, cloud speech cannot be evaluated. Without the board, a classifier label has no measured bearing. USB permission can recur after replugging. Keep a phone-screen alternative for people who cannot comfortably use the viewer.
