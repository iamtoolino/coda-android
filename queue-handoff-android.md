# Coda queue handoff — Android implementation brief

> Historical implementation input. Current behavior is defined by `docs/product.md`,
> `docs/architecture.md`, `AGENTS.md`, and the reviewed Android code.

This document describes the queue-handoff behavior currently implemented in Coda for macOS and how
to reproduce it in Coda for Android. It is a cross-client continuation feature, not remote control:
clients do not attempt to remain synchronized while they are simultaneously playing.

## Server model

OpenSubsonic exposes one saved play queue per authenticated user through `getPlayQueue` and
`savePlayQueue`. It does not provide a separate queue for each client. The saved object contains:

- the ordered song IDs;
- the current song;
- the playback position in milliseconds;
- `changed` and `changedBy`, which identify the latest server update.

The shared server queue therefore represents the client that most recently took playback
ownership. `changedBy` is used to distinguish Coda Android, Coda Mac, and unrelated clients.

Use a stable, distinct OpenSubsonic client name for Android. A future improvement would append a
short random installation identifier so two Android devices can also be distinguished. A fixed
application name distinguishes platforms but not two installations of the same app.

## Ownership states

The coordinator has three practical states.

### Playing locally

Coda owns the shared queue while it is actively playing. It may write the queue to Navidrome.

Write a snapshot:

- shortly after a local queue replacement or mutation;
- shortly after the current item changes;
- shortly after playback begins or resumes;
- every 15 seconds while playback continues;
- when the macOS app terminates while it is still playing (best effort);
- once immediately at the transition from playing to paused.

The snapshot must be captured atomically from queue order, current index, and current position.

### Paused locally

After the one final pause snapshot completes, the server queue is strictly read-only for Coda.

- Do not save because the UI is opened, closed, backgrounded, or destroyed.
- Do not save periodic position updates.
- Queue edits made while paused remain local only.
- Continue checking whether another client has replaced the shared queue.

If the user resumes the local queue, Coda takes ownership again and publishes its current local
snapshot. This is an intentional choice to continue the local session instead of the remote one.

### External queue observed

When a paused Coda receives a non-empty queue whose `changedBy` is not this Coda client:

1. publish it to the UI as the current remote handoff candidate;
2. cancel scheduled or pending local saves;
3. relinquish local ownership of the server queue;
4. preserve Coda's local paused queue;
5. offer the remote queue through the **Continue from _client_** card.

Accepting Continue replaces the local queue, restores its current item and position, and begins
playback. Normal playback ownership then resumes. If the user instead presses Play on the existing
local queue, the card disappears and that local queue becomes authoritative.

An empty server queue produces no Continue card.

## Read schedule

Refresh the remote queue only while local playback is paused.

The macOS implementation checks:

- after connecting to the server;
- when Home performs its normal refresh;
- when Coda becomes the active application;
- after system wake;
- after the user session unlocks;
- every 60 seconds as a fallback.

For Android, use the equivalent mobile lifecycle:

- after account/server connection;
- during Home refresh;
- when the app process or main activity returns to the foreground;
- every 60 seconds while the UI/process is foreground and playback is paused.

Foreground refresh is the important Android trigger. Do not keep a minute-by-minute background
poll alive merely for this feature; it wastes battery and Android may defer it anyway. If the app is
not visible, the next foreground event performs the check immediately.

Refresh failures are silent and opportunistic. Keep the existing local queue and retry at the next
lifecycle event or timer tick. Do not replace the Home screen with an error merely because a
handoff refresh failed.

## Continue-card rules

Show the card only when all of the following are true:

- local playback is paused;
- the remote queue exists and contains songs;
- `changedBy` identifies another client;
- this exact remote snapshot has not already been handled.

Do **not** require the local queue to be empty. Preserving a local paused queue while offering a
newer phone, Mac, or third-party session is the point of the feature.

Build a handoff identity from at least:

- `changed`;
- `changedBy`;
- current song or current index;
- position;
- ordered song IDs.

Remember the accepted identity for the current app session so the same snapshot is not offered
again. A later server update naturally has a different identity.

## Own-queue restoration

On a fresh launch, if the server queue was last written by this Coda client, is non-empty, and no
local queue has already been restored:

1. restore its songs, current item, and position;
2. leave playback paused;
3. mark that remote snapshot as handled.

If it was written by another client, do not auto-play it. Present Continue instead.

## Save serialization

All writes must pass through one serialized, conflating worker:

- assign every requested save a monotonically increasing sequence number;
- debounce closely related queue/current-item events;
- keep only the newest pending snapshot while a request is in flight;
- before every request and retry, verify that its sequence and account session are still current;
- cancel stale work when the account changes or an external queue is observed;
- retry transient failures up to three attempts with short exponential delays.

The final pause snapshot is allowed to complete even though the player has just entered the paused
state. Block remote refresh until this final write has finished, preventing a read/write race at the
pause boundary. No subsequent paused-state event may schedule a write.

On Android, place this coordinator in the playback/service layer rather than an Activity or
Composable. UI recreation must not create multiple save workers.

## Android state-machine sketch

```text
queue/current item changes
    if playing -> debounce and save
    if paused  -> keep local only

play/resume
    claim ownership
    hide Continue card
    debounce and save local snapshot

pause
    save one final snapshot immediately
    after it completes, enter read-only monitoring

foreground/Home refresh/60-second foreground timer
    if paused -> getPlayQueue
    if non-empty and changedBy is external -> cancel local writes and offer Continue

accept Continue
    mark remote identity handled
    replace local queue/current item/position
    start playback
```

## Android caveats

- Do not depend on `Activity.onDestroy()`, service destruction, process death, or an app-swipe event
  for the final save. Android does not guarantee a usable closing callback.
- Periodic 15-second writes while playing and the explicit pause write make abrupt process death a
  bounded-loss event.
- Network callbacks may complete after an account switch. Tag every request with the active account
  session and discard stale results.
- A media-button pause and an in-app pause must go through the same ownership transition.
- Scrobbling is independent of queue handoff and should not be coupled to these writes.

## Acceptance scenarios

1. Play on Android, pause, open Coda Mac: Mac offers the Android queue at approximately the saved
   track position.
2. Play on Mac, pause, foreground Android: Android offers the Mac queue without deleting its own
   local paused queue.
3. Leave Android paused and use another client: foregrounding Android or waiting 60 seconds in its
   foreground UI reveals the new Continue card.
4. Ignore Continue and resume Android's local queue: the card disappears and Android republishes
   its local queue.
5. Accept Continue: Android replaces its queue and starts at the remote current item and position.
6. Edit Android's queue while paused: Navidrome is not touched until playback resumes.
7. Kill Android while playing: at worst, Navidrome retains the latest periodic snapshot; correctness
   must not depend on a shutdown callback.
8. Switch accounts while a save or refresh is in flight: the result is discarded and no queue leaks
   into the new account.
