class Channel {
  final String name;
  final String logoUrl;
  final String category;
  final String tvgId;

  /// "slow" when even this channel's best link delivers video at about real-time speed (tools/check-live-channels.py --speed).
  final String note;

  /// First URL is the main stream; the rest are backups tried when it fails.
  final List<String> streamUrls;

  /// Request headers a stream needs (a Referer or User-Agent), by stream URL; most streams need none.
  final Map<String, Map<String, String>> headers;

  Channel({
    required this.name,
    required this.logoUrl,
    required this.category,
    required this.streamUrls,
    this.tvgId = '',
    this.note = '',
    this.headers = const {},
  });

  Map<String, String> headersFor(String url) => headers[url] ?? const {};

  bool get hasLogo => logoUrl.startsWith('http');

  Map<String, dynamic> toJson() => {
        'n': name,
        'l': logoUrl,
        'c': category,
        'i': tvgId,
        'w': note,
        's': streamUrls,
        if (headers.isNotEmpty) 'h': headers,
      };

  factory Channel.fromJson(Map<String, dynamic> j) => Channel(
        name: j['n'] as String,
        logoUrl: j['l'] as String,
        category: j['c'] as String,
        tvgId: j['i'] as String? ?? '',
        note: j['w'] as String? ?? '',
        streamUrls: (j['s'] as List).cast<String>(),
        headers: ((j['h'] as Map?) ?? const {}).map(
            (url, h) => MapEntry(url as String, (h as Map).map((k, v) => MapEntry(k as String, v as String)))),
      );
}
