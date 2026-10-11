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
  const launcher = MethodChannel('plugins.flutter.io/url_launcher');
  TestWidgetsFlutterBinding.ensureInitialized();
  var enabled = false;
  var failSending = false;
  var failLogin = false;
  var browserAvailable = true;
  String? backendState;
  var auth = '';
  var health = <String>[];
  final calls = <MethodCall>[];
  final launches = <String>[];
  setUp(() {
    enabled = false;
    failSending = false;
    failLogin = false;
    browserAvailable = true;
    backendState = null;
    auth = '';
    health = [];
    calls.clear();
    launches.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(launcher, (call) async {
          launches.add((call.arguments as Map)['url'] as String);
          return browserAvailable;
        });
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
                  'BackendState':
                      backendState ?? (enabled ? 'Running' : 'NeedsLogin'),
                  'TailscaleIPs': ['100.100.1.2'],
                  'AuthURL': auth,
                  'Health': health,
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
            case 'login':
              if (failLogin) {
                throw PlatformException(
                  code: 'TAILNET',
                  message: 'Control connection failed',
                );
              }
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
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(launcher, null);
  });

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
  testWidgets('delayed sign-in link opens once after an explicit login', (
    tester,
  ) async {
    enabled = true;
    backendState = 'NeedsLogin';
    await pump(tester);
    await tester.enterText(
      find.byType(TextField),
      'https://headscale.example/',
    );
    await tester.tap(find.text(currentAppLocalizations.tailnetLogin));
    await tester.pump();
    await tester.pump();
    expect(find.text(currentAppLocalizations.tailnetWaitingLogin), findsOne);
    expect(find.byType(LinearProgressIndicator), findsOne);
    expect(launches, isEmpty);
    expect(
      calls.any(
        (c) =>
            c.method == 'login' && c.arguments == 'https://headscale.example/',
      ),
      true,
    );
    auth = 'https://headscale.example/register/test-key';
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(launches, [auth]);
    expect(find.text(auth), findsOne);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    await tester.pump(const Duration(seconds: 8));
    await tester.pumpAndSettle();
    expect(launches, [auth]);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'initial links require a tap and failed browser opens retain link',
    (tester) async {
      enabled = true;
      backendState = 'NeedsLogin';
      auth = 'https://headscale.example/register/test-key';
      browserAvailable = false;
      await pump(tester);
      expect(launches, isEmpty);
      await tester.tap(find.text(currentAppLocalizations.tailnetOpenLogin));
      await tester.pumpAndSettle();
      expect(launches, [auth]);
      expect(
        find.text(currentAppLocalizations.tailnetOpenLoginFailed),
        findsOne,
      );
      expect(find.text(auth), findsOne);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'missing link times out and exposes backend connection problems',
    (tester) async {
      enabled = true;
      backendState = 'NeedsLogin';
      health = ['Cannot reach the control server: TLS handshake failed'];
      await pump(tester);
      expect(find.text(health.single), findsOne);
      expect(find.text(currentAppLocalizations.tailnetLoginRequired), findsOne);
      await tester.tap(find.text(currentAppLocalizations.tailnetLogin));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(seconds: 29));
      expect(find.text(currentAppLocalizations.tailnetWaitingLogin), findsOne);
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text(currentAppLocalizations.tailnetLoginTimeout), findsOne);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      expect(launches, isEmpty);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('a rejected login leaves no pending indicator or timeout', (
    tester,
  ) async {
    enabled = true;
    backendState = 'NeedsLogin';
    failLogin = true;
    await pump(tester);
    await tester.tap(find.text(currentAppLocalizations.tailnetLogin));
    await tester.pumpAndSettle();
    expect(find.textContaining('Control connection failed'), findsOne);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    await tester.pump(const Duration(seconds: 31));
    expect(
      find.text(currentAppLocalizations.tailnetLoginTimeout),
      findsNothing,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('leaving a pending login cancels its timer and browser request', (
    tester,
  ) async {
    enabled = true;
    backendState = 'NeedsLogin';
    await pump(tester);
    await tester.tap(find.text(currentAppLocalizations.tailnetLogin));
    await tester.pump();
    await tester.pump();
    expect(find.byType(LinearProgressIndicator), findsOne);
    await tester.pumpWidget(const SizedBox());
    auth = 'https://headscale.example/register/test-key';
    await tester.pump(const Duration(seconds: 35));
    expect(launches, isEmpty);
    expect(tester.takeException(), null);
  });

  testWidgets('invalid sign-in link cannot launch a non-web URI', (
    tester,
  ) async {
    enabled = true;
    backendState = 'NeedsLogin';
    auth = 'file:///private/state';
    await pump(tester);
    await tester.tap(find.text(currentAppLocalizations.tailnetLogin));
    await tester.pumpAndSettle();
    expect(launches, isEmpty);
    expect(find.text(currentAppLocalizations.tailnetInvalidLoginUrl), findsOne);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
}
