'use strict';

// Testable mirror of the job-level `if:` gate in workflows/triage.yml.
// Mirrors .github/scripts/opencode-trigger.js so the two workflows cannot
// disagree about the same body: an anchored non-triage command that merely
// quotes " /oc triage" in prose never starts Triage, while a genuine
// "/oc triage ..." still does. An embedded " /oc triage" still starts
// Triage, and an anchored "/oc approve" runs nothing. Session-link and
// author gates stay in the workflow (unconditional); this predicate only
// inspects the body.

const oc = require("./opencode-trigger.js");

module.exports = {
  isTriageTrigger: oc.isTriageTrigger,
  isAnchoredNonTriage: oc.isAnchoredNonTriage
};
