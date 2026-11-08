# Darker

An Android screen dimmer for comfortable nighttime reading, with text that stays
readable while the rest of your screen gets darker.

Darker lets you darken the screen beyond your phone's minimum brightness.
Its optional **Text Aware** mode detects text on your device and keeps those
areas brighter than the surrounding content. Useful for reading manhwa,
comics, and other text-heavy content in the dark.

[Download from Releases](https://github.com/Desu1243/darker/releases)

## Features

- **Dimming across apps:** continue tapping and scrolling in your reader while
  Darker runs above it.
- **Independent screen and text opacity:** keep text readable without making
  its surroundings uncomfortably bright.
- **Customizable text areas:** adjust margins, rounded corners, and soft edges.
- **Smooth, stabilized masks:** fade transitions, persistent text regions, and
  safe-area tracking help reduce distracting changes around text.
- **Adjustable responsiveness:** choose a text-detection interval from 50 to
  500 ms in 5 ms steps and fine-tune region stability tolerance.
- **Calibration samples:** preview color bars and large and small-text samples
  while adjusting the overlay.
- **Saved preferences:** keep your settings between sessions.
- **Convenient controls:** switch between Dimmer and Settings, with Start/Stop
  always available and a Stop action in the ongoing notification.
- **Optional background operation:** choose whether dimming continues after
  removing Darker from recent apps.

## Install

1. Open [Releases](https://github.com/Desu1243/darker/releases) and download an APK
   attached to a release.
2. Open the APK on your Android device. If prompted, allow installation from
   the browser or file manager you used.
3. Launch Darker and grant **Display over other apps** permission.

Darker's screen overlay is available on Android only.

## Get started

1. On **Dimmer**, adjust **Opacity** to choose how dark the screen should be.
2. Open **Settings** using the bottom navigation or by swiping left from Dimmer.
3. Leave **Enable Text Aware** on to keep text areas brighter, or turn it off
   for uniform dimming.
4. Press **Start overlay**. With Text Aware enabled, approve Android's
   screen-sharing prompt so Darker can locate text.
5. Adjust **Text opacity** on Dimmer, then switch to your reading app.

Stop the overlay from Darker or its notification. By default, removing Darker
from recent apps also stops it. Enable **Keep overlay active after closing the
app** if you prefer it to remain on.

### Understanding opacity

Both sliders describe how visible the underlying content remains:

- **100%:** no additional dimming.
- **0% screen opacity:** the strongest dimming available in Darker.
- **Higher text opacity:** text areas remain brighter than their surroundings.
  For example, try screen opacity at 50% and text opacity at 70%.

Text areas cannot be set darker than the surrounding screen. These percentages
describe Darker's adjustment range, not measured display brightness.

## Make it comfortable

Expand **Text appearance** in Settings to customize margins, rounded corners,
and soft edges. Stop the overlay before changing these appearance settings.

Lower **Refresh interval** for faster updates while scrolling. Higher values
reduce how often recognition runs. Actual responsiveness depends on your device
and recognition speed. Fade duration automatically follows half the interval.

**Region stability tolerance** fine-tunes movement detection and mask shrinking.
Higher values favor stability over small updates. Safe-area stabilization
remains active at 0 px; this setting does not disable all stabilization.

Opacity, refresh interval, and stability tolerance can be adjusted while the
overlay is running.

## Privacy and permissions

- **Display over other apps** allows the dimming layer to appear above your apps.
- **Screen capture** is requested for Text Aware mode so it can locate text.
  Uniform dimming does not require capture.
- **Text recognition runs locally** using ML Kit. Darker's recognition pipeline
  does not upload screenshots or recognized text, or save them as recordings.
- An ongoing notification provides access to the app and a Stop action.

## Things to know

- Recognition currently uses a Latin-script model. Other scripts and highly
  stylized fonts may not be detected reliably.
- Text Aware brightens rounded areas around text lines, rather than recoloring
  individual letters or detecting entire speech bubbles.
- Detection can lag or miss text during fast scrolling. Stabilization reduces
  flicker but cannot eliminate every recognition change.
- Full-screen capture can include Darker's own overlay, affecting detection.
- App-only capture and rotation can affect alignment. If text areas appear
  offset, try full-screen capture and restart the overlay in your reading orientation.
- Frequent recognition uses more processing power and may increase battery usage.

## Feedback

Found a bug or have a suggestion? Open an
[issue](https://github.com/Desu1243/darker/issues). Include your device, Android
version, relevant settings, and steps to reproduce any rendering or recognition issue.
