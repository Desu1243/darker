import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const DarkerApp());

class DarkerApp extends StatelessWidget {
  const DarkerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Darker',
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF090A0E),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF9EA8FF),
          secondary: Color(0xFFBBC2FF),
          surface: Color(0xFF15171E),
          error: Color(0xFFFFB4AB),
        ),
        sliderTheme: const SliderThemeData(
          trackHeight: 6,
          thumbSize: WidgetStatePropertyAll(Size.square(22)),
        ),
      ),
      home: const DimmerPage(),
    );
  }
}

class DimmerPage extends StatefulWidget {
  const DimmerPage({super.key});

  @override
  State<DimmerPage> createState() => _DimmerPageState();
}

enum TextAwareUpdateMode { continuous, afterMotionStops }

enum TextAwareAnimationMode { instant, fade }

class _DimmerPageState extends State<DimmerPage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.desu1243.darker/overlay');
  static const _maxNativeOverlayOpacity = 0.70;

  // User-facing opacity describes how visible the underlying screen remains:
  // 100% means no dimming, while 0% means maximum safe dimming.
  double _screenOpacity = 0.50;
  double _textOpacity = 0.75;
  bool _textAware = false;
  bool _hasOverlayPermission = false;
  bool _overlayRunning = false;
  bool _captureRunning = false;
  bool _busy = true;
  TextAwareUpdateMode _updateMode = TextAwareUpdateMode.continuous;
  TextAwareAnimationMode _animationMode = TextAwareAnimationMode.instant;
  double _textMargin = 12;
  double _cornerRadius = 10;
  double _edgeSoftness = 12;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _channel.setMethodCallHandler(_handleNativeCallback);
    _refreshNativeState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _channel.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshNativeState();
  }

  Future<void> _handleNativeCallback(MethodCall call) async {
    if (!mounted) return;
    switch (call.method) {
      case 'onOverlayStarted':
        setState(() {
          _busy = false;
          _overlayRunning = true;
          _captureRunning = true;
        });
      case 'onCapturePermissionDenied':
        setState(() => _busy = false);
        _showMessage(
          'Text Aware cannot start without screen capture permission.',
        );
    }
  }

  Future<void> _refreshNativeState() async {
    try {
      final results = await Future.wait<Object?>([
        _channel.invokeMethod<bool>('checkOverlayPermission'),
        _channel.invokeMethod<Map<Object?, Object?>>('getOverlayState'),
      ]);
      final state = results[1] as Map<Object?, Object?>?;
      if (!mounted) return;
      setState(() {
        _hasOverlayPermission = results[0] as bool? ?? false;
        _overlayRunning = state?['running'] as bool? ?? false;
        _captureRunning = state?['captureRunning'] as bool? ?? false;
        _textAware = state?['textAware'] as bool? ?? _textAware;
        final nativeOpacity = state?['opacity'] as num?;
        final nativeTextDimAmount = state?['textDimAmount'] as num?;
        if (_overlayRunning && nativeOpacity != null) {
          _screenOpacity = _screenOpacityFromNative(nativeOpacity.toDouble());
        }
        if (_overlayRunning && nativeTextDimAmount != null) {
          _textOpacity = _screenOpacityFromNative(
            nativeTextDimAmount.toDouble(),
          ).clamp(_screenOpacity, 1.0);
        }
        _busy = false;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showMessage(error.message ?? 'Could not read the application state.');
    } on MissingPluginException {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _requestOverlayPermission() async {
    await _channel.invokeMethod<void>('requestOverlayPermission');
  }

  Future<void> _toggleOverlay() async {
    if (_busy) return;
    if (_overlayRunning) {
      setState(() => _busy = true);
      try {
        await _channel.invokeMethod<void>('stopOverlay');
        if (!mounted) return;
        setState(() {
          _overlayRunning = false;
          _captureRunning = false;
          _busy = false;
        });
      } on PlatformException catch (error) {
        if (!mounted) return;
        setState(() => _busy = false);
        _showMessage(error.message ?? 'Could not stop the overlay.');
      }
      return;
    }

    if (!_hasOverlayPermission) {
      _showMessage('Allow Darker to display over other apps first.');
      await _requestOverlayPermission();
      return;
    }

    setState(() => _busy = true);
    try {
      final response = await _channel.invokeMethod<Map<Object?, Object?>>(
        'startOverlay',
        {
          'opacity': _nativeOpacityFromScreen(_screenOpacity),
          'textDimAmount': _nativeOpacityFromScreen(_textOpacity),
          'textAware': _textAware,
          'updateMode': _updateMode.name,
          'animationMode': _animationMode.name,
          'textMarginDp': _textMargin,
          'cornerRadiusDp': _cornerRadius,
          'featherDp': _edgeSoftness,
        },
      );
      final waiting = response?['awaitingCapturePermission'] as bool? ?? false;
      if (!mounted) return;
      if (!waiting) {
        setState(() {
          _overlayRunning = true;
          _busy = false;
        });
      }
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showMessage(error.message ?? 'Could not start the overlay.');
    }
  }

  Future<void> _updateOpacity(double value) async {
    setState(() {
      _screenOpacity = value;
      if (_textOpacity < value) _textOpacity = value;
    });
    if (!_overlayRunning) return;
    try {
      await _channel.invokeMethod<void>('updateOpacity', {
        'opacity': _nativeOpacityFromScreen(value),
      });
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Could not update the opacity.');
    }
  }

  Future<void> _updateTextOpacity(double value) async {
    final safeValue = value.clamp(_screenOpacity, 1.0);
    setState(() => _textOpacity = safeValue);
    if (!_overlayRunning) return;
    try {
      await _channel.invokeMethod<void>('updateTextOpacity', {
        'textDimAmount': _nativeOpacityFromScreen(safeValue),
      });
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Could not update the text opacity.');
    }
  }

  double _nativeOpacityFromScreen(double screenOpacity) {
    return (1 - screenOpacity.clamp(0.0, 1.0)) * _maxNativeOverlayOpacity;
  }

  double _screenOpacityFromNative(double nativeOpacity) {
    final safeNativeOpacity = nativeOpacity.clamp(
      0.0,
      _maxNativeOverlayOpacity,
    );
    return 1 - (safeNativeOpacity / _maxNativeOverlayOpacity);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final percentage = (_screenOpacity * 100).round();
    final textPercentage = (_textOpacity * 100).round();
    final textOpacityDivisions = ((1 - _screenOpacity) * 100).round().clamp(
      1,
      100,
    );
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(22, 24, 22, 28),
          children: [
            Row(
              children: [
                Container(
                  width: 46,
                  height: 46,
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primary
                        .withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(15),
                  ),
                  child: const Icon(Icons.dark_mode_rounded),
                ),
                const SizedBox(width: 14),
                const Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Darker',
                      style: TextStyle(
                        fontSize: 27,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    Text(
                      'Comfortable reading after dark',
                      style: TextStyle(color: Color(0xFF9A9DA8)),
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 34),
            _StatusCard(
              running: _overlayRunning,
              captureRunning: _captureRunning,
              textAware: _textAware,
            ),
            const SizedBox(height: 18),
            _Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        'Opacity',
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        '$percentage%',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Slider(
                    value: _screenOpacity,
                    min: 0,
                    max: 1,
                    divisions: 100,
                    label: '$percentage%',
                    onChanged: _busy ? null : _updateOpacity,
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 4),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Maximum dimming',
                          style: TextStyle(color: Color(0xFF858894)),
                        ),
                        Text(
                          'No dimming',
                          style: TextStyle(color: Color(0xFF858894)),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            _Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text(
                      'Text Aware',
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    subtitle: const Padding(
                      padding: EdgeInsets.only(top: 5),
                      child: Text(
                        'Detect text on screen and keep its surroundings clear.',
                      ),
                    ),
                    value: _textAware,
                    onChanged: _overlayRunning || _busy
                        ? null
                        : (value) => setState(() => _textAware = value),
                  ),
                  if (_textAware) ...[
                    const Divider(height: 32),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text(
                          'Text opacity',
                          style: TextStyle(fontWeight: FontWeight.w600),
                        ),
                        Text(
                          '$textPercentage%',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.primary,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                    Slider(
                      value: _textOpacity.clamp(_screenOpacity, 1.0),
                      min: _screenOpacity,
                      max: 1,
                      divisions: textOpacityDivisions,
                      label: '$textPercentage%',
                      onChanged: _busy || _screenOpacity >= 1
                          ? null
                          : _updateTextOpacity,
                    ),
                    const Text(
                      'Controls brightness inside detected text areas. It cannot be darker than the rest of the screen.',
                      style: TextStyle(color: Color(0xFF858894), height: 1.35),
                    ),
                    const SizedBox(height: 22),
                    const Text(
                      'Detection frequency',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<TextAwareUpdateMode>(
                        segments: const [
                          ButtonSegment(
                            value: TextAwareUpdateMode.continuous,
                            label: Text('Continuous'),
                            icon: Icon(Icons.sync_rounded),
                          ),
                          ButtonSegment(
                            value: TextAwareUpdateMode.afterMotionStops,
                            label: Text('After movement'),
                            icon: Icon(Icons.touch_app_outlined),
                          ),
                        ],
                        selected: {_updateMode},
                        onSelectionChanged: _overlayRunning || _busy
                            ? null
                            : (selection) =>
                                  setState(() => _updateMode = selection.first),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _updateMode == TextAwareUpdateMode.continuous
                          ? 'OCR updates as quickly as the device can process frames.'
                          : 'OCR runs about 300 ms after visible movement stops.',
                      style: const TextStyle(
                        color: Color(0xFF858894),
                        height: 1.35,
                      ),
                    ),
                    const SizedBox(height: 22),
                    const Text(
                      'Transition',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<TextAwareAnimationMode>(
                        segments: const [
                          ButtonSegment(
                            value: TextAwareAnimationMode.instant,
                            label: Text('Instant'),
                          ),
                          ButtonSegment(
                            value: TextAwareAnimationMode.fade,
                            label: Text('Fade'),
                          ),
                        ],
                        selected: {_animationMode},
                        onSelectionChanged: _overlayRunning || _busy
                            ? null
                            : (selection) => setState(
                                () => _animationMode = selection.first,
                              ),
                      ),
                    ),
                    const SizedBox(height: 22),
                    _StyleSlider(
                      label: 'Text margin',
                      value: _textMargin,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: (value) => setState(() => _textMargin = value),
                    ),
                    _StyleSlider(
                      label: 'Corner radius',
                      value: _cornerRadius,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: (value) =>
                          setState(() => _cornerRadius = value),
                    ),
                    _StyleSlider(
                      label: 'Edge softness',
                      value: _edgeSoftness,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: (value) =>
                          setState(() => _edgeSoftness = value),
                    ),
                  ],
                ],
              ),
            ),
            if (!_hasOverlayPermission) ...[
              const SizedBox(height: 14),
              _PermissionCard(onPressed: _requestOverlayPermission),
            ],
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _busy ? null : _toggleOverlay,
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(56),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(18),
                ),
              ),
              icon: _busy
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Icon(
                      _overlayRunning
                          ? Icons.stop_rounded
                          : Icons.visibility_rounded,
                    ),
              label: Text(
                _overlayRunning ? 'Stop overlay' : 'Start overlay',
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const SizedBox(height: 14),
            const Text(
              'The overlay does not intercept touch. At 0% screen opacity, '
              'maximum dimming is capped at 70% for comfortable reading.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Color(0xFF858894), height: 1.4),
            ),
          ],
        ),
      ),
    );
  }
}

class _Panel extends StatelessWidget {
  const _Panel({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: Color(0xFF252832)),
      ),
      child: Padding(padding: const EdgeInsets.all(18), child: child),
    );
  }
}

class _StyleSlider extends StatelessWidget {
  const _StyleSlider({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.suffix,
    required this.enabled,
    required this.onChanged,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final String suffix;
  final bool enabled;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label),
            Text(
              '${value.round()} $suffix',
              style: TextStyle(color: Theme.of(context).colorScheme.primary),
            ),
          ],
        ),
        Slider(
          value: value,
          min: min,
          max: max,
          divisions: (max - min).round(),
          onChanged: enabled ? onChanged : null,
        ),
      ],
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.running,
    required this.captureRunning,
    required this.textAware,
  });
  final bool running;
  final bool captureRunning;
  final bool textAware;

  @override
  Widget build(BuildContext context) {
    final label = !running
        ? 'Overlay inactive'
        : captureRunning
        ? 'Overlay and Text Aware active'
        : 'Overlay active';
    final color = running ? const Color(0xFF8FE3B1) : const Color(0xFF9A9DA8);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 17, vertical: 15),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF1B1D27), Color(0xFF12141B)],
        ),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF2A2D39)),
      ),
      child: Row(
        children: [
          Container(
            width: 10,
            height: 10,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Text(
              label,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          if (textAware)
            const Icon(
              Icons.text_fields_rounded,
              size: 20,
              color: Color(0xFF9EA8FF),
            ),
        ],
      ),
    );
  }
}

class _PermissionCard extends StatelessWidget {
  const _PermissionCard({required this.onPressed});
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(17),
      decoration: BoxDecoration(
        color: const Color(0xFF241D16),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF4A3825)),
      ),
      child: Row(
        children: [
          const Icon(Icons.layers_outlined, color: Color(0xFFFFC078)),
          const SizedBox(width: 13),
          const Expanded(
            child: Text('Darker needs permission to display over other apps.'),
          ),
          TextButton(onPressed: onPressed, child: const Text('Allow')),
        ],
      ),
    );
  }
}
