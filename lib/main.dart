import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final preferences = await SharedPreferences.getInstance();
  runApp(DarkerApp(preferences: preferences));
}

class DarkerApp extends StatelessWidget {
  const DarkerApp({super.key, this.preferences});

  final SharedPreferences? preferences;

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
      home: DimmerPage(preferences: preferences),
    );
  }
}

class DimmerPage extends StatefulWidget {
  const DimmerPage({super.key, this.preferences});

  final SharedPreferences? preferences;

  @override
  State<DimmerPage> createState() => _DimmerPageState();
}

class _DimmerPageState extends State<DimmerPage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.desu1243.darker/overlay');
  static const _maxNativeOverlayOpacity = 0.70;
  static const _screenOpacityKey = 'screenOpacity';
  static const _textOpacityKey = 'textOpacity';
  static const _textAwareKey = 'textAware';
  static const _textMarginKey = 'textMargin';
  static const _cornerRadiusKey = 'cornerRadius';
  static const _edgeSoftnessKey = 'edgeSoftness';
  static const _refreshIntervalKey = 'refreshIntervalMs';

  // User-facing opacity describes how visible the underlying screen remains:
  // 100% means no dimming, while 0% means maximum safe dimming.
  double _screenOpacity = 0.50;
  double _textOpacity = 0.75;
  bool _textAware = true;
  bool _keepOverlayAfterClosing = false;
  final _pageController = PageController();
  int _selectedPage = 0;
  bool _hasOverlayPermission = false;
  bool _overlayRunning = false;
  bool _captureRunning = false;
  bool _busy = true;
  double _textMargin = 12;
  double _cornerRadius = 10;
  double _edgeSoftness = 12;
  double _refreshIntervalMs = 400;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _channel.setMethodCallHandler(_handleNativeCallback);
    _restoreSettings();
    _refreshNativeState();
  }

  void _restoreSettings() {
    final preferences = widget.preferences;
    if (preferences == null) return;

    _screenOpacity = (preferences.getDouble(_screenOpacityKey) ?? 0.50).clamp(
      0.0,
      1.0,
    );
    _textOpacity = (preferences.getDouble(_textOpacityKey) ?? 0.75).clamp(
      _screenOpacity,
      1.0,
    );
    _textAware = preferences.getBool(_textAwareKey) ?? true;
    _refreshIntervalMs = (preferences.getDouble(_refreshIntervalKey) ?? 400)
        .clamp(50.0, 500.0);
    _keepOverlayAfterClosing =
        preferences.getBool('keepOverlayAfterClosing') ?? false;
    _textMargin = (preferences.getDouble(_textMarginKey) ?? 12).clamp(
      0.0,
      32.0,
    );
    _cornerRadius = (preferences.getDouble(_cornerRadiusKey) ?? 10).clamp(
      0.0,
      32.0,
    );
    _edgeSoftness = (preferences.getDouble(_edgeSoftnessKey) ?? 12).clamp(
      0.0,
      32.0,
    );
  }

  void _saveDouble(String key, double value) {
    final preferences = widget.preferences;
    if (preferences != null) unawaited(preferences.setDouble(key, value));
  }

  void _saveBool(String key, bool value) {
    final preferences = widget.preferences;
    if (preferences != null) unawaited(preferences.setBool(key, value));
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _channel.setMethodCallHandler(null);
    _pageController.dispose();
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
      await _channel.invokeMethod<void>('setKeepOverlayAfterClosing', {
        'enabled': _keepOverlayAfterClosing,
      });
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
        if (_overlayRunning) {
          _textAware = state?['textAware'] as bool? ?? _textAware;
        }
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
          'textMarginDp': _textMargin,
          'cornerRadiusDp': _cornerRadius,
          'featherDp': _edgeSoftness,
          'refreshIntervalMs': _refreshIntervalMs.round(),
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
    _saveDouble(_screenOpacityKey, _screenOpacity);
    _saveDouble(_textOpacityKey, _textOpacity);
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
    _saveDouble(_textOpacityKey, safeValue);
    if (!_overlayRunning) return;
    try {
      await _channel.invokeMethod<void>('updateTextOpacity', {
        'textDimAmount': _nativeOpacityFromScreen(safeValue),
      });
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Could not update the text opacity.');
    }
  }

  void _updateTextAware(bool value) {
    setState(() => _textAware = value);
    _saveBool(_textAwareKey, value);
  }

  Future<void> _updateRefreshInterval(double value) async {
    setState(() => _refreshIntervalMs = value);
    _saveDouble(_refreshIntervalKey, value);
    if (!_overlayRunning) return;
    try {
      await _channel.invokeMethod<void>('updateRefreshInterval', {
        'refreshIntervalMs': value.round(),
      });
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Could not update the refresh interval.');
    }
  }

  void _updateTextMargin(double value) {
    setState(() => _textMargin = value);
    _saveDouble(_textMarginKey, value);
  }

  void _updateCornerRadius(double value) {
    setState(() => _cornerRadius = value);
    _saveDouble(_cornerRadiusKey, value);
  }

  void _updateEdgeSoftness(double value) {
    setState(() => _edgeSoftness = value);
    _saveDouble(_edgeSoftnessKey, value);
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
        child: PageView(
          controller: _pageController,
          onPageChanged: (index) => setState(() => _selectedPage = index),
          children: [
            ListView(
              key: const PageStorageKey('home'),
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
                const _CalibrationSamples(),
                const SizedBox(height: 14),
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
                            Icon(Icons.brightness_low_sharp),
                            Icon(Icons.brightness_high_sharp),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                if (_textAware) ...[
                  const SizedBox(height: 14),
                  _Panel(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'Text opacity',
                              style: TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              '$textPercentage%',
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
                          value: _textOpacity.clamp(_screenOpacity, 1.0),
                          min: _screenOpacity,
                          max: 1,
                          divisions: textOpacityDivisions,
                          label: '$textPercentage%',
                          onChanged: _busy || _screenOpacity >= 1
                              ? null
                              : _updateTextOpacity,
                        ),
                        const Padding(
                          padding: EdgeInsets.symmetric(horizontal: 4),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Icon(
                                Icons.text_fields_rounded,
                                color: Color(0xFF61636C),
                                semanticLabel: 'Dim text',
                              ),
                              Icon(
                                Icons.text_fields_rounded,
                                color: Color(0xFFE4E5ED),
                                semanticLabel: 'Bright text',
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
                if (!_hasOverlayPermission) ...[
                  const SizedBox(height: 14),
                  _PermissionCard(onPressed: _requestOverlayPermission),
                ],
              ],
            ),
            _buildSettings(),
          ],
        ),
      ),
      bottomNavigationBar: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SafeArea(
            top: false,
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(22, 12, 22, 10),
              child: FilledButton.icon(
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
            ),
          ),
          NavigationBar(
            selectedIndex: _selectedPage,
            onDestinationSelected: (index) => _pageController.animateToPage(
              index,
              duration: const Duration(milliseconds: 250),
              curve: Curves.easeOut,
            ),
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.dark_mode_outlined),
                selectedIcon: Icon(Icons.dark_mode),
                label: 'Dimmer',
              ),
              NavigationDestination(
                icon: Icon(Icons.settings_outlined),
                selectedIcon: Icon(Icons.settings),
                label: 'Settings',
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _updateKeepOverlayAfterClosing(bool value) async {
    try {
      await _channel.invokeMethod<void>('setKeepOverlayAfterClosing', {
        'enabled': value,
      });
      if (!mounted) return;
      setState(() => _keepOverlayAfterClosing = value);
      _saveBool('keepOverlayAfterClosing', value);
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Could not update overlay behavior.');
    }
  }

  Widget _buildSettings() {
    return ListView(
      key: const PageStorageKey('settings'),
      padding: const EdgeInsets.fromLTRB(22, 24, 22, 28),
      children: [
        const Text(
          'Settings',
          style: TextStyle(fontSize: 27, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 24),
        _Panel(
          child: Column(
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text(
                  'Enable Text Aware',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
                ),
                subtitle: const Padding(
                  padding: EdgeInsets.only(top: 5),
                  child: Text(
                    'Detect text on screen and keep its surroundings clear.',
                  ),
                ),
                value: _textAware,
                onChanged: _overlayRunning || _busy ? null : _updateTextAware,
              ),

              if (_textAware) ...[
                const SizedBox(height: 14),
                _StyleSlider(
                  label: 'Refresh interval',
                  value: _refreshIntervalMs,
                  min: 50,
                  max: 500,
                  suffix: 'ms',
                  enabled: !_busy,
                  onChanged: _updateRefreshInterval,
                ),
                const Text(
                  'Lower values respond faster but use more battery. Actual updates depend on text recognition speed.',
                  style: TextStyle(color: Color(0xFF858894), height: 1.35),
                ),
                const SizedBox(height: 14),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  childrenPadding: const EdgeInsets.only(top: 8),
                  shape: const Border(),
                  collapsedShape: const Border(),
                  title: const Text(
                    'Text appearance',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                  ),
                  children: [
                    _StyleSlider(
                      label: 'Text margin',
                      value: _textMargin,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: _updateTextMargin,
                    ),
                    _StyleSlider(
                      label: 'Corner radius',
                      value: _cornerRadius,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: _updateCornerRadius,
                    ),
                    _StyleSlider(
                      label: 'Edge softness',
                      value: _edgeSoftness,
                      min: 0,
                      max: 32,
                      suffix: 'dp',
                      enabled: !_overlayRunning && !_busy,
                      onChanged: _updateEdgeSoftness,
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        _Panel(
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Keep overlay active after closing the app'),
            subtitle: const Text(
              'Keep dimming when Darker is removed from recent apps.',
            ),
            value: _keepOverlayAfterClosing,
            onChanged: _busy ? null : _updateKeepOverlayAfterClosing,
          ),
        ),
      ],
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

class _ColorCalibrationCard extends StatelessWidget {
  const _ColorCalibrationCard();

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: Color(0xFF252832)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Color calibration',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 10),
            ClipRRect(
              borderRadius: BorderRadius.circular(13),
              child: const AspectRatio(
                aspectRatio: 16 / 7,
                child: CustomPaint(painter: _ColorBarsPainter()),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CalibrationSamples extends StatelessWidget {
  const _CalibrationSamples();

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(
                width: constraints.maxWidth * 0.9,
                child: const _ColorCalibrationCard(),
              ),
              const SizedBox(width: 14),
              SizedBox(
                width: constraints.maxWidth * 0.9,
                child: const _ContrastSample(
                  label: 'Large text (3:1)',
                  backgroundColor: Color(0xFF808080),
                  foregroundColor: Color(0xFF414141),
                  fontSize: 18,
                  text: 'The contrast between this text and its background is 3:1, meeting the minimum recommended contrast ratio for large text. Make sure the text remains readable even when filter is on.',
                ),
              ),
              const SizedBox(width: 14),
              SizedBox(
                width: constraints.maxWidth * 0.9,
                child: const _ContrastSample(
                  label: 'Small text (4.5:1)',
                  backgroundColor: Color(0xFF808080),
                  foregroundColor: Color(0xFF181818),
                  fontSize: 12,
                  text: 'The contrast between this text and its background is 4.5:1, meeting the minimum recommended contrast ratio for small text. Make sure the text remains readable even when filter is on.',
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ContrastSample extends StatelessWidget {
  const _ContrastSample({
    required this.label,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.fontSize,
    required this.text,
  });

  final String label;
  final Color backgroundColor;
  final Color foregroundColor;
  final double fontSize;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: foregroundColor.withValues(alpha: 0.24)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: TextStyle(
              color: foregroundColor.withValues(alpha: 0.82),
              fontSize: 11,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            text,
            style: TextStyle(
              color: foregroundColor,
              fontSize: fontSize,
              fontWeight: FontWeight.w600,
              height: 1.32,
            ),
          ),
        ],
      ),
    );
  }
}

class _ColorBarsPainter extends CustomPainter {
  const _ColorBarsPainter();

  @override
  void paint(Canvas canvas, Size size) {
    const bars = [
      Color(0xFFD9D9D9),
      Color(0xFFD4D65A),
      Color(0xFF55D4D2),
      Color(0xFF59CC67),
      Color(0xFFD05DCA),
      Color(0xFFD35454),
      Color(0xFF545ECE),
    ];
    final topHeight = size.height * 0.72;
    final barWidth = size.width / bars.length;
    final paint = Paint();

    for (var index = 0; index < bars.length; index++) {
      paint.color = bars[index];
      canvas.drawRect(
        Rect.fromLTWH(index * barWidth, 0, barWidth + 1, topHeight),
        paint,
      );
    }

    const lowerBars = [
      Color(0xFF2635B7),
      Color(0xFFFFFFFF),
      Color(0xFF74239F),
      Color(0xFF161616),
      Color(0xFF0B7E81),
      Color(0xFF121212),
      Color(0xFFD4D4D4),
    ];
    for (var index = 0; index < lowerBars.length; index++) {
      paint.color = lowerBars[index];
      canvas.drawRect(
        Rect.fromLTWH(index * barWidth, topHeight, barWidth + 1, size.height),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _ColorBarsPainter oldDelegate) => false;
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
