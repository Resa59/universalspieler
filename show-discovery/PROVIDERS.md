# Provider behavior

## Search order and ranking

Providers run in parallel rather than serially. This prevents Feedly or gPodder latency from delaying Apple or Podcast Index results.

Result order is calculated after every provider update from:

1. explicit music declaration;
2. weighted text relevance;
3. direct and verified RSS availability;
4. feed activity;
5. source trust;
6. agreement between multiple sources;
7. source-specific popularity;
8. music/DJ likelihood.

The final list is grouped in this order:

1. declared music;
2. likely DJ/music shows;
3. all other results.

## Podcast Index

Without credentials:

- uses the official keyless Apple-replacement `/search` endpoint;
- returns normal feed results;
- cannot use the special music endpoint or browse endpoints.

With key and secret:

- searches `/search/music/byterm` first;
- also searches `/search/byterm`;
- marks music-endpoint hits as declared music;
- supports trending, new, recently updated and genre browse.

Authentication is calculated at request time with SHA-1 of key + secret + epoch seconds and the documented headers. No hash or secret is retained.

## Apple Podcasts

- searches the requested country storefronts, normally DE and US;
- uses `media=podcast&entity=podcast`;
- keeps the Apple catalogue link as secondary evidence;
- uses the returned `feedUrl` as the subscribable target;
- observes the documented 1–200 result limit.

The module does not depend on undocumented Apple episode search. Episodes are taken from RSS.

## Feedly

- preserves the legacy `/v3/search/feeds` behavior from the old AppYet app;
- tries `query=` first and `q=` as a compatibility fallback;
- works with or without an optional bearer token;
- is deliberately marked best-effort;
- never blocks other providers.

## gPodder

- keyless search via `search.json`;
- popular browse via toplist;
- genre browse via tag directory;
- returns direct RSS URLs.

## Mixcloud

- keyless read/search API;
- searches both shows and user profiles;
- browse support for hot/popular, new and genre query;
- results are platform targets, not fake RSS feeds.

## YouTube

- optional official Data API search using a user-supplied API key;
- returns channels and playlists;
- channel results include the official channel Atom feed;
- playlist results require a platform adapter;
- no NewPipe code or GPL dependency is included in this module.

## Spotify

- optional Web API search with a bearer token supplied by the host app;
- searches show, playlist and artist objects;
- returns external Spotify targets only;
- never claims a playlist or artist has RSS.

## SoundCloud

- optional official API search with an OAuth access token;
- searches users and playlists;
- returns platform targets only;
- future playback/listing may use an official adapter or a separately licensed extractor.
