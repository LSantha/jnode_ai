'use strict';

// Testable mirror of the job-level `if:` gate in workflows/opencode.yml.
// The workflow expression cannot call this file (it is evaluated before any
// step runs), so the two must be kept in step by hand; the wiring test in
// tests/opencode-trigger.test.js asserts the workflow carries the same
// anchored-exemption clause this module implements.
//
// The gate: a comment starts an opencode run when it carries a "/oc "
// trigger (anchored at the start, or preceded by a space) and is not a
// triage or approve command and not an agent self-trigger. The triage and
// approve exclusions used to match anywhere in the body, so an anchored
// "/oc review" prompt that merely quoted "/oc approve" in prose was
// silently skipped (issue #862). The fix is anchored exemption: when the
// body itself starts with "/oc " but not with "/oc approve" or
// "/oc triage", its own (first) verb is by construction already decided,
// so later quotes of another verb cannot re-route it. An embedded trigger
// (not anchored) keeps the broad contains exclusion, which is the safe
// direction: it can only under-run a genuine approve/triage command.

function startsWith(body, prefix) {
  return body.indexOf(prefix) === 0;
}

function contains(body, needle) {
  return body.indexOf(needle) >= 0;
}

function isAnchoredNonExcluded(body) {
  return (
    startsWith(body, "/oc ") &&
    !startsWith(body, "/oc approve") &&
    !startsWith(body, "/oc triage")
  );
}

function isOpencodeTrigger(body) {
  var text = body || "";
  var hasTrigger = contains(text, " /oc ") || startsWith(text, "/oc ");
  if (!hasTrigger) return false;
  if (contains(text, "opencode.ai/s/")) return false;
  if (isAnchoredNonExcluded(text)) return true;
  if (startsWith(text, "/oc approve") || contains(text, " /oc approve")) return false;
  if (startsWith(text, "/oc triage") || contains(text, " /oc triage")) return false;
  return true;
}

module.exports = {
  isOpencodeTrigger: isOpencodeTrigger,
  isAnchoredNonExcluded: isAnchoredNonExcluded
};
