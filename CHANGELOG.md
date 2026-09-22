# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions track `versionName` in [app/build.gradle.kts](app/build.gradle.kts).

## [Unreleased]

### Changed
- First public release. Backend endpoints and the MQTT password moved out of
  source into Gradle properties (`RC_MQTT_BROKER`, `RC_WS_BASE`,
  `RC_MQTT_PASSWORD`); the published defaults are placeholders.

## [1.4.5]

### Added
- Session and encoder stats streamed as `{"t":"log"}` JSON on the viewer
  WebSocket, so a black session can be diagnosed without adb.
- Vector launcher icon that survives round and squircle masks.

### Fixed
- MQTT survives an `rc-cap` SurfaceTexture crash instead of hanging main
  thread on `stopCapture`.

## [1.4.0]

### Added
- WebRTC `HardwareVideoEncoder` H.264 capture path with a JPEG fallback ladder.
- Crash breadcrumbs (`FaultLog`) and a per-device hardware H.264 kill switch.
