const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const { isTriageTrigger, isAnchoredNonTriage } = require("../triage-trigger.js");

test("an anchored /oc review quoting /oc triage does not start Triage (#866)", () => {
  const body =
    "/oc review\n\nSecond opinion please. The /oc triage run is separate from this review.";
  assert.strictEqual(isTriageTrigger(body), false);
});

test("an anchored non-triage verb quoting /oc triage never starts Triage", () => {
  for (const body of [
    "/oc fix Address the feedback; /oc triage was requested earlier.",
    "/oc investigate why /oc triage behaves this way",
    "/oc wiki Document the /oc triage flow.",
    "/oc chore Sweep /oc triage references.",
    "/oc test Cover the /oc triage path.",
    "/oc Please proceed with this task. The /oc triage run is separate.",
    "/oc frobnicate A new verb quoting /oc triage in prose.",
    "/oc review The /oc triage run is separate from this review."
  ]) {
    assert.strictEqual(isTriageTrigger(body), false, body.slice(0, 40));
  }
});

test("a genuine /oc triage still starts Triage", () => {
  for (const body of [
    "/oc triage",
    "/oc triage issue #862",
    "/oc triage\n\nAnalyzing.",
    "/oc triageXYZ"
  ]) {
    assert.strictEqual(isTriageTrigger(body), true, body.slice(0, 40));
  }
});

test("an embedded triage still starts Triage", () => {
  assert.strictEqual(isTriageTrigger("Please run /oc triage on this issue."), true);
});

test("an embedded non-triage trigger does not start Triage", () => {
  assert.strictEqual(isTriageTrigger("Please run /oc fix on this."), false);
});

test("no trigger, a session link, or an empty body does not run", () => {
  assert.strictEqual(isTriageTrigger("Just a comment."), false);
  assert.strictEqual(isTriageTrigger("/oc triage done (https://opencode.ai/s/abc)"), false);
  assert.strictEqual(isTriageTrigger(""), false);
  assert.strictEqual(isTriageTrigger(undefined), false);
});

test("isAnchoredNonTriage tracks the anchored exemption", () => {
  assert.strictEqual(isAnchoredNonTriage("/oc review anything"), true);
  assert.strictEqual(isAnchoredNonTriage("/oc triage issue #1"), false);
  assert.strictEqual(isAnchoredNonTriage("please /oc review"), false);
});

test("both workflows agree on the same body", () => {
  const { isOpencodeTrigger } = require("../opencode-trigger.js");
  const bodies = [
    "/oc review\n\nquoting /oc triage in prose",
    "/oc triage issue #1",
    "Please run /oc triage on this issue.",
    "Please run /oc fix on this.",
    "/oc fix Address /oc triage references.",
    "Just a comment."
  ];
  for (const b of bodies) {
    const oc = isOpencodeTrigger(b);
    const tr = isTriageTrigger(b);
    // never both true
    assert.ok(!(oc && tr), "both true: " + b.slice(0, 40));
  }
});

test("triage.yml carries the anchored exemption the helper mirrors", () => {
  const workflow = fs.readFileSync(
    path.join(__dirname, "..", "..", "workflows", "triage.yml"),
    "utf8"
  );
  const exemption =
    "startsWith(github.event.comment.body, '/oc ') &&\n" +
    "        !startsWith(github.event.comment.body, '/oc triage')";
  assert.ok(
    workflow.includes(exemption),
    "triage.yml job if must exempt an anchored non-triage trigger"
  );
  assert.ok(
    workflow.includes("contains(github.event.comment.body, ' /oc triage')"),
    "triage.yml must keep the contains half for embedded triage commands"
  );
});

test("opencode-trigger re-exports triage predicate consistently", () => {
  const oc = require("../opencode-trigger.js");
  assert.strictEqual(typeof oc.isTriageTrigger, "function");
  assert.strictEqual(oc.isTriageTrigger("/oc triage x"), true);
  assert.strictEqual(oc.isTriageTrigger("/oc review quoting /oc triage"), false);
});
