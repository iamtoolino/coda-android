# Coda Android development instructions

## Project character

- Coda is a focused, album-first Android client for Navidrome and compatible OpenSubsonic servers.
  It is a polished daily music player, but still early-alpha software that needs testing across more
  devices, servers, network conditions, and car hosts.
- Preserve the artwork-led dark UI, visible queue, direct server-backed library, and restrained
  product scope. Prefer Jetpack Compose, Media3, coroutines, and small Android-native integrations
  over custom frameworks.
- Navidrome is authoritative for library metadata, playlists, ratings, search, and the shared server
  queue. Coda is online-first and does not maintain a second local library database.
- Coda is mobile-aware: Wi-Fi/Ethernet requests the original stream, cellular requests server-defined
  Opus transcoding, and playback starts progressively. There is no permanent offline library.
- Android Auto, notifications, Bluetooth controls, and the phone UI are first-class consumers of the
  same playback service and media session. A feature is not complete if it works only while the main
  activity is visible.
- Keep solutions proportional to this hobby project's size and expected maintenance. Centralize a
  semantic decision when multiple consumers must agree; do not add generic layers with one caller.
- The product contract is in `docs/product.md`, and current ownership and data flow are in
  `docs/architecture.md`. Treat current code and those reviewed documents as authoritative over
  obsolete prototype behavior.

## Durable product and architecture rules

- `PlaybackService` owns the Media3 player, `MediaLibrarySession`, local playback restoration,
  transient audio window, and behavior required without `MainActivity`.
- `PlaybackConnection` is the phone UI's controller-side bridge. Compose screens consume playback
  state; they must not create a second player or independently reconstruct service-owned state.
- Process death is normal Android behavior. Persist enough account-scoped playback metadata to
  restore queue, current item, position, artwork, and paused state before publishing a replacement
  media session. Never depend on `Activity.onDestroy()`, service destruction, a final callback, or
  opening Home for correctness.
- The car host may cold-start and control Coda without launching the phone UI. Preserve Media3
  playback resumption and `MediaButtonReceiver` behavior when changing the service or manifest.
- The queue defines the rolling audio cache. As soon as a non-empty queue exists, cache the current
  track and then the next three sequentially. Preserve that bounded window across process/service
  recreation, prune entries outside it when the queue or current item changes, and clear it when the
  queue is emptied or the account disconnects. It is not an offline library. Phone artwork and
  Android Auto artwork have separate cache policies.
- Android Auto artwork uses local `content://` URIs so private and VPN-only servers work through the
  phone. Keep the car-artwork cache bounded and do not expose authenticated remote URLs to the host.
- Queue handoff is an ownership protocol, not merely serialization. Playing locally claims the
  shared queue; paused Coda does not keep overwriting another client; an external queue is offered
  explicitly; the local snapshot remains the immediate process-death restoration source.
- Every asynchronous server result belongs to an `AppGraph` account generation. After suspension
  and before publishing state or mutating a server, verify that the captured generation is current.
  Cancellation alone is not sufficient.
- Phone UI server reads and mutations go through `AppGraph.withCurrentSession`; do not capture
  `AppGraph.navidrome` directly across suspension. Metadata calls have a finite overall deadline so
  an unreachable VPN, DNS answer, or server becomes an actionable error instead of an endless
  loading state.
- Home sections load and fail independently. Keep their shells visible immediately, publish each
  successful section without waiting for its siblings, retain good content during refresh, and use
  only bounded network-error retries. One slow endpoint must not blank or block the rest of Home.
- Collection composables render coordinator state; they do not own request generations or publish
  suspended results directly. Preserve good content on same-key refresh, clear mismatched content
  when a collection key changes, and reject late completions from superseded loads.
- Read-only detail composables use the same keyed remote-resource lifecycle. Artist detail loading
  belongs to its coordinator specialization; Compose owns only rendering, scrolling, and navigation.
- Search text selection, focus, and keyboard behavior belong to Compose; query debounce, request
  ownership, result identity, refresh retention, and stale-result rejection belong to
  `SearchCoordinator`. Explicit refresh is immediate and must not repeat the typing debounce.
- Album ratings belong to one authenticated-session `AlbumRatingCoordinator`, never a file-global
  override or screen-local transaction flag. Publish optimistic ratings across every surface,
  serialize writes per album while conflating pending choices, and roll back only a failed latest
  revision. Session cancellation must not publish a rollback or failure.
- Scrobbling belongs beside the authoritative player in `PlaybackService`, not to the phone UI.
  Model each loaded track as a playback occurrence. Submit it as played only after a Media3 automatic
  completion, repeat completion, or final `STATE_ENDED`; restore, handoff, seek, manual skip, queue
  replacement, stop, and process start must never infer completion from playback position.
- Artwork identity, album identity, and track identity are not interchangeable. Prefer canonical
  album artwork for playback, cache, and theme decisions.
- Phone artwork uses the shared 420/500/600/1200 px source policy. Cache encoded Navidrome artwork
  once per account, generation, cover ID, and source size rather than per composable; theme
  extraction shares the hero source. Coil owns decoded memory and a 10 GB encoded disk cache.
  Playback `content://` artwork is already backed by the separate car cache and must not be copied
  into Coil's disk cache. Server artwork replacement is explicit: the Connection screen refresh
  clears phone/car bytes, derived colors, and advances the cache generation.
- Shared visual policy is resolved centrally. Brand teal is the default; the current playback
  artwork owns the theme across ordinary screens and Now Playing; a viewed album or playlist with
  explicit artwork temporarily owns the entire phone presentation, including the mini-player;
  artist imagery and playlists without explicit artwork inherit playback or Brand. Never infer a
  playlist theme from its first track. Guard asynchronous artwork/color results by account-scoped
  identity and retain the previous committed theme while a replacement is loading so navigation
  never flashes through Brand. Monochrome artwork resolves to neutral grey, and unreadable or absent
  artwork resolves to Brand. Brand gold is reserved for the login Connect action.

## Git workflow

- Work on the current branch. Do not create or switch branches unless the user explicitly asks.
- After completing and verifying a normal requested code change, create a focused commit unless the
  user explicitly says not to commit it.
- For an explicitly requested prototype or experiment branch, commit each stable working checkpoint
  unless the user asks to leave the experiment uncommitted. Remove rejected variants and tuning
  controls after a decision.
- Do not commit broken, incomplete, or unverified states merely to create a checkpoint.
- Preserve unrelated user changes and never include them in a commit without explicit permission.
- Before committing, inspect staged filenames, `git diff --cached`, and `git diff --check`.
- Do not merge, push, publish, tag, release, rewrite history, or delete a branch unless the user
  explicitly asks.

## Privacy and repository hygiene

- Never commit credentials, tokens, private server URLs, device identities, personal library data,
  authenticated media URLs, or reports derived from a user's server or listening history.
- Keep screenshots with personal content, logs, profiler captures, databases, audits, benchmarks,
  generated reports, and one-off diagnostic scripts in `/tmp`, another temporary directory, or an
  ignored build directory unless the user explicitly requests a scrubbed repository fixture.
- Treat unexpected CSV, JSON, XML preference files, databases, logs, caches, reports, APKs, and
  bytecode as suspicious until their purpose and contents are verified.
- Do not print credential-store contents to discover test configuration. Prefer in-app login and
  read-only checks that reveal only non-sensitive state.
- If sensitive data enters Git history, stop unrelated work, avoid repeating it in tool output, and
  discuss containment and history cleanup with the user.

## Review workflow

- Reviews are read-only by default. Do not implement findings, reorganize code, alter documentation,
  or create commits unless the user separately asks for changes.
- Start by recording the reviewed commit, branch, and dirty files. Inspect current code, relevant
  tests and documentation, and recent history needed to understand intent. Do not revive discarded
  prototypes merely because they exist in history.
- Lead with findings ordered by practical severity. Give exact file and line references, concrete
  user impact or failure mode, supporting evidence, and a concise remediation direction.
- Separate confirmed defects, credible risks, architecture options, and optional cleanup. Style
  preferences are not correctness findings.
- Independently verify important claims. If there are no actionable findings, say so and identify
  meaningful verification gaps.

## Multi-agent review orchestration

- Use subagents only when the user explicitly requests a multi-agent review, a full Coda review, or
  named specialist review passes. Do not spawn them for ordinary implementation or a normal review.
- A full review covers these passes, in waves if concurrency is limited:
  1. Architecture fitness and shared-policy ownership.
  2. Correctness, concurrency, security, packaging, and Android-native behavior.
  3. Code quality, semantic duplication, maintainability, and experiment residue.
  4. Performance and resource use.
  5. Documentation, build reproducibility, release readiness, and verification coverage.
- Specialist agents are read-only unless fixes are separately authorized. They must not edit files,
  launch live server or car tests, create commits, or delegate further review work.
- Each specialist reports evidence-backed findings with severity, file/line references, impact, and
  remediation direction, and explicitly says when it found no actionable issue.
- The coordinating agent verifies important claims, reconciles conflicts, removes duplicates, and
  delivers one findings-first report. Agreement between agents is not proof.

## Review priorities

### Architecture and correctness

- Look first for duplicated product policy across composables, controllers, callbacks, and services:
  playback restoration, queue ownership, theme context, ratings, scrobbling, cache identity, session
  changes, and error handling.
- Verify that mutable state and side effects have a clear owner. UI lifecycle events may request work
  but must not be the sole owner of playback, persistence, or car-host correctness.
- Pay particular attention to process/activity/service recreation; foreground-service restrictions;
  MediaSession and media-button commands; audio focus and noisy-output handling; notifications and
  lock-screen behavior; connectivity changes; and queue boundaries.
- Check coroutine cancellation, dispatcher use, Flow collection lifetime, stale completions after
  account changes, concurrent saves, optimistic mutation rollback, and callbacks arriving out of
  order. Do not perform blocking network, disk, image, or potentially expensive serialization work
  on the main thread.
- Exercise empty libraries, missing and non-square artwork, long metadata, arbitrary multidisc
  albums, large queues/playlists, server errors, cellular/Wi-Fi transitions, background/foreground
  transitions, and repeated process death.
- Prefer targeted helpers and state machines over a broad architecture rewrite. Introduce protocols,
  repositories, modules, or dependency injection only when multiple consumers or test seams justify
  them.

### Android UI and accessibility

- Preserve Android-native back behavior, gesture navigation, system bars, touch targets, TalkBack
  semantics, font scaling, and configuration/process recreation. The phone UI is intentionally
  portrait-only unless the product contract changes.
- Compose effects must have correct keys and cleanup. Async image or data work must not publish into
  a route, album, session, or playback identity that is no longer current.
- Automated tests do not establish visual quality. Check representative artwork, missing artwork,
  long text, small and large screens, and actual interaction on a device or emulator for visual work.

### Performance and resources

- Base findings on a credible hot path, measurement, or clear unbounded behavior. Do not recommend
  speculative micro-optimization.
- Focus on bitmap memory, artwork/network fan-out, Compose invalidation caused by playback ticks,
  large list scrolling, audio prefetch/cache eviction, repeated queue serialization, background
  work, wake locks, and battery/mobile-data impact.
- Keep playback metadata/queue state separate from high-frequency position state. Rebuild immutable
  queue snapshots only on timeline changes; position persistence must reuse them and encode off main.
- Understand Coil, Media3, HTTP, car-artwork, and Navidrome server caches before adding another cache.
  Authenticated URL metadata and invalidation behavior are part of the design.
- Profile before substantial performance refactors. Keep profiling artifacts and personal-library
  measurements out of tracked source.

### Security, packaging, and release behavior

- Treat credential storage, Network Security Config, cleartext LAN behavior, exported services and
  providers, content-URI permissions, backup policy, logs, signing, R8 rules, and release
  debuggability as security-sensitive.
- When adding or upgrading dependencies, inspect the final transitive packaged closure and required
  notices, not only Gradle declarations. Keep debug helpers and live-test entry points out of release
  variants.
- A release request requires its own candidate and approval flow. Test clean install and upgrade,
  inspect the signed APK/AAB and merged manifest, and keep local tag/package preparation separate
  from explicit approval to push or publish.

### Debug theme labs

- The browser-controlled theme lab is temporary design tooling, not a product setting. Keep its ADB
  receiver and manifest declaration under `app/src/debug`; the browser server must bind to loopback
  and require an explicit device serial.
- After a visual decision, bake the chosen treatment into the central theme policy and remove
  rejected variants and tuning controls. Do not carry a general user-selectable theme framework
  forward.
- Before a release candidate, either remove the lab or prove that its receiver and broadcast action
  are absent from the merged release manifest and packaged release output.

## Verification

- Match verification effort to risk and report checks that were not run.
- Use the repository wrapper so the JDK and Gradle cache are consistent:

  ```sh
  ./scripts/verify.sh
  ```

- Run the deterministic Compose smoke test on a booted emulator with an explicit serial:

  ```sh
  ./scripts/ui-test.sh emulator-5554
  ```

- For documentation-only changes, run `git diff --check` and verify referenced commands and paths. A
  full Android build is unnecessary unless build, manifest, packaging, or runtime behavior changed.
- Prefer deterministic JUnit tests for policy, serialization, ordering, and state-machine behavior.
  Keep live Navidrome, audio, emulator, phone, and car tests explicit and separate.
- Playback/service changes require proportionate lifecycle checks: pause a real queue, background
  Coda, kill the debuggable app process without Force Stop, and confirm the replacement MediaSession
  restores metadata, queue, position, and system Play. Force Stop is not a process-death substitute.
- Android Auto changes require the Google Desktop Head Unit when available and, for material fixes,
  confirmation on a physical phone/car path. Validate cold service start, split-screen Play/Pause,
  browse/search, artwork, queue size, and recovery without opening the phone activity.
- Use `scripts/adb.sh` and an explicit device serial when an emulator and phone are connected. Do not
  install, clear, force-stop, or modify the wrong device. Preserve app data for upgrade tests unless
  a clean-install test is explicitly intended.
- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Identify the exact commit and dirty
  state associated with any APK given to the user for testing.

## Documentation and scope discipline

- Update `README.md`, `docs/product.md`, `docs/architecture.md`, and release documentation in the
  same change when user-facing behavior, durable ownership, setup, or verification commands change.
- Keep build instructions reproducible for a fresh checkout. Do not preserve obsolete commands
  merely because they once worked.
- Treat large files and long functions as review signals, not automatic defects. Flag concrete mixed
  responsibilities, policy duplication, or change amplification rather than line count alone.
- Do not impose enterprise process by default: no mandatory coverage target, CI matrix, module split,
  DI framework, database, snapshot suite, or benchmark gate without a concrete payoff.
- Automate repeated failure points and protect high-risk behavior with focused regression tests, but
  keep process proportional to Coda.
