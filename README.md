# Darker

Flutter UI with a native Android screen-dimming overlay. The overlay is drawn
by a Kotlin foreground service and remains non-touchable, so input is passed to
the application underneath it.

## Current milestone

- user-facing screen opacity from 0% to 100%;
- explicit Android "display over other apps" permission flow;
- live opacity changes while the overlay is active;
- persistent foreground-service notification with a Stop action;
- optional Text Aware mode with on-device ML Kit OCR and softly feathered text
  cut-outs;
- independent text-area opacity, adjustable live while the overlay is active;
- continuous text recognition with a smooth fade transition;
- live OCR refresh interval from 50 to 500 ms, with fades lasting half the interval;
- configurable rectangle jitter tolerance from 0 to 20 screen pixels;
- configurable text margin, corner radius, and edge softness;
- persistent storage for every user-adjustable setting;
- Flutter/native state synchronization through a method channel;
- separate Dimmer and Settings pages with bottom navigation;
- configurable overlay shutdown when the app is removed from recent apps.

## Technical documentation

See [Project and rendering documentation](docs/PROJECT_DOCUMENTATION.md) for
the architecture, settings, lifecycle, capture/OCR scheduling, rectangle
stabilization, opacity equations, Canvas mask composition, animation behavior,
and current limitations.

Screen opacity is intentionally the inverse of the effective black overlay
alpha: 100% means no dimming and 0% means maximum dimming. The overlay window
stays at a constant 70% alpha while the black background pixels supply the
variable part of the dimming. Keeping the window in one composition mode avoids
the brightness and tone-mapping jump that some physical devices exhibit when a
window changes from exactly transparent to non-transparent.

## Run

```shell
flutter run
```

On first use, grant the overlay permission. When Text Aware is enabled, Android
will also ask which screen or app should be shared.
