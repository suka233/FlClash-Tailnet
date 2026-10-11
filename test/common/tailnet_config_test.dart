import 'package:fl_clash/common/tailnet_config.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yaml/yaml.dart';

void main() {
  const profile =
      'mode: global\nproxies: []\nrules: ["MATCH,DIRECT"]\ndns: {enable: true, fake-ip-filter: ["+.lan"], nameserver-policy: {"+.example.com": "1.1.1.1"}}\n';
  final snapshot = <String, dynamic>{
    'enabled': true,
    'socksPort': 12001,
    'username': 'local',
    'password': 'secret',
    'dnsPort': 12002,
    'status': {
      'MagicDNSSuffix': 'test.ts.net',
      'Peer': {
        'key': {
          'TailscaleIPs': ['100.100.1.2', 'fd7a:115c:a1e0::2'],
          'DNSName': 'peer.test.ts.net.',
          'PrimaryRoutes': ['192.168.7.0/24', '0.0.0.0/0', '::/0'],
        },
      },
    },
  };
  test('disabled leaves the original profile byte-for-byte unchanged', () {
    expect(injectTailnet(profile, {'enabled': false}), profile);
  });
  test('tailnet routes precede MATCH and exclude exit-node default routes', () {
    final config = loadYaml(injectTailnet(profile, snapshot)) as Map;
    expect(config['mode'], 'rule');
    expect(config['rules'].last, 'MATCH,DIRECT');
    expect(
      config['rules'],
      contains('IP-CIDR,192.168.7.0/24,FlClash-Tailnet,no-resolve'),
    );
    expect(
      config['rules'],
      contains('IP-CIDR6,fd7a:115c:a1e0::2/128,FlClash-Tailnet,no-resolve'),
    );
    expect(config['rules'].toString(), isNot(contains(',0.0.0.0/0,')));
    expect(config['rules'].toString(), isNot(contains(',::/0,')));
    expect(config['proxies'].single['udp'], true);
    expect(config['proxies'].single['password'], 'secret');
  });
  test('MagicDNS preserves unrelated DNS policy and maps short host names', () {
    final config = loadYaml(injectTailnet(profile, snapshot)) as Map;
    expect(config['dns']['nameserver-policy']['+.example.com'], '1.1.1.1');
    expect(
      config['dns']['nameserver-policy']['+.test.ts.net'],
      'udp://127.0.0.1:12002',
    );
    expect(
      config['dns']['fake-ip-filter'],
      containsAll(['+.lan', '+.test.ts.net']),
    );
    expect(config['hosts']['peer'], '100.100.1.2');
    expect(config['hosts']['peer.test.ts.net'], '100.100.1.2');
  });
  test(
    'an unavailable backend keeps tailnet traffic away from internet MATCH',
    () {
      final config =
          loadYaml(
                injectTailnet(profile, {
                  ...snapshot,
                  'status': null,
                  'running': false,
                }),
              )
              as Map;
      expect(
        config['rules'].first,
        'IP-CIDR,100.64.0.0/10,FlClash-Tailnet,no-resolve',
      );
    },
  );
  test('reserved-name collisions fail instead of using an unrelated proxy', () {
    expect(
      () => injectTailnet('proxies: [{name: FlClash-Tailnet}]', snapshot),
      throwsFormatException,
    );
  });
  test(
    'startup keeps cached routes and MagicDNS until the backend is ready',
    () {
      final config =
          loadYaml(
                injectTailnet(profile, {
                  ...snapshot,
                  'routing': snapshot['status'],
                  'status': {'BackendState': 'Starting'},
                }),
              )
              as Map;
      expect(
        config['rules'],
        contains('DOMAIN-SUFFIX,test.ts.net,FlClash-Tailnet'),
      );
      expect(
        config['dns']['nameserver-policy']['+.test.ts.net'],
        'udp://127.0.0.1:12002',
      );
    },
  );
}
