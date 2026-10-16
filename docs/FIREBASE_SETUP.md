# Switching TrashTalk to live Firebase

The app ships with a seeded on-device backend so it runs with no configuration.
This document turns on the live one. Nothing in the app code changes.

## 1. Create the project

1. <https://console.firebase.google.com> → **Add project**.
2. Add an **Android** app with package name `com.trashtalk`.
   - For debug builds the applicationId is `com.trashtalk.debug`. Either add
     that as a second Android app, or build release. Two apps in one Firebase
     project is normal and is the simpler option.
3. Download `google-services.json` and put it at `app/google-services.json`.

That is the switch. `app/build.gradle.kts` applies the Google Services plugin
only when that file exists, and `AppContainer` picks the Firestore backend only
when `FirebaseApp.initializeApp` succeeds — so adding the file is the entire
migration, and deleting it reverts cleanly to demo mode.

## 2. Turn on the services

In the console:

- **Authentication → Sign-in method → Anonymous → Enable.**
  The app signs in anonymously on first launch. Nobody should have to create an
  account to pick up a wrapper.
- **Firestore Database → Create database.** Start in production mode and paste
  the rules below.
- **Storage** (optional) if you intend to upload litter photos. The app stores
  photo URIs locally until you do.

## 3. Collections

Geometry is **not** stored in Firestore. The 184 segment polylines ship in the
APK and never change, so uploading them would cost a document read per segment
per launch to transfer data the client already has. Firestore holds only what
people change.

| Collection | Document | Holds |
|---|---|---|
| `users/{uid}` | one per player | profile, points, streak, badge tiers, stats |
| `collections/{auto}` | one per pickup | segment, counts by material, points, minutes |
| `segment_state/{segmentId}` | one per touched segment | litter index, survey/cleanup counts, guardian |
| `teams/{teamId}` | one per crew | name, blurb, member count, weekly points |
| `flags/{auto}` | one per flag | kind, location, note, confirmed |

Seed the four crews once (ids must match `FirestoreRepository.DEFAULT_TEAMS`):

```
team_trashers   Terrell Heights Trashers
team_austin     Austin Hwy Alliance
team_wilshire   Wilshire Walkers
team_night      Nacogdoches Night Crew
```

## 4. Security rules

Copy `docs/firestore.rules` into **Firestore → Rules**. The shape of it:

- Anyone signed in can read the neighbourhood. The map is public knowledge and
  the point of the project is to publish it.
- A user may only write their own `users/{uid}` document.
- Collections and flags are append-only, and the `userId` on a new document
  must match the caller — so nobody can log a pickup as somebody else.
- `segment_state` is writable by any signed-in user, because that is what a
  shared survey *is*. The protection against vandalism is social and
  statistical, not a rule: every change is attributed, and a segment surveyed
  by several people converges.

## 5. Indexes

The stats recomputation queries a user's own collections, newest first.
Firestore will prompt with a one-click link the first time it runs; or create
it ahead of time:

- Collection `collections`, fields `userId` ASC, `createdAt` DESC.

## 6. The weekly reset

Leaderboards read a denormalised `weeklyPoints` field on each user. Until a
scheduled function zeroes it on Mondays, weekly figures simply track all-time —
wrong, but visibly so. A minimal Cloud Function:

```js
// functions/index.js
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { getFirestore } = require("firebase-admin/firestore");
require("firebase-admin").initializeApp();

exports.resetWeeklyPoints = onSchedule(
  { schedule: "every monday 00:05", timeZone: "America/Chicago" },
  async () => {
    const db = getFirestore();
    const users = await db.collection("users").get();
    // Batches cap at 500 writes.
    for (let i = 0; i < users.docs.length; i += 450) {
      const batch = db.batch();
      for (const doc of users.docs.slice(i, i + 450)) {
        batch.update(doc.ref, { weeklyPoints: 0 });
      }
      await batch.commit();
    }
  },
);
```

## 7. Verify

Launch the app and check Logcat:

```
I TrashTalk: backend: live Firestore
```

If it says `on-device demo data (no google-services.json)`, the file is missing
or in the wrong directory — it belongs in `app/`, beside `build.gradle.kts`.

Then log a pickup and confirm a document appears in `collections` and that the
matching `segment_state` document's `litterIndex` dropped.

## Costs

The free Spark tier covers a neighbourhood pilot comfortably. The design keeps
reads flat as the network grows: geometry is bundled, the segment-state
listener returns only touched segments, and leaderboards are capped at 100
documents. The thing to watch is the collection feed — if the neighbourhood
gets busy, lower the `observeCollections` limit or add a date filter.
