import 'package:darker/main.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows the dimmer controls', (tester) async {
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

    await tester.pumpWidget(const DarkerApp());
    await tester.pump();

    expect(find.text('Darker'), findsOneWidget);
    expect(find.text('Opacity'), findsOneWidget);
    expect(find.text('Text Aware'), findsOneWidget);
    expect(find.byType(DimmerPage), findsOneWidget);

    await tester.tap(find.text('Text Aware'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('Text opacity'), findsOneWidget);
    expect(find.text('75%'), findsOneWidget);
  });
}
