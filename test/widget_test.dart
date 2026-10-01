import 'package:darker/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('shows the dimmer controls', (tester) async {
    await tester.pumpWidget(const DarkerApp());
    await tester.pump();

    expect(find.text('Darker'), findsOneWidget);
    expect(find.text('Opacity'), findsOneWidget);
    expect(find.text('Text Aware'), findsOneWidget);
    expect(find.byType(DimmerPage), findsOneWidget);
  });
}
