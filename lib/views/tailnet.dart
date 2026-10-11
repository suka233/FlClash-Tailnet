import 'dart:async';
import 'dart:convert';
import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/plugins/tailnet.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/url_launcher.dart';

class TailnetView extends ConsumerStatefulWidget {
  const TailnetView({super.key});
  @override
  ConsumerState<TailnetView> createState() => _TailnetViewState();
}

class _TailnetViewState extends ConsumerState<TailnetView> {
  final _control = TextEditingController(
    text: 'https://controlplane.tailscale.com',
  );
  Timer? _timer;
  Timer? _loginTimer;
  Map<String, dynamic> _snapshot = {};
  bool _busy = false;
  bool _polling = false;
  String? _error;
  String? _actionError;
  String? _routingSignature;
  bool _controlLoaded = false;
  bool _awaitingLogin = false;
  bool _loginTimedOut = false;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    _timer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (!_busy) unawaited(_refresh());
    });
  }

  Future<void> _refresh() async {
    if (_polling) return;
    _polling = true;
    try {
      final snapshot = await Tailnet.status();
      if (!mounted) return;
      if (!_controlLoaded) {
        _controlLoaded = true;
        _control.text = snapshot['controlUrl'] as String? ?? _control.text;
      }
      final status = snapshot['status'] as Map? ?? {};
      final signature = jsonEncode([
        snapshot['enabled'],
        snapshot['socksPort'],
        snapshot['dnsPort'],
        status['BackendState'],
        status['MagicDNSSuffix'],
        for (final peer in (status['Peer'] as Map? ?? {}).values)
          [peer['TailscaleIPs'], peer['PrimaryRoutes'], peer['DNSName']],
      ]);
      setState(() {
        _snapshot = snapshot;
        _error = snapshot['error'] as String?;
        if (snapshot['enabled'] != true ||
            status['BackendState'] == 'Running') {
          _cancelLoginWait();
        }
      });
      if (_routingSignature != signature) {
        _routingSignature = signature;
        ref
            .read(setupActionProvider.notifier)
            .applyProfileDebounce(silence: true);
      }
      final auth = (status['AuthURL'] ?? '').toString();
      if (_awaitingLogin && auth.isNotEmpty) {
        setState(_cancelLoginWait);
        await _run(() => _openLogin(auth));
      }
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      _polling = false;
    }
  }

  Future<void> _run(Future<void> Function() operation) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _actionError = null;
    });
    try {
      await operation();
    } catch (error) {
      if (mounted) setState(() => _actionError = error.toString());
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        await _refresh();
      }
    }
  }

  Future<void> _enable(bool enabled) => _run(() async {
    if (enabled) {
      final allowed = await app?.requestNotificationsPermission();
      if (allowed == false) return;
      if (!mounted) return;
      ref
          .read(patchClashConfigProvider.notifier)
          .update((s) => s.copyWith(mode: Mode.rule));
    }
    await Tailnet.setEnabled(enabled);
    if (!enabled && mounted) setState(_cancelLoginWait);
  });

  void _cancelLoginWait() {
    _loginTimer?.cancel();
    _loginTimer = null;
    _awaitingLogin = false;
    _loginTimedOut = false;
  }

  Future<void> _login() => _run(() async {
    setState(_cancelLoginWait);
    await Tailnet.login(_control.text.trim());
    if (!mounted) return;
    setState(() => _awaitingLogin = true);
    _loginTimer = Timer(const Duration(seconds: 30), () {
      if (!mounted) return;
      setState(() {
        _awaitingLogin = false;
        _loginTimedOut = true;
        _loginTimer = null;
      });
    });
  });

  Future<void> _openLogin(String auth) async {
    final uri = Uri.tryParse(auth);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        (uri.scheme != 'https' && uri.scheme != 'http')) {
      throw MessageException(context.appLocalizations.tailnetInvalidLoginUrl);
    }
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (!mounted) return;
      throw MessageException(context.appLocalizations.tailnetOpenLoginFailed);
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    _loginTimer?.cancel();
    _control.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final enabled = _snapshot['enabled'] == true;
    final status = _snapshot['status'] as Map? ?? {};
    final auth = (status['AuthURL'] ?? '').toString();
    final health = (status['Health'] as List? ?? []).join('\n');
    final files = _snapshot['files'] as List? ?? [];
    final targets = _snapshot['targets'] as List? ?? [];
    return CommonScaffold(
      title: l.tailnetTitle,
      body: ListView(
        padding: EdgeInsets.only(top: context.appBarInset, bottom: 32),
        children: [
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.wifi),
            title: Text(l.tailnetEnable),
            subtitle: Text(l.tailnetRuleMode),
            value: enabled,
            onChanged: _busy ? null : _enable,
          ),
          ListItem(
            title: Text(l.tailnetStatus),
            subtitle: Text(
              '${status['BackendState'] ?? (_snapshot['running'] == true ? l.connecting : l.disconnected)}\n${(status['TailscaleIPs'] as List? ?? []).join(' · ')}',
            ),
          ),
          if (enabled && health.isNotEmpty)
            Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(
                health,
                style: TextStyle(color: context.colorScheme.error),
              ),
            ),
          if ((_actionError ?? _error) != null)
            Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(
                (_actionError ?? _error)!,
                style: TextStyle(color: context.colorScheme.error),
              ),
            ),
          if (_busy || _awaitingLogin) const LinearProgressIndicator(),
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _control,
              enabled: !_busy && !_awaitingLogin,
              decoration: InputDecoration(labelText: l.tailnetControlUrl),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: FilledButton(
              onPressed:
                  enabled &&
                      !_busy &&
                      !_awaitingLogin &&
                      _snapshot['running'] == true
                  ? _login
                  : null,
              child: Text(l.tailnetLogin),
            ),
          ),
          if (enabled && auth.isEmpty && status['BackendState'] == 'NeedsLogin')
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                _awaitingLogin
                    ? l.tailnetWaitingLogin
                    : _loginTimedOut
                    ? l.tailnetLoginTimeout
                    : l.tailnetLoginRequired,
              ),
            ),
          if (auth.isNotEmpty)
            Padding(
              padding: const EdgeInsets.all(16),
              child: FilledButton.tonal(
                onPressed: _busy ? null : () => _run(() => _openLogin(auth)),
                child: Text(l.tailnetOpenLogin),
              ),
            ),
          if (auth.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SelectableText(auth),
            ),
          ListHeader(title: l.tailnetSend),
          if (targets.isEmpty) ListItem(title: Text(l.tailnetNoTargets)),
          for (final target in targets)
            ListItem(
              title: Text(
                (target['Node']?['Name'] ??
                        target['Node']?['Hostinfo']?['Hostname'] ??
                        '')
                    .toString(),
              ),
              subtitle: Text(l.tailnetChooseFile),
              onTap: _busy
                  ? null
                  : () => _run(() async {
                      final sent = await Tailnet.send(
                        target['Node']['StableID'].toString(),
                      );
                      if (context.mounted && sent) {
                        context.showSnackBar(l.tailnetSent);
                      }
                    }),
            ),
          ListHeader(title: l.tailnetInbox),
          if (files.isEmpty) ListItem(title: Text(l.tailnetNoFiles)),
          for (final file in files)
            ListItem(
              title: Text(file['Name'].toString()),
              subtitle: Text('${file['Size']} B · ${l.tailnetSave}'),
              onTap: _busy
                  ? null
                  : () => _run(() async {
                      final saved = await Tailnet.save(file['Name'].toString());
                      if (context.mounted && saved) {
                        context.showSnackBar(l.tailnetSaved);
                      }
                    }),
            ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(l.tailnetHotspotHelp),
          ),
        ],
      ),
    );
  }
}
