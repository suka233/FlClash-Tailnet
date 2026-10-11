import 'dart:convert';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/tailnet.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import '../helpers/test_app.dart';

class _SetupStub extends SetupAction {
  @override
  void build() {}
  @override
  void applyProfileDebounce({bool silence = false, bool force = false}) {}
}

void main() {
  const channel = MethodChannel('flclash/tailnet');
  TestWidgetsFlutterBinding.ensureInitialized();
  var enabled = false;
  var failSending = false;
  final calls = <MethodCall>[];
  setUp(() {
    enabled = false;
    failSending = false;
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'status':
              return jsonEncode({
                'enabled': enabled,
                'running': enabled,
                'socksPort': 12001,
                'dnsPort': 12002,
                'status': {
                  'BackendState': enabled ? 'Running' : 'NeedsLogin',
                  'TailscaleIPs': ['100.100.1.2'],
                },
                'targets': enabled
                    ? [
                        {
                          'Node': {
                            'Name': 'mac.test.ts.net.',
                            'StableID': 'stable123',
                          },
                        },
                      ]
                    : [],
                'files': [],
              });
            case 'enabled':
              enabled = call.arguments as bool;
              return null;
            case 'send':
              if (failSending) {
                throw PlatformException(
                  code: 'TAILNET',
                  message: 'Transfer failed',
                );
              }
              return true;
            default:
              return null;
          }
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1400, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final container = ProviderContainer(
      overrides: [setupActionProvider.overrideWith(_SetupStub.new)],
    );
    addTearDown(container.dispose);
    globalState.container = container;
    container
        .read(viewSizeProvider.notifier)
        .update((_) => const Size(1400, 1400));
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(child: TailnetView()),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'empty inbox and disabled state are actionable without loading forever',
    (tester) async {
      await pump(tester);
      expect(find.text(currentAppLocalizations.tailnetNoFiles), findsOne);
      expect(find.text(currentAppLocalizations.tailnetNoTargets), findsOne);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(
        calls.any((c) => c.method == 'enabled' && c.arguments == true),
        true,
      );
      expect(find.text('mac.test.ts.net.'), findsOne);
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'send passes the stable node ID and retains errors after refresh',
    (tester) async {
      enabled = true;
      failSending = true;
      await pump(tester);
      await tester.tap(find.text('mac.test.ts.net.'));
      await tester.pumpAndSettle();
      expect(
        calls.any((c) => c.method == 'send' && c.arguments == 'stable123'),
        true,
      );
      expect(find.textContaining('Transfer failed'), findsOne);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(tester.takeException(), null);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
