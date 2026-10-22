# Shows: keep your place in a show

The **Shows** tile in the grid remembers where you are in a show you are working through: the season, the episode
and how far into it you stopped. Open it and press **Resume** to carry on from that spot, **Next episode**, or choose
any season and episode. It is off by default (`show_shows`).

## How it keeps your place

- It opens the episode itself, so it knows which one you are on. That is the part to rely on.
- While the episode plays, a small listener (`MediaWatcher`) reads the player's position and saves it when you
  pause or leave, so the screen can say "S13 E960 · 3:41 in".
- Where an episode picks up when you reopen it is up to the app. Pluto TV remembers its own spot (saved when you
  pause or leave properly) and resumes there; other apps may start from the beginning. Apps ignore seek requests
  from outside, so the Shows screen cannot jump for you: if the episode opens earlier than your saved position,
  wind forward with the remote.
- Pluto TV (like most apps) does not tell Android which episode is playing. So if the app moves on to the next
  episode by itself, the place moves on too by a rule: the position fell back to the start after a long watch. If
  you browse to a different episode inside the app, the Shows screen cannot see that; press **Mark as watched** or
  pick the episode there.
- Whatever plays in the show's app while a show is marked as being watched counts as that show, until the app has
  been quiet for three minutes or six hours pass, or you open another episode from Shows.

## Setup

1. Make a catalogue of the show. For Pluto TV (free, anime and TV):

   ```
   tools/pluto-catalog.py --search "one piece"                      # find the series slug
   tools/pluto-catalog.py one-piece-eng --id one-piece --out shows  # writes shows/one-piece.json
   ```

   Pluto's catalogue is its own unofficial web API, so this can stop working after a change on their side.
   `--order N` sets the position in the Shows list (0 first, ties sort by title). Some Pluto shows are also split into
   separate series (One Piece has one per saga); each is its own show with its own place.
2. Copy the catalogue to the TV: `TV_HOST=<tv ip> tools/push-shows.sh shows`
3. Turn the tile on with `show_shows` (in `config.xml`, or a theme's `config.json`).
4. Allow the listener, once, so it can read playback (an app cannot do this itself):

   ```
   adb shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaWatcher
   ```

   Without it the Shows screen still opens episodes, but nothing saves your position.

Your progress stays on the TV, in the launcher's own storage, when you push a newer catalogue.

## Last on YouTube

YouTube has no catalogue or fixed place to keep, so it is handled differently. The app tells other apps only the title
and channel of the video it is playing (and its length), not a link. Its position looked right for one long video and
near zero for others, so it is not used. So the Shows screen gets
one extra entry, "Last on YouTube", listing the last twenty videos you watched, newest first.

```json
"youtube_package": "com.google.android.youtube.tv"
```

- Empty (the default) turns it off. On Fire TV the package is `com.amazon.firetv.youtube`.
- A video is saved after it has played for about 30 seconds, so flicking through videos does not fill the list.
  Watching it again moves it back to the top. Only the newest 20 are kept. Hold OK on a video to remove it from the list; each row says so under its title.
- OK on a video opens YouTube's own search for its title and channel. Pick the right result; YouTube picks up where you
  stopped, the way it does for any video you reopen while signed in. If the search shows a different upload first, the
  match is by name, so it is not exact.
- It needs the same notification listener as the shows above (see "Tracking where you stop"). Live streams are listed
  like any other video.
- `adb logcat -s TvHome.Watcher:I` prints each video it sees, with its title, channel and length. The TV's log buffer
  turns over fast, so read it soon after the video starts.
- An ad that plays first counts toward the 30 seconds, but the app keeps reporting the video's own title, so an ad is
  never saved as a video.
