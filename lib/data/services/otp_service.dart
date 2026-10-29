import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// Twilio Verify via a tiny backend proxy (never embed Twilio auth token in the app).
///
/// URL resolution order:
/// 1. Saved Settings URL (`twilio_verify_url`)
/// 2. `--dart-define=TWILIO_VERIFY_URL=https://host`
/// 3. Live Render proxy `https://zeppay.onrender.com`
///
/// If the URL is cleared, debug OTP `123456` is accepted.
class OtpService {
  OtpService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;
  static const compiledUrl = String.fromEnvironment(
    'TWILIO_VERIFY_URL',
    defaultValue: 'https://zeppay.onrender.com',
  );
  static const prefsKey = 'twilio_verify_url';

  /// The free-tier Render proxy sleeps when idle, and a cold start can take
  /// 30-60s. Only health() retries (a GET changes nothing on the backend).
  /// send() NEVER retries: the backend has no idempotency guard on
  /// /verify/start, and neither a timeout nor a 503 proves the request was
  /// not accepted - a blind retry could text the user a second OTP. Instead
  /// send() runs a health preflight to wake the proxy first, then places a
  /// single request.
  static const _quickWait = Duration(seconds: 6);
  static const _normalWait = Duration(seconds: 20);
  static const _coldStartWait = Duration(seconds: 45);
  static const _wakePause = Duration(seconds: 3);

  Future<String> resolveUrl() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = (prefs.getString(prefsKey) ?? '').trim();
    final raw = saved.isNotEmpty ? saved : compiledUrl.trim();
    return raw.replaceAll(RegExp(r'/+$'), '');
  }

  Future<bool> isLive() async => (await resolveUrl()).isNotEmpty;

  Future<void> saveUrl(String url) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(prefsKey, url.trim().replaceAll(RegExp(r'/+$'), ''));
  }

  /// health()-only helper: while the proxy wakes, the GET either hangs
  /// (timeout) or the edge answers 503. Both are safe to retry once with a
  /// longer wait because GET /health changes nothing on the backend. Never
  /// use this for calls with side effects (see send()).
  Future<http.Response> _withWakeRetry(
    Future<http.Response> Function() call, {
    Duration first = _normalWait,
    Duration again = _coldStartWait,
  }) async {
    http.Response? res;
    try {
      res = await call().timeout(first);
    } on TimeoutException {
      res = null; // still waking; fall through to the longer retry
    }
    if (res != null && res.statusCode != 503) return res;
    if (res != null) await Future.delayed(_wakePause); // edge said waking
    return call().timeout(again);
  }

  Future<Map<String, dynamic>> health() async {
    final base = await resolveUrl();
    if (base.isEmpty) return const {'ok': false};
    try {
      final res = await _withWakeRetry(
        () => _client.get(Uri.parse('$base/health')),
        first: _quickWait,
        again: _coldStartWait,
      );
      if (res.statusCode >= 500) return {'ok': false};
      final body = jsonDecode(res.body);
      if (body is Map) return Map<String, dynamic>.from(body);
      return {'ok': res.statusCode < 500};
    } catch (_) {
      return const {'ok': false};
    }
  }

  Future<bool> ping() async {
    final h = await health();
    return h['ok'] == true || h['twilio'] == true || h['supabase'] == true;
  }

  Future<void> send(String phone) async {
    final base = await resolveUrl();
    if (base.isEmpty) {
      debugPrint('OTP dev mode: use 123456 for $phone');
      return;
    }
    // Preflight wakes the proxy (health retries internally for ~50s), so the
    // single POST below almost always lands on a warm server. If the preflight
    // fails we throw before any SMS can be sent; the login screen turns that
    // into a "server is waking up, try again" message.
    if (!await ping()) {
      throw TimeoutException('OTP server is not reachable yet');
    }
    final res = await _client
        .post(
          Uri.parse('$base/verify/start'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'phone': phone}),
        )
        .timeout(_coldStartWait);
    if (res.statusCode >= 400) {
      throw Exception(_err(res, 'Could not send OTP'));
    }
  }

  Future<bool> check(String phone, String code) async {
    final base = await resolveUrl();
    if (base.isEmpty) {
      return code == '123456';
    }
    final res = await _client
        .post(
          Uri.parse('$base/verify/check'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'phone': phone, 'code': code}),
        )
        .timeout(_normalWait);
    if (res.statusCode >= 400) return false;
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body['approved'] == true || body['status'] == 'approved';
  }

  String _err(http.Response res, String fallback) {
    try {
      final body = jsonDecode(res.body);
      if (body is Map && body['error'] != null) return '${body['error']}';
    } catch (_) {}
    return '$fallback (${res.statusCode})';
  }
}
