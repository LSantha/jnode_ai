/*
 * Single source of truth for who may drive the automation.
 *
 * Consumed by the orchestration scripts (via orchestrator-helpers.js) and by
 * the inline actions/github-script guards in auto-triage.yml and ant.yml. Each
 * of those jobs must `actions/checkout` the repo first so this file is on disk;
 * ant.yml's heal job checks out the trusted base ref, never the PR head, so a
 * fork cannot inject this module into a job that holds GH_PAT and issues:write.
 *
 * TRUSTED_AUTHOR_LOGINS is a login allowlist, not `author_association`:
 * "COLLABORATOR" would silently trust any future collaborator, and the
 * automation itself acts as this login (via `GH_PAT`), so association cannot
 * tell a human from a prompt-injected agent.
 */

'use strict';

// TEMPORARY (untrusted-gate validation): empty allowlist, reverted immediately
// in the following commit. Do not keep.
var TRUSTED_AUTHOR_LOGINS = [];

/** Return true if the user object represents a bot. */
function isBotUser(user) {
  return !!user && ((user.type || "").toLowerCase() === "bot" ||
    (user.login || "").indexOf("[bot]") >= 0 ||
    (user.login || "").indexOf("app/") === 0);
}

/** Return true if the user is an allowlisted maintainer (fail-closed on a missing user). */
function isTrustedAuthor(user) {
  if (!user || isBotUser(user)) return false;
  return TRUSTED_AUTHOR_LOGINS.indexOf(user.login || "") >= 0;
}

module.exports = {
  TRUSTED_AUTHOR_LOGINS: TRUSTED_AUTHOR_LOGINS,
  isBotUser: isBotUser,
  isTrustedAuthor: isTrustedAuthor
};
