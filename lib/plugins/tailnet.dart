import 'dart:convert';
import 'package:flutter/services.dart';

class Tailnet {
  static const _channel = MethodChannel('flclash/tailnet');
  static Map<String, dynamic> lastSnapshot = {};

  static Future<Map<String, dynamic>> status() async {
    final raw = await _channel.invokeMethod<String>('status');
    return lastSnapshot = jsonDecode(raw!) as Map<String, dynamic>;
  }

  static Future<void> setEnabled(bool enabled) async {
    await _channel.invokeMethod<void>('enabled', enabled);
    lastSnapshot = {...lastSnapshot, 'enabled': enabled};
  }

  static Future<void> login(String controlUrl) =>
      _channel.invokeMethod<void>('login', controlUrl);
  static Future<bool> send(String peerId) async =>
      await _channel.invokeMethod<bool>('send', peerId) ?? false;
  static Future<bool> save(String name) async =>
      await _channel.invokeMethod<bool>('save', name) ?? false;
}
