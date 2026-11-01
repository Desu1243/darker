import 'package:darker/main.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('shows the dimmer controls', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    const channel = MethodChannel('com.desu1243.darker/overlay');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => switch (call.method) {
        'checkOverlayPermission' => false,
        'getOverlayState' => <String, Object?>{
          'running': false,
          'captureRunning': false,
          'textAware': false,
        },
        _ => null,
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );

    await tester.pumpWidget(DarkerApp(preferences: preferences));
    await tester.pump();

    expect(find.text('Darker'), findsOneWidget);
    expect(find.text('Color calibration'), findsOneWidget);
    expect(find.text('Large text (3:1)'), findsOneWidget);
    expect(find.text('Small text (4.5:1)'), findsOneWidget);
    expect(find.byType(DimmerPage), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('Opacity'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Opacity'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Text Aware'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('Text Aware'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(preferences.getBool('textAware'), isTrue);
    await tester.scrollUntilVisible(
      find.text('Text opacity'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Text opacity'), findsOneWidget);
    expect(find.text('75%'), findsOneWidget);
    expect(find.text('Detection frequency'), findsNothing);
  });

  testWidgets('restores all user settings', (tester) async {
    SharedPreferences.setMockInitialValues({
      'screenOpacity': 0.63,
      'textOpacity': 0.82,
      'textAware': true,
      'textMargin': 19.0,
      'cornerRadius': 7.0,
      'edgeSoftness': 24.0,
    });
    final preferences = await SharedPreferences.getInstance();
    const channel = MethodChannel('com.desu1243.darker/overlay');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => switch (call.method) {
        'checkOverlayPermission' => false,
        'getOverlayState' => <String, Object?>{
          'running': false,
          'captureRunning': false,
          'textAware': false,
        },
        _ => null,
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );

    await tester.pumpWidget(DarkerApp(preferences: preferences));
    await tester.pump();

    await tester.scrollUntilVisible(
      find.text('63%'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('63%'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('82%'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('82%'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('19 dp'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('19 dp'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('7 dp'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('7 dp'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('24 dp'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('24 dp'), findsOneWidget);
    expect(find.text('Detection frequency'), findsNothing);
    expect(find.text('Transition'), findsNothing);
    expect(find.text('Instant'), findsNothing);
  });
}
