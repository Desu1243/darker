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
- continuous recognition or recognition after visible screen movement stops;
- instant and fade transitions plus configurable margin, radius, and softness;
- Flutter/native state synchronization through a method channel.

Screen opacity is intentionally the inverse of the effective black overlay
alpha: 100% means no dimming and 0% means maximum dimming. The overlay window
stays at a constant 70% alpha while the black background pixels supply the
variable part of the dimming. Keeping the window in one composition mode avoids
the brightness and tone-mapping jump that some physical devices exhibit when a
window changes from exactly transparent to non-transparent.

The movement-triggered mode compares lightweight frame signatures rather than
intercepting global touch input. This preserves normal touch pass-through and
avoids requiring an accessibility service.

## Run

```shell
flutter run
```

On first use, grant the overlay permission. When Text Aware is enabled, Android
will also ask which screen or app should be shared.
