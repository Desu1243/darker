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

class _DimmerPageState extends State<DimmerPage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.desu1243.darker/overlay');
  static const _maxOpacity = 0.78;

  double _opacity = 0.45;
  bool _textAware = false;
  bool _hasOverlayPermission = false;
  bool _overlayRunning = false;
  bool _captureRunning = false;
  bool _busy = true;

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
          'Bez zgody na przechwytywanie tryb Text Aware nie wystartuje.',
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
        if (_overlayRunning && nativeOpacity != null) {
          _opacity = nativeOpacity.toDouble().clamp(0, _maxOpacity);
        }
        _busy = false;
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      _showMessage(error.message ?? 'Nie udało się odczytać stanu aplikacji.');
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
        _showMessage(error.message ?? 'Nie udało się zatrzymać nakładki.');
      }
      return;
    }

    if (!_hasOverlayPermission) {
      _showMessage(
        'Najpierw zezwól aplikacji na wyświetlanie nad innymi aplikacjami.',
      );
      await _requestOverlayPermission();
      return;
    }

    setState(() => _busy = true);
    try {
      final response = await _channel.invokeMethod<Map<Object?, Object?>>(
        'startOverlay',
        {'opacity': _opacity, 'textAware': _textAware},
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
      _showMessage(error.message ?? 'Nie udało się uruchomić nakładki.');
    }
  }

  Future<void> _updateOpacity(double value) async {
    setState(() => _opacity = value);
    if (!_overlayRunning) return;
    try {
      await _channel.invokeMethod<void>('updateOpacity', {'opacity': value});
    } on PlatformException catch (error) {
      _showMessage(error.message ?? 'Nie udało się zmienić przyciemnienia.');
    }
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final percentage = (_opacity * 100).round();
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
                        'Przyciemnienie',
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
                    value: _opacity,
                    min: 0,
                    max: _maxOpacity,
                    divisions: 78,
                    label: '$percentage%',
                    onChanged: _busy ? null : _updateOpacity,
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 4),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Brak',
                          style: TextStyle(color: Color(0xFF858894)),
                        ),
                        Text(
                          'Bezpieczne maksimum',
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
              child: SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text(
                  'Text Aware',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
                ),
                subtitle: const Padding(
                  padding: EdgeInsets.only(top: 5),
                  child: Text(
                    'Przygotuj przechwytywanie ekranu do wykrywania tekstu. '
                    'Maska OCR zostanie dodana w kolejnym etapie.',
                  ),
                ),
                value: _textAware,
                onChanged: _overlayRunning || _busy
                    ? null
                    : (value) => setState(() => _textAware = value),
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
                _overlayRunning ? 'Wyłącz nakładkę' : 'Włącz nakładkę',
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const SizedBox(height: 14),
            const Text(
              'Nakładka nie przechwytuje dotyku. Maksymalna wartość została '
              'ograniczona do 78%, czyli nieco poniżej progu bezpieczeństwa Androida.',
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
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: const Color(0xFF252832)),
      ),
      child: child,
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
        ? 'Nakładka wyłączona'
        : captureRunning
        ? 'Nakładka i Text Aware aktywne'
        : 'Nakładka aktywna';
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
            child: Text(
              'Darker potrzebuje zgody na wyświetlanie nad innymi aplikacjami.',
            ),
          ),
          TextButton(onPressed: onPressed, child: const Text('Zezwól')),
        ],
      ),
    );
  }
}
