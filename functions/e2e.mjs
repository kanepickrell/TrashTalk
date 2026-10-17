/**
 * Drives the scoring function against the emulator, the way the app will.
 *
 * Writes a submission, waits for the result the function produces, and checks
 * it. This is the only way to find out whether the trigger, the transaction
 * and the contest actually agree with each other -- the unit tests cover the
 * rules, not the wiring.
 *
 *   node e2e.mjs
 */

import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

process.env.FIRESTORE_EMULATOR_HOST ??= 'localhost:8080';
initializeApp({ projectId: 'trashtalk-de1e9' });
const db = getFirestore();

const PASS = [];
const FAIL = [];

function check(name, ok, detail = '') {
  (ok ? PASS : FAIL).push(name);
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
}

/** A believable walking track between two points. */
function track(points, seconds) {
  const out = [];
  const start = Date.now() - seconds * 1000;
  for (let i = 0; i < points; i++) {
    out.push({
      lat: 29.5025 + i * 0.00002,
      lng: -98.456 + i * 0.00002,
      at: start + (i * seconds * 1000) / points,
      accuracyMetres: 8,
      isMock: false,
    });
  }
  return out;
}

async function waitForResult(id, timeoutMs = 20000) {
  const ref = db.collection('results').doc(id);
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snap = await ref.get();
    if (snap.exists) return snap.data();
    await new Promise((r) => setTimeout(r, 300));
  }
  return null;
}

async function submit(id, body) {
  await db.collection('submissions').doc(id).set({ createdAt: Date.now(), ...body });
  return waitForResult(id);
}

async function main() {
  const uid = 'test-user-' + Date.now();
  await db.collection('users').doc(uid).set({ displayName: 'Tester', streakDays: 0 });

  // ---------------------------------------------------------- happy path
  const id1 = 'sub-' + Date.now();
  const r1 = await submit(id1, {
    userId: uid,
    segments: [
      {
        segmentId: 's_aaa',
        segmentName: 'Test Street',
        counts: { CIGARETTE: 7, PLASTIC_BOTTLE: 3 },
        lat: 29.5025,
        lng: -98.456,
        minutes: 12,
      },
    ],
    track: track(40, 900),
    evidencePath: `evidence/${uid}/${id1}/haul.jpg`,
  });

  check('a clean submission is scored', r1?.status === 'SCORED', JSON.stringify(r1?.pointsAwarded));
  check('points were awarded', (r1?.pointsAwarded ?? 0) > 0, `${r1?.pointsAwarded} pts`);
  check('a first survey is recognised', r1?.awards?.[0]?.wasFirstSurvey === true);
  check('evidence makes it partial trust', r1?.trustLevel === 'partial');
  check('an unowned block is claimed', r1?.awards?.[0]?.claimed === true);

  const state1 = (await db.collection('segment_state').doc('s_aaa').get()).data();
  check('ownership was written server-side', state1?.ownerId === uid);
  check('the observation was written', (await db.collection('collections')
    .doc(`${id1}_s_aaa`).get()).exists);

  const user1 = (await db.collection('users').doc(uid).get()).data();
  check('the user total was incremented by the server',
    user1?.totalPoints === r1?.pointsAwarded,
    `${user1?.totalPoints} vs ${r1?.pointsAwarded}`);

  // ------------------------------------------------------- the client lies
  // The client has no say in what it scores. Send an absurd claim and see
  // whether the server pays it.
  const id2 = 'sub-lie-' + Date.now();
  const r2 = await submit(id2, {
    userId: uid,
    points: 999999,
    totalPoints: 999999,
    segments: [
      { segmentId: 's_bbb', segmentName: 'Liar Lane', counts: { CIGARETTE: 1 },
        lat: 29.5025, lng: -98.456, points: 999999 },
    ],
    track: track(40, 900),
  });
  check('a forged points field is ignored',
    (r2?.pointsAwarded ?? 0) < 1000, `${r2?.pointsAwarded} pts`);

  // ------------------------------------------------------------ anti-cheat
  const id3 = 'sub-car-' + Date.now();
  const r3 = await submit(id3, {
    userId: uid,
    segments: [{ segmentId: 's_ccc', counts: { PAPER: 2 }, lat: 29.5025, lng: -98.456 }],
    // 10 km in 10 minutes.
    track: (() => {
      const t = track(40, 600);
      return t.map((p, i) => ({ ...p, lng: -98.456 + i * 0.0025 }));
    })(),
  });
  check('driving is rejected', r3?.status === 'REJECTED', r3?.reason);
  check('the rejection reads as plain language',
    typeof r3?.reason === 'string' && /^[A-Z].*\.$/.test(r3.reason) && !/_/.test(r3.reason),
    r3?.reason);

  const id4 = 'sub-mock-' + Date.now();
  const r4 = await submit(id4, {
    userId: uid,
    segments: [{ segmentId: 's_ddd', counts: { PAPER: 1 }, lat: 29.5025, lng: -98.456 }],
    track: track(40, 900).map((p, i) => (i === 5 ? { ...p, isMock: true } : p)),
  });
  check('a mock location is rejected', r4?.status === 'REJECTED', r4?.reason);

  // --------------------------------------------------------- idempotency
  // Re-writing the same submission must not pay twice. This is what the
  // offline queue does on every retry.
  const before = (await db.collection('users').doc(uid).get()).data()?.totalPoints;
  await db.collection('submissions').doc(id1).set(
    { retriedAt: Date.now() }, { merge: true });
  await new Promise((r) => setTimeout(r, 3000));
  const after = (await db.collection('users').doc(uid).get()).data()?.totalPoints;
  check('a retried submission does not double-score', before === after,
    `${before} -> ${after}`);

  // ------------------------------------------------------- no evidence
  const id5 = 'sub-noevid-' + Date.now();
  const r5 = await submit(id5, {
    userId: uid,
    segments: [{ segmentId: 's_eee', segmentName: 'No Proof Road',
      counts: { ALUMINUM_CAN: 4 }, lat: 29.5025, lng: -98.456 }],
    track: track(40, 900),
  });
  check('no evidence still scores points', (r5?.pointsAwarded ?? 0) > 0);
  check('no evidence is unverified', r5?.trustLevel === 'unverified');
  check('no evidence takes no ground', r5?.awards?.[0]?.claimed === false);

  console.log(`\n  ${PASS.length} passed, ${FAIL.length} failed`);
  if (FAIL.length) {
    console.log('  failed: ' + FAIL.join(', '));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
