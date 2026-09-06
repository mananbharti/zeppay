/// NDEF / deep-link payload for semiconductor batch NFC tags (Challenge 2).
abstract final class ChipTagCodec {
  static const scheme = 'zeppay';
  static const chipHost = 'chip';
  static const webChipBase =
      'https://mananbharti.github.io/zeppay/chip';

  static Uri appUri({required String nfcId}) {
    return Uri(
      scheme: scheme,
      host: chipHost,
      queryParameters: {'nfc_id': nfcId},
    );
  }

  static Uri webUri({required String nfcId}) {
    return Uri.parse(webChipBase).replace(
      queryParameters: {'nfc_id': nfcId},
    );
  }

  /// Returns the physical tag id written on the NFC label, or null.
  static String? parseUri(Uri? uri) {
    if (uri == null) return null;
    final id = uri.queryParameters['nfc_id']?.trim() ?? '';
    if (id.isEmpty) return null;

    final isChipHost = uri.host == chipHost;
    final isZepScheme = uri.scheme == scheme;
    final isWebFallback = uri.scheme == 'https' &&
        (uri.host == 'mananbharti.github.io' ||
            uri.host == 'biasmanan2010.github.io') &&
        uri.path.startsWith('/zeppay/chip');
    final isPathChip = uri.path.contains('/chip');

    if (!isChipHost && !isWebFallback && !(isZepScheme && isPathChip)) {
      return null;
    }
    return id;
  }

  static String? parseLink(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    return parseUri(Uri.tryParse(raw.trim()));
  }
}
