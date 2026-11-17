# Customizable mini newsfeed

Status: implemented and reviewed; all work remains uncommitted at the user's request.

Sources: [Customizable mini newsfeed](https://github.com/paintparty/jus/issues/31)
and `clojurians-tui-news-feed-handoff.docx` in the repository root.

## Confirmed direction

- Navigation: main menu → Newsfeed → configured channel → scrollable channel reader.
  The channel picker replaces the handoff's combined feed.
- Users edit channel subscriptions in the existing global config. No subscription UI.
- Omit Newsfeed from the main menu when no channels are configured.
- Keep configured channels visible when they have no retained posts, showing
  `(0)` and an empty reader message: "No posts in the last 7 days."
- Carry forward the handoff's archive-first design: no Slack authentication;
  seven calendar days of history; roughly five-minute polling; no persistent
  unread state; session-only new-message markers; graceful network failures.

## Proposed behavior

- Preserve configured channel order. Normalize an optional leading `#` and
  deduplicate subscriptions; reject unsafe channel names before constructing URLs.
- Counts represent retained posts in the seven-day window, not unread posts.
  The main menu count is the sum across channels.
- Load history asynchronously at startup when subscriptions exist. Never block
  navigation or other TUI features on archive requests.
- Each reader shows newest posts first, with time, author, message text, and
  underlined terminal hyperlinks. Reuse the existing bounded content reader.
- Initial history establishes a per-channel baseline; it is not marked NEW.
  Subsequently discovered messages receive session-only markers.
- Keep scroll position stable while someone is reading and fresh posts arrive.
- Refresh while the TUI runs; expire posts outside the rolling window. Retain
  cached content on failures and show a small stale/error notice.
- Treat genuinely missing days as empty. Distinguish throttling, server errors,
  unexpected HTML, and connectivity failures from an empty channel.
- Cancel pending refresh work on shutdown. Prevent duplicate polling loops when
  navigating or when the TUI temporarily exits to launch a REPL.

## Config proposal

Add a sibling to the existing `:tui :projects` section:

```clojure
{:tui {:news {:channels ["announcements" "releases" "events"]}}}
```

An absent or empty channel vector disables the feature. Seven days and five
minutes are v1 defaults; do not expose additional settings unless necessary.
Preserve unrelated settings and comments in existing config write paths.
An invalid news section should disable news with a useful notice rather than
discarding valid project settings or preventing startup.

## Implementation sequence

1. Verify archive behavior with a small number of requests. Inspect a populated
   dated page, stable message identifiers, timestamps/date boundaries, links,
   reply representation, missing-day responses, and fetching guidance. Confirm
   archive freshness and whether late updates require revisiting yesterday.
   Choose parsing and HTTP dependencies only after checking both JVM and
   Babashka runtime compatibility.
2. Isolate archive fetching and parsing behind a small adapter. Use a descriptive
   User-Agent, request timeouts, bounded response sizes/concurrency, caching, and
   backoff. Treat archived content as untrusted text; preserve intended links
   without passing through arbitrary terminal control sequences.
3. Add a news module for window calculation, per-channel histories, stable
   deduplication, ordering, session baselines, and refresh/expiry transitions.
   Keep time and fetching injectable for deterministic verification.
4. Add config accessors and state-aware main-menu choices. Wire background
   loading and refresh results through existing Charm commands/messages. Keep
   polling and state across navigation without mixing unrelated screens' errors.
5. Add channel picker and reader, counts, loading/empty/stale states, resizing,
   paging, Escape navigation, and session-new styling. Keep archive selectors
   and network operations outside rendering code.
6. Document config and behavior; run relevant checks and a terminal smoke check.

## Verification

- Saved HTML fixtures cover normal posts, rich links, escaping, replies, empty
  days, and detectable parser failures. Routine tests do not scrape the archive.
- Model tests cover duplicate fetches, baseline versus new messages, partial
  failures, midnight/window expiry, backoff, and cancellation.
- Config/navigation tests cover disabled news, configured channel order/counts,
  the reader/back path, resizing/scrolling, and preservation of project settings.
- Run `~/bin/clj-kondo` for changed Clojure files and the existing unit and
  integration suites (`bb test`). Perform a focused terminal smoke check for
  loading responsiveness, hyperlinks, navigation, and clean shutdown.

## Observations and boundaries

- Existing reusable pieces: `jus.tui.content/page-lines`, asynchronous
  `charm.program/cmd` requests, and `jus.tui.config` global configuration.
- A planning-time request to the archive's `robots.txt` returned
  `max connections reached: 10`. Archive availability and actual rate-limit
  semantics remain unverified; do not infer missing content from this response.
- Existing unrelated working-tree edits are present. Preserve them.
- Defer persistent last-seen cursors, per-message unread tracking, combined
  feeds, subscription screens, Slack APIs, and mirroring threads/reactions.

## Implementation decisions and validation

- Only top-level posts appear in counts/readers; discussion remains accessible
  through each post's archive link. The parser excludes replies and reactions.
- Day boundaries use UTC to match archive timestamps. Refresh today and yesterday
  to catch delayed updates; backfill any days that have not loaded successfully.
- Jsoup parses repaired HTML on both JVM Clojure and Babashka. HTTP requests use
  connection/read timeouts, a 4 MiB response ceiling, and a descriptive User-Agent.
- One owned daemon worker makes sequential requests and publishes coalesced
  snapshots. Shutdown interrupts it, disconnects its request, and releases waiters.
- Channel-specific parser/access failures leave other subscriptions usable;
  archive-wide failures pause the batch and mark all channels unavailable.
- Subscriptions take effect on restarting Jus. Command Central config reloads
  preserve the active news subscriptions until the next runtime starts.
- Full unit/integration suite: 182 tests, 1,375 assertions, no failures or errors.
  An earlier generator-cancellation stream race passed on isolated and full reruns.
- `scripts/newsfeed-smoke.exp` verifies real Babashka TUI navigation, empty channels,
  paging, resizing, terminal hyperlinks, and shutdown against saved HTML. It is
  included in `bb test:e2e` and `bb test:all`.
- Standards and spec reviews completed; reported issues were fixed and rechecked.
- `bb test:e2e` passed, including the newsfeed, Command Central, installation,
  four REPL runtimes, and return-to-picker regression checks. After the final
  multi-class HTML selector correction, focused news tests passed (9 tests,
  67 assertions) and the newsfeed lint check reported no errors or warnings.
- Linting the existing core file still reports its pre-existing duplicate
  `:else` branch in `item-label`; that unrelated code was left unchanged.
