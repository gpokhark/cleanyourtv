class Channel {
  final String name;
  final String logoUrl;
  final String category;
  final String tvgId;

  /// "slow" when even this channel's best link delivers video at about real-time speed (tools/check-live-channels.py --speed).
  final String note;

  /// First URL is the main stream; the rest are backups tried when it fails.
  final List<String> streamUrls;

  Channel({
    required this.name,
    required this.logoUrl,
    required this.category,
    required this.streamUrls,
    this.tvgId = '',
    this.note = '',
  });

  bool get hasLogo => logoUrl.startsWith('http');

  Map<String, dynamic> toJson() => {
        'n': name,
        'l': logoUrl,
        'c': category,
        'i': tvgId,
        'w': note,
        's': streamUrls,
      };

  factory Channel.fromJson(Map<String, dynamic> j) => Channel(
        name: j['n'] as String,
        logoUrl: j['l'] as String,
        category: j['c'] as String,
        tvgId: j['i'] as String? ?? '',
        note: j['w'] as String? ?? '',
        streamUrls: (j['s'] as List).cast<String>(),
      );
}
