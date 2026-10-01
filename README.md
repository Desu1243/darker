# Darker

Flutter UI with a native Android screen-dimming overlay. The overlay is drawn
by a Kotlin foreground service and remains non-touchable, so input is passed to
the application underneath it.

## Current milestone

- dimming range from 0% to 78%;
- explicit Android "display over other apps" permission flow;
- live opacity changes while the overlay is active;
- persistent foreground-service notification with a Stop action;
- optional Text Aware mode that starts a `MediaProjection` session and receives
  throttled screen frames;
- Flutter/native state synchronization through a method channel.

The 78% limit is enforced in Kotlin as well as in Flutter. It leaves a small
margin below Android's 0.80 maximum obscuring opacity for pass-through touches.

Text recognition and transparent/partially dimmed text regions are intentionally
reserved for the next milestone. The capture pipeline is already running and
has a dedicated frame-processing hook in `OverlayService`.

## Run

```shell
flutter run
```

On first use, grant the overlay permission. When Text Aware is enabled, Android
will also ask which screen or app should be shared.
