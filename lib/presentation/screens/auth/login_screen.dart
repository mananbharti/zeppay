import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/widgets/chrome.dart';
import '../../../core/widgets/illustrations.dart';
import '../../../core/widgets/ux.dart';
import '../../../data/local/ux_prefs.dart';
import '../../../data/services/providers.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _phone = TextEditingController();
  var _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    UxPrefs.lastPhone().then((v) {
      if (!mounted || v.length < 10) return;
      final d = v.replaceAll(RegExp(r'\D'), '');
      _phone.text = d.length >= 10 ? d.substring(d.length - 10) : d;
      setState(() {});
    });
  }

  @override
  void dispose() {
    _phone.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    var raw = _phone.text.replaceAll(RegExp(r'\D'), '');
    if (raw.startsWith('91') && raw.length >= 12) {
      raw = raw.substring(raw.length - 10);
    }
    if (raw.length != 10) {
      setState(() => _error = 'Enter a valid 10-digit mobile number');
      return;
    }
    final phone = '+91$raw';
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(otpServiceProvider).send(phone);
      await UxPrefs.savePhone(phone);
      ref.read(pendingPhoneProvider.notifier).state = phone;
      if (mounted) context.go('/otp');
    } catch (e) {
      final msg = '$e'.replaceFirst('Exception: ', '');
      setState(
        () => _error = msg.contains('TimeoutException')
            ? 'Server is waking up. Wait a few seconds, then tap SEND OTP again.'
            : msg,
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ready = _phone.text.replaceAll(RegExp(r'\D'), '').length >= 10;
    return AuthBackdrop(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
        children: [
            const GoalBar(
              done: 2,
              total: 4,
              label: 'App open · number next',
            ),
            const SizedBox(height: 12),
            const Center(child: ZepIllustration(ZepArt.otp, size: 168)),
            const SizedBox(height: 18),
            Text(
              'Your number.',
              style: Theme.of(
                context,
              ).textTheme.displayMedium?.copyWith(height: 1.12),
            ),
            const SizedBox(height: 8),
            Text(
              'OTP goes to the number you type. Then your name, photo, and UPI ID.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.4),
            ),
            const SizedBox(height: 16),
            GlassPanel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'MOBILE',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: AppColors.hero,
                      letterSpacing: 1.6,
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: _phone,
                    keyboardType: TextInputType.phone,
                    onChanged: (_) => setState(() {}),
                    inputFormatters: [
                      FilteringTextInputFormatter.digitsOnly,
                      LengthLimitingTextInputFormatter(10),
                    ],
                    style: const TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 0.8,
                    ),
                    decoration: const InputDecoration(
                      hintText: '98765 43210',
                      prefixText: '+91  ',
                    ),
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 10),
                    Text(
                      _error!,
                      style: const TextStyle(color: AppColors.danger),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 10),
            const LossNote(
              text:
                  'Walk away here and this phone stays unsigned-in — nothing is saved yet.',
            ),
            const SizedBox(height: 14),
            GlowButton(
              label: ready ? 'SEND OTP' : 'ENTER 10 DIGITS',
              onTap: _busy || !ready ? null : _send,
              busy: _busy,
            ),
            Center(
              child: ref
                  .watch(otpLiveProvider)
                  .when(
                    data: (live) => Text(
                      live
                          ? 'We’ll text this exact number.'
                          : 'No OTP URL yet — use 123456, or tap Twilio setup.',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.labelSmall,
                    ),
                    loading: () => const SizedBox.shrink(),
                    error: (_, __) => const SizedBox.shrink(),
                  ),
            ),
            TextButton(
              onPressed: () => context.push('/verify-setup'),
              child: const Text('Twilio setup'),
            ),
        ],
      ),
    );
  }
}
