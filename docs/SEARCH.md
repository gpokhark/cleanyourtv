# Search: what can I watch without paying more?

The **Search** pill looks up a movie or series and shows which of *your* streaming services carry it at no
extra cost: included in a subscription, or free. Type with the on-screen keyboard or hold the mic key.
The filter button switches between "only what I can watch" and everything, where the rest say
"Needs payment". Rent and buy offers never count.

It is off by default (`show_search`) and makes no network requests until you search.

## Setup

1. Get a free API key from [Watchmode](https://api.watchmode.com/).
2. Save it to `secrets/watchmode.key`. The `secrets/` folder and any `*.key` file are git-ignored. To keep
   the key out of your shell history, type it at a hidden prompt:
   `read -s "k?key: " && printf '%s' "$k" > secrets/watchmode.key` (zsh).
3. `TV_HOST=<tv ip> tools/push-key.sh` copies it to the launcher's private folder on the TV, printing
   only its length. `tools/push-key.sh --remove` takes it off again.
4. Turn the feature on and list your services, either in `launcher/app/src/main/res/values/config.xml`
   and rebuild, or without rebuilding in a theme's `config.json` pushed with `tools/theme.sh`
   (keys in [themes/README.md](../themes/README.md)).

`my_services` entries look like `Label|android.package|ids`, where the ids are the provider's own ids for that
service (some services have more than one, comma separated):

```json
"my_services": [
  "Netflix|com.netflix.ninja|203",
  "Peacock|com.peacocktv.peacockandroid|388,389",
  "Tubi|com.tubitv|296"
]
```

Watchmode's ids come from its `/v1/sources/` endpoint. Leave out add-on channels ("... (Via Amazon Prime)"):
they cost extra, so they should not count as free.

## Behaviour and limits

- A search costs one request, plus one per result checked (`search_max_results`, default 8, so up to 9).
  Answers are cached on the TV for a day (searches) and three days (where to watch), so repeats are free.
  Watchmode's free plan allows 2,500 requests a month.
- It knows the service, not your plan tier. "Peacock" can mean Premium only, and a service you have not signed
  in to still shows as available. Catalogues also change, so availability can lag by a few days.
- The free Watchmode plan has no deep links, so OK opens the app rather than the title. An app that is not
  installed opens its Play Store page. Where a title is on two of your services, a picker asks which.
- On TVs whose default keyboard is a phone bridge ("use the keyboard on your mobile device"), set
  `search_keyboard` to an on-screen keyboard's id (`ime list -s` over adb) and grant the permission once:
  `pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS`. Search then switches to it when it opens.
- The catalogue holds titles (movies, series), not user videos, so YouTube is not searched. `search_extras` adds
  a button beside the filter that sends what you typed to another app's own search page:

  ```json
  "search_extras": ["YouTube|com.google.android.youtube.tv|https://www.youtube.com/results?search_query={q}"]
  ```

  Each entry is `Label|android.package|link`, with `{q}` standing for the text (encoded for you). It works with any
  app that opens a search link.
- Errors show in the status line: no key pushed, key rejected, quota reached, or no connection.

## Another data source

The lookup is behind `TitleSearch.Provider` (`search` and `sources`). To add one, write a class that
implements it (see `WatchmodeProvider`), add a case for its name in `SearchProviders.create`, put its key at
`secrets/<name>.key`, push it with `tools/push-key.sh <name>`, and set `search_provider`.
