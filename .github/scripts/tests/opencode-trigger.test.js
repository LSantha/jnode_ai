const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const { isOpencodeTrigger, isAnchoredNonExcluded } = require("../opencode-trigger.js");

test("an anchored /oc review quoting /oc approve still runs (#862)", () => {
  const body =
    "/oc review\n\nSecond opinion please. Line 15: an issue body rewritten " +
    "without the marker -> parseState null -> /oc approve refused.";
  assert.strictEqual(isOpencodeTrigger(body), true);
});

test("an anchored trigger of any non-approve verb still runs when it quotes another verb", () => {
  for (const body of [
    "/oc fix Address the feedback; /oc approve was refused earlier.",
    "/oc investigate why /oc approve and /oc triage behave this way",
    "/oc wiki Document the /oc approve flow.",
    "/oc chore Sweep /oc triage references.",
    "/oc test Cover the /oc approve path.",
    "/oc Please proceed with this task.",
    "/oc frobnicate A new verb quoting /oc approve in prose.",
    "/oc review The /oc triage run is separate from this review."
  ]) {
    assert.strictEqual(isOpencodeTrigger(body), true, body.slice(0, 40));
  }
});

test("a body that genuinely is /oc approve never starts a run", () => {
  for (const body of [
    "/oc approve",
    "/oc approve --force",
    "/oc approve\n\nMerging now.",
    "/oc approveXYZ"
  ]) {
    assert.strictEqual(isOpencodeTrigger(body), false, body.slice(0, 40));
  }
});

test("a body that genuinely is /oc triage never starts a run", () => {
  assert.strictEqual(isOpencodeTrigger("/oc triage issue #862"), false);
});

test("an embedded approve or triage is still excluded", () => {
  assert.strictEqual(isOpencodeTrigger("Please run /oc approve on this PR."), false);
  assert.strictEqual(isOpencodeTrigger("Please run /oc triage on this issue."), false);
});

test("an embedded non-excluded trigger still runs", () => {
  assert.strictEqual(isOpencodeTrigger("Please run /oc fix on this."), true);
});

test("no trigger, a session link, or an empty body does not run", () => {
  assert.strictEqual(isOpencodeTrigger("Just a comment."), false);
  assert.strictEqual(isOpencodeTrigger("/oc fix done (https://opencode.ai/s/abc)"), false);
  assert.strictEqual(isOpencodeTrigger(""), false);
  assert.strictEqual(isOpencodeTrigger(undefined), false);
});

test("isAnchoredNonExcluded tracks the anchored exemption", () => {
  assert.strictEqual(isAnchoredNonExcluded("/oc review anything"), true);
  assert.strictEqual(isAnchoredNonExcluded("/oc approve"), false);
  assert.strictEqual(isAnchoredNonExcluded("/oc triage issue #1"), false);
  assert.strictEqual(isAnchoredNonExcluded("please /oc review"), false);
});

test("opencode.yml carries the anchored exemption the helper mirrors", () => {
  const workflow = fs.readFileSync(
    path.join(__dirname, "..", "..", "workflows", "opencode.yml"),
    "utf8"
  );
  const exemption =
    "startsWith(github.event.comment.body, '/oc ') &&\n" +
    "        !startsWith(github.event.comment.body, '/oc approve') &&\n" +
    "        !startsWith(github.event.comment.body, '/oc triage')";
  assert.ok(
    workflow.includes(exemption),
    "opencode.yml job if must exempt an anchored non-approve/non-triage trigger"
  );
  assert.ok(
    workflow.includes("contains(github.event.comment.body, ' /oc approve')"),
    "opencode.yml must keep the contains half for embedded approve commands"
  );
  assert.ok(
    workflow.includes("contains(github.event.comment.body, ' /oc triage')"),
    "opencode.yml must keep the contains half for embedded triage commands"
  );
});
