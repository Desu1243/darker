# Darker

Flutter UI with a native Android screen-dimming overlay. The overlay is drawn
by a Kotlin foreground service and remains non-touchable, so input is passed to
the application underneath it.

## Current milestone

- user-facing screen opacity from 0% to 100%;
- explicit Android "display over other apps" permission flow;
- live opacity changes while the overlay is active;
- persistent foreground-service notification with a Stop action;
- optional Text Aware mode that starts a `MediaProjection` session and receives
  throttled screen frames;
- Flutter/native state synchronization through a method channel.

Screen opacity is intentionally the inverse of the native black overlay alpha:
100% means no dimming and 0% means maximum dimming. The underlying overlay
alpha is capped at 78% in Kotlin, leaving a small margin below Android's 0.80
maximum obscuring opacity for pass-through touches.

Text recognition and transparent/partially dimmed text regions are intentionally
reserved for the next milestone. The capture pipeline is already running and
has a dedicated frame-processing hook in `OverlayService`.

## Run

```shell
flutter run
```

On first use, grant the overlay permission. When Text Aware is enabled, Android
will also ask which screen or app should be shared.
