import 'package:darker/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
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
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('home')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('Opacity'), findsOneWidget);
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Enable Text Aware'));
    await tester.pumpAndSettle();
    expect(preferences.getBool('textAware'), isFalse);
    await tester.tap(find.text('Enable Text Aware'));
    await tester.pumpAndSettle();
    expect(preferences.getBool('textAware'), isTrue);
    await tester.scrollUntilVisible(
      find.text('Keep overlay active after closing the app'),
      200,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('settings')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Keep overlay active after closing the app'));
    await tester.pumpAndSettle();
    expect(preferences.getBool('keepOverlayAfterClosing'), isTrue);
    await tester.tap(find.text('Dimmer'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('Text opacity'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('home')),
            matching: find.byType(Scrollable),
          )
          .first,
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
      'refreshIntervalMs': 125.0,
      'jitterTolerancePx': 7.0,
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
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('home')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('63%'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('82%'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('home')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('82%'), findsOneWidget);
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('125 ms'), findsOneWidget);
    expect(find.text('7 px'), findsOneWidget);
    expect(find.text('Text margin'), findsNothing);
    await tester.scrollUntilVisible(
      find.text('Text appearance'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('settings')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Text appearance'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Text appearance'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('19 dp'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('settings')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('19 dp'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('7 dp'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('settings')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('7 dp'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('24 dp'),
      300,
      scrollable: find
          .descendant(
            of: find.byKey(const PageStorageKey('settings')),
            matching: find.byType(Scrollable),
          )
          .first,
    );
    expect(find.text('24 dp'), findsOneWidget);
    expect(find.text('Detection frequency'), findsNothing);
    expect(find.text('Transition'), findsNothing);
    expect(find.text('Instant'), findsNothing);
  });

  testWidgets('updates and persists refresh interval while overlay is active', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    const channel = MethodChannel('com.desu1243.darker/overlay');
    final updates = <int>[];
    final toleranceUpdates = <double>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'checkOverlayPermission') return true;
      if (call.method == 'getOverlayState') {
        return <String, Object?>{
          'running': true,
          'captureRunning': true,
          'textAware': true,
        };
      }
      if (call.method == 'updateRefreshInterval') {
        updates.add((call.arguments as Map)['refreshIntervalMs'] as int);
      }
      if (call.method == 'updateJitterTolerance') {
        toleranceUpdates.add(
          (call.arguments as Map)['jitterTolerancePx'] as double,
        );
      }
      return null;
    });
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      ),
    );
    await tester.pumpWidget(DarkerApp(preferences: preferences));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    final slider = find.byWidgetPredicate(
      (widget) => widget is Slider && widget.min == 50 && widget.max == 500,
    );
    expect(find.text('400 ms'), findsOneWidget);
    tester.widget<Slider>(slider).onChanged!(50);
    await tester.pumpAndSettle();
    expect(preferences.getDouble('refreshIntervalMs'), 50);
    expect(find.text('50 ms'), findsOneWidget);
    tester.widget<Slider>(slider).onChanged!(500);
    await tester.pumpAndSettle();
    expect(preferences.getDouble('refreshIntervalMs'), 500);
    expect(updates, [50, 500]);
    final toleranceSlider = find.byWidgetPredicate(
      (widget) => widget is Slider && widget.min == 0 && widget.max == 20,
    );
    expect(find.text('5 px'), findsOneWidget);
    tester.widget<Slider>(toleranceSlider).onChanged!(0);
    await tester.pumpAndSettle();
    expect(preferences.getDouble('jitterTolerancePx'), 0);
    tester.widget<Slider>(toleranceSlider).onChanged!(8);
    await tester.pumpAndSettle();
    expect(preferences.getDouble('jitterTolerancePx'), 8);
    expect(toleranceUpdates, [0, 8]);
  });
}
