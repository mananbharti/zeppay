import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:zeppay/data/services/otp_service.dart';

/// Counts GETs (health preflight) and POSTs (actual OTP sends) separately so
/// tests can prove no duplicate /verify/start call ever happens.
class _CountingClient extends MockClient {
  _CountingClient(super.handler);
  int gets = 0;
  int posts = 0;
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  _CountingClient client(
    Future<http.Response> Function(http.Request req) handler,
  ) {
    late _CountingClient c;
    c = _CountingClient((req) async {
      if (req.method == 'GET') c.gets++;
      if (req.method == 'POST') c.posts++;
      return handler(req);
    });
    return c;
  }

  group('OtpService cold-start resilience', () {
    test('send() on a warm server: one health preflight, one POST', () async {
      final c = client((req) async => req.method == 'GET'
          ? http.Response(jsonEncode({'ok': true, 'twilio': true}), 200)
          : http.Response(jsonEncode({'ok': true}), 200));
      await OtpService(client: c).send('+919999999999');
      expect(c.gets, 1);
      expect(c.posts, 1);
    });

    test('send() wakes a sleeping proxy via preflight, then sends once',
        () async {
      var healthCalls = 0;
      final c = client((req) async {
        if (req.method == 'GET') {
          healthCalls++;
          if (healthCalls == 1) throw TimeoutException('asleep');
          return http.Response(jsonEncode({'ok': true}), 200);
        }
        return http.Response(jsonEncode({'ok': true}), 200);
      });
      await OtpService(client: c).send('+919999999999');
      expect(healthCalls, 2);
      expect(c.posts, 1);
    });

    test('send() throws WITHOUT posting when the proxy never wakes', () async {
      final c = client((req) async =>
          req.method == 'GET'
              ? throw TimeoutException('asleep')
              : http.Response('{}', 200));
      await expectLater(
        OtpService(client: c).send('+919999999999'),
        throwsA(isA<TimeoutException>()),
      );
      expect(c.posts, 0); // no OTP texted, no duplicate risk
    });

    test('send() after preflight never retries a timed-out POST', () async {
      final c = client((req) async => req.method == 'GET'
          ? http.Response(jsonEncode({'ok': true}), 200)
          : throw TimeoutException('request may still arrive'));
      await expectLater(
        OtpService(client: c).send('+919999999999'),
        throwsA(isA<TimeoutException>()),
      );
      expect(c.posts, 1); // exactly one attempt - no second SMS
    });

    test('send() never retries a 503 POST (provenance ambiguous)', () async {
      final c = client((req) async => req.method == 'GET'
          ? http.Response(jsonEncode({'ok': true}), 200)
          : http.Response('waking', 503));
      await expectLater(
        OtpService(client: c).send('+919999999999'),
        throwsA(predicate((e) => '$e'.contains('503'))),
      );
      expect(c.posts, 1);
    });

    test('send() surfaces backend errors without retrying', () async {
      final c = client((req) async => req.method == 'GET'
          ? http.Response(jsonEncode({'ok': true}), 200)
          : http.Response(jsonEncode({'error': 'bad phone'}), 400));
      await expectLater(
        OtpService(client: c).send('+9100'),
        throwsA(predicate((e) => '$e'.contains('bad phone'))),
      );
      expect(c.posts, 1);
    });

    test('health() returns ok:false when the proxy never answers', () async {
      final svc = OtpService(
        client: MockClient((req) async => throw TimeoutException('asleep')),
      );
      expect(await svc.health(), {'ok': false});
    });

    test('health() retries after a timeout and parses the body', () async {
      var calls = 0;
      final svc = OtpService(
        client: MockClient((req) async {
          calls++;
          if (calls == 1) throw TimeoutException('asleep');
          return http.Response(jsonEncode({'ok': true, 'twilio': true}), 200);
        }),
      );
      final h = await svc.health();
      expect(h['ok'], isTrue);
      expect(h['twilio'], isTrue);
      expect(calls, 2);
    });

    test('health() retries a 503 once before giving up', () async {
      var calls = 0;
      final svc = OtpService(
        client: MockClient((req) async {
          calls++;
          return http.Response('waking', 503);
        }),
      );
      expect(await svc.health(), {'ok': false});
      expect(calls, 2);
    }, timeout: const Timeout(Duration(seconds: 15)));

    test('check() approves only on an approved status', () async {
      final svc = OtpService(
        client: MockClient((req) async {
          expect(req.url.path, '/verify/check');
          return http.Response(jsonEncode({'status': 'approved'}), 200);
        }),
      );
      expect(await svc.check('+919999999999', '123456'), isTrue);
    });
  });
}
