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
