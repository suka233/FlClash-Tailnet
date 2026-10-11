import 'dart:convert';
import 'package:yaml/yaml.dart';
import 'yaml.dart' as writer;

/// This adapter runs last, after user scripts and profile overrides.
/// Tailnet destinations cannot fall through into the user's internet proxy.
String injectTailnet(String text, Map<String, dynamic> snapshot) {
  if (snapshot['enabled'] != true) return text;
  final config = (jsonDecode(jsonEncode(loadYaml(text))) as Map)
      .cast<String, dynamic>();
  const name = 'FlClash-Tailnet';
  final proxies = List<dynamic>.from(config['proxies'] as List? ?? []);
  if (proxies.any((p) => p is Map && p['name'] == name)) {
    throw const FormatException('Reserved proxy name: FlClash-Tailnet');
  }
  proxies.add({
    'name': name,
    'type': 'socks5',
    'server': '127.0.0.1',
    'port': snapshot['socksPort'],
    'username': snapshot['username'],
    'password': snapshot['password'],
    'udp': true,
  });
  config['proxies'] = proxies;
  config['mode'] = 'rule';
  final live = snapshot['status'] as Map?;
  final status =
      (live?['BackendState'] == 'Running'
          ? live
          : (snapshot['routing'] as Map? ?? live)) ??
      {};
  final peers = (status['Peer'] as Map?)?.values ?? [];
  final routes = <String>{'100.64.0.0/10', 'fd7a:115c:a1e0::/48'};
  for (final peer in [status['Self'], ...peers]) {
    if (peer is! Map) continue;
    for (final ip in peer['TailscaleIPs'] as List? ?? []) {
      routes.add('$ip/${ip.toString().contains(':') ? 128 : 32}');
    }
    for (final route in peer['PrimaryRoutes'] as List? ?? []) {
      // Exit-node routes must never hijack the ordinary proxy/internet path.
      if (route != '0.0.0.0/0' && route != '::/0') routes.add(route.toString());
    }
  }
  final suffix = (status['MagicDNSSuffix'] ?? '').toString().replaceFirst(
    RegExp(r'\.$'),
    '',
  );
  final rules = [
    for (final route in routes)
      '${route.contains(':') ? 'IP-CIDR6' : 'IP-CIDR'},$route,$name,no-resolve',
    if (suffix.isNotEmpty) 'DOMAIN-SUFFIX,$suffix,$name',
    ...config['rules'] as List? ?? [],
  ];
  config['rules'] = rules;
  final dns = Map<String, dynamic>.from(config['dns'] as Map? ?? {});
  final exclusions = List<dynamic>.from(dns['fake-ip-filter'] as List? ?? []);
  if (suffix.isNotEmpty && (snapshot['dnsPort'] as num? ?? 0) > 0) {
    switch (dns['fake-ip-filter-mode']) {
      case 'rule':
        exclusions.insert(0, 'DOMAIN-SUFFIX,$suffix,real-ip');
      case 'whitelist':
        break;
      default:
        exclusions.add('+.$suffix');
    }
    dns['fake-ip-filter'] = exclusions;
    dns['nameserver-policy'] = {
      ...dns['nameserver-policy'] as Map? ?? {},
      '+.$suffix': 'udp://127.0.0.1:${snapshot['dnsPort']}',
    };
    dns['enable'] = true;
  }
  config['dns'] = dns;
  final hosts = Map<String, dynamic>.from(config['hosts'] as Map? ?? {});
  for (final peer in [status['Self'], ...peers]) {
    if (peer is! Map) continue;
    final ips = peer['TailscaleIPs'] as List? ?? [];
    final dnsName = (peer['DNSName'] ?? '').toString().replaceFirst(
      RegExp(r'\.$'),
      '',
    );
    if (dnsName.isNotEmpty && ips.isNotEmpty) {
      hosts[dnsName] = ips.first;
      hosts[dnsName.split('.').first] = ips.first;
    }
  }
  config['hosts'] = hosts;
  dns['use-hosts'] = true;
  return writer.yaml.encode(config);
}
