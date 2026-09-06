/// Parsed identity from a Zep Card NFC tag or deep link.
class ZepCardProfile {
  const ZepCardProfile({required this.vpa, required this.name});

  final String vpa;
  final String name;

  String get initials {
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) {
      final local = vpa.split('@').first;
      return local.isEmpty ? 'Z' : local.substring(0, 1).toUpperCase();
    }
    if (parts.length == 1) return parts.first.substring(0, 1).toUpperCase();
    return '${parts.first[0]}${parts[1][0]}'.toUpperCase();
  }
}

/// Standard UPI handle: local part [a-zA-Z0-9._-]+ @ PSP handle [a-zA-Z0-9]+
final RegExp _upiVpaPattern = RegExp(r'^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$');

bool isValidUpiVpa(String vpa) => _upiVpaPattern.hasMatch(vpa.trim());

/// Static NDEF payload: custom scheme + HTTPS fallback for phones without the app.
abstract final class ZepCardCodec {
  static const profileScheme = 'zeppay';
  static const profileHost = 'profile';
  static const webProfileBase =
      'https://mananbharti.github.io/zeppay/profile';

  static Uri appUri({required String vpa, required String name}) {
    return Uri(
      scheme: profileScheme,
      host: profileHost,
      queryParameters: {'vpa': vpa, 'name': name},
    );
  }

  static Uri webUri({required String vpa, required String name}) {
    return Uri.parse(webProfileBase).replace(
      queryParameters: {'vpa': vpa, 'name': name},
    );
  }

  static ZepCardProfile? parseUri(Uri? uri) {
    if (uri == null) return null;
    final vpa = uri.queryParameters['vpa']?.trim() ?? '';
    if (!isValidUpiVpa(vpa)) return null;
    final name = uri.queryParameters['name']?.trim() ?? '';
    final path = uri.path;
    final isProfile = uri.host == profileHost ||
        path.endsWith('/profile') ||
        path.contains('/profile');
    final isZepScheme = uri.scheme == profileScheme;
    final isWebFallback = uri.scheme == 'https' &&
        (uri.host == 'mananbharti.github.io' ||
            uri.host == 'biasmanan2010.github.io') &&
        path.startsWith('/zeppay/profile');
    if (!isZepScheme && !isWebFallback && !isProfile) return null;
    return ZepCardProfile(vpa: vpa, name: name);
  }

  static ZepCardProfile? parseLink(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    return parseUri(Uri.tryParse(raw.trim()));
  }
}
