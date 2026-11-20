'use strict';

// Testable mirror of the job-level `if:` gate in workflows/triage.yml.
// Mirrors .github/scripts/opencode-trigger.js so the two workflows cannot
// disagree about the same body: an anchored non-triage command that merely
// quotes " /oc triage" in prose never starts Triage, while a genuine
// "/oc triage ..." still does. Session-link and author gates stay in the
// workflow (unconditional); this predicate only inspects the body.

const oc = require("./opencode-trigger.js");

function startsWith(body, prefix) {
  return (body || "").indexOf(prefix) === 0;
}

function isAnchoredTriage(body) {
  return startsWith(body || "", "/oc triage");
}

module.exports = {
  isTriageTrigger: oc.isTriageTrigger,
  isAnchoredNonTriage: oc.isAnchoredNonTriage,
  isAnchoredTriage: isAnchoredTriage,
  isAnchoredNonExcluded: oc.isAnchoredNonExcluded,
  isAnchoredExcluded: oc.isAnchoredNonTriage,
  isOpencodeTrigger: oc.isOpencodeTrigger
};
