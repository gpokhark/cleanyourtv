import 'package:flutter_test/flutter_test.dart';
import 'package:ip_tv/model/channel.dart';
import 'package:ip_tv/provider/epg.dart';

// 2026-10-22 12:00:00 UTC
final nowMs = DateTime.utc(2026, 10, 22, 12).millisecondsSinceEpoch;

const guide = '''<?xml version="1.0" encoding="UTF-8"?>
<tv>
<channel id="Aaj.Tak.in"><display-name>Aaj Tak</display-name></channel>
<channel id="X1"><display-name lang="en">Zee &amp; Co HD</display-name></channel>
<channel id="Other.in"><display-name>Not Ours</display-name></channel>
<programme start="20261022113000 +0000" stop="20261022123000 +0000" channel="Aaj.Tak.in"><title lang="hi">Khabar &amp; More</title></programme>
<programme start="20261022190000 +0530" stop="20261022200000 +0530" channel="Aaj.Tak.in"><title>Evening News</title></programme>
<programme start="20261022123000 +0000" stop="20261022133000 +0000" channel="Aaj.Tak.in"><title><![CDATA[Late Show]]></title></programme>
<programme start="20261020100000 +0000" stop="20261020110000 +0000" channel="Aaj.Tak.in"><title>Two days ago</title></programme>
<programme start="20261023100000 +0000" stop="20261023110000 +0000" channel="Aaj.Tak.in"><title>Tomorrow</title></programme>
<programme start="20261022120000 +0000" stop="20261022130000 +0000" channel="X1"><title>By name</title></programme>
<programme start="20261022120000 +0000" stop="20261022130000 +0000" channel="Other.in"><title>Not wanted</title></programme>
</tv>''';

Channel ch(String name, String id) => Channel(name: name, logoUrl: '', category: 'News', streamUrls: ['u'], tvgId: id);

Stream<String> pieces(String s, int size) async* {
  for (var i = 0; i < s.length; i += size) {
    yield s.substring(i, i + size > s.length ? s.length : i + size);
  }
}

void main() {
  final wanted = wantedKeys([ch('Aaj Tak', 'AajTak.in@HD'), ch('Zee & Co', '')]);

  test('keys ignore dots, case, the feed and an HD part', () {
    expect(epgIdKey('AajTak.in@HD'), epgIdKey('Aaj.Tak.in'));
    expect(epgIdKey('&TV.HD.in'), epgIdKey('&TV.in'));
    expect(epgNameKey('Aaj Tak HD (1080p) [Geo-blocked]'), 'aajtak');
  });

  test('times carry their zone', () {
    expect(parseXmltvTime('20261022120000 +0000'), nowMs);
    expect(parseXmltvTime('20261022173000 +0530'), nowMs);
    expect(parseXmltvTime('20261022070000 -0500'), nowMs);
    expect(parseXmltvTime('20261022120000'), nowMs);
    expect(parseXmltvTime('nonsense'), isNull);
  });

  test('keeps wanted channels in the window, matched by id and by name', () async {
    // 7-character pieces cut every tag and attribute in two
    final out = await parseGuide(pieces(guide, 7), wanted, {}, nowMs);
    final aaj = out[epgIdKey('AajTak.in@HD')]!;
    expect(aaj.map((p) => p[2]).toList(), ['Khabar & More', 'Late Show', 'Evening News']);
    expect(out['nzeeco']!.map((p) => p[2]).toList(), ['By name']);
    expect(out.length, 2);
  });

  test('a channel an earlier guide supplied is skipped', () async {
    final out = await parseGuide(pieces(guide, 4096), wanted, {epgIdKey('AajTak.in@HD')}, nowMs);
    expect(out.keys, ['nzeeco']);
  });

  test('nowNext picks the running programme and the one after it', () async {
    final out = await parseGuide(pieces(guide, 4096), wanted, {}, nowMs);
    final list = [for (final p in out[epgIdKey('AajTak.in@HD')]!) Programme.fromJson(p)];
    final (now, next) = pickNowNext(list, nowMs);
    expect(now!.title, 'Khabar & More');
    expect(next!.title, 'Late Show');
    // between programmes: nothing running, the next one is what follows
    final gap = pickNowNext(const [Programme(100, 200, 'a'), Programme(300, 400, 'b')], 250);
    expect(gap.$1, isNull);
    expect(gap.$2!.title, 'b');
    expect(pickNowNext(const [], 250), (null, null));
    expect(Epg.instance.nowNext(ch('Nobody', ''), DateTime.now()), (null, null));
  });
}
