/*
 * Shared helpers for the orchestrator and ticket-runner scripts.
 *
 * Each function is a factory that takes { github, context, core } and returns
 * the concrete helper bound to those parameters.  This keeps the call-sites
 * identical to the original inline helpers.
 *
 * Usage (inside actions/github-script):
 *   const helpers = require("./orchestrator-helpers.js")({ github, context, core });
 *   const pr = await helpers.findPRForIssue(42);
 */

'use strict';

var isTriggerComment = require("./report-signals.js").isTriggerComment;

/**
 * Both runners persist their state into an issue body, which GitHub caps at
 * 65536 characters, so history cannot grow without bound. A suppressed CI
 * retry pushes one entry, and the CI-heal loop produces one completion per
 * SHA indefinitely: at ~127 bytes per entry a persistently red PR would make
 * the body unwritable and the runner would lose its own state. Keep the most
 * recent entries; history has no functional readers, it is diagnostic only.
 */
var MAX_HISTORY_ENTRIES = 200;

/**
 * Default review-feedback turn cap, shared by both runners so the single-ticket
 * (ticket-runner.js) and batch (orchestrator.js) paths cannot drift. A turn is
 * consumed when a human requests changes (native review) or the agent review
 * verdict is request-changes; exceeding the cap marks the ticket FAILED. The
 * default applies on every state-creation path (fresh /run, auto-start, new
 * orchestrator task); /run --turns N overrides it per ticket.
 */
var DEFAULT_MAX_TURNS = 7;

/**
 * Trusted-author policy lives in one place, shared with the workflow YAML
 * guards (auto-triage.yml, ant.yml). See trusted-author.js.
 */
var trustedAuthor = require("./trusted-author.js");
var TRUSTED_AUTHOR_LOGINS = trustedAuthor.TRUSTED_AUTHOR_LOGINS;

module.exports = function createHelpers({ github, context, core }) {
  const owner = context.repo.owner;
  const repo  = context.repo.repo;

  /** Post a /oc comment on an issue or PR to trigger the opencode workflow. */
  async function triggerTask(issueNumber, message) {
    if (message === undefined) message = "/oc Please proceed with this task.";
    core.info("Triggering task #" + issueNumber + " via comment...");
    await github.rest.issues.createComment({
      owner, repo, issue_number: issueNumber, body: message
    });
  }

  /**
   * Park a task whose review cannot be requested yet because CI is not green.
   *
   * The two flags exist to tell three states apart, so they must move together:
   * a review was requested (review_in_progress true), a review is waiting on CI
   * and no prompt exists (review_pending_ci true), and neither. Every park site
   * goes through here so the pair cannot drift. The caller supplies the history
   * event (and any extra fields) and keeps its own state write and comments.
   *
   * `flags` owns the two flags; `opts.history` is the array to append to when it
   * is not `flags.history` (the orchestrator keeps task flags on current_task
   * but history on the master state).
   */
  function parkForCI(flags, prNumber, opts) {
    opts = opts || {};
    var history = opts.history || flags.history;
    flags.review_in_progress = false;
    flags.review_pending_ci = true;
    var entry = { event: opts.event || "review_pending_ci", timestamp: new Date().toISOString() };
    if (prNumber != null) entry.pr = prNumber;
    if (opts.reason) entry.reason = opts.reason;
    if (opts.extra) {
      Object.keys(opts.extra).forEach(function (k) { entry[k] = opts.extra[k]; });
    }
    history.push(entry);
    return entry;
  }

  /** Find an open PR whose branch or body references the given issue number. */
  async function findPRForIssue(issueNumber) {
    var pulls = await github.rest.pulls.list({
      owner, repo, state: "open", per_page: 100
    });
    var issueRefRe = new RegExp("(?:Closes|Fixes|Resolves)\\s+#" + issueNumber + "\\b", "i");
    for (var i = 0; i < pulls.data.length; i++) {
      var pr = pulls.data[i];
      var headRef = (pr.head && pr.head.ref) || "";
      var body = pr.body || "";
      if (headRef.startsWith("opencode/issue" + issueNumber + "-") || issueRefRe.test(body)) {
        return pr.number;
      }
    }
    return null;
  }

  /** Build the standard review prompt that the runner/orchestrator posts on PRs. */
  function getReviewPrompt() {
    return "/oc review\n\nYou are reviewing a pull request for the automated pipeline. Your final line must be exactly one of:\nVerdict: approve\nVerdict: request-changes\n\nPost this verdict as an ordinary COMMENT on the pull request (a native GitHub review is not usable here: the PR is authored by the maintainer, so GitHub blocks self-approval).\n\nImmediately after the Verdict line, add one more line naming the model that produced this review, copied verbatim from the model your runtime reports (never invented):\nAssisted-by: <model id>\n\nA reviewer makes no commit, so this comment is the only place its model can be recorded; mergePR folds it into the merge commit as Reviewed-by. Use \"Verdict: request-changes\" if the PR needs code changes. Use \"Verdict: approve\" only if the PR is correct and ready for the next phase.";
  }

  /** Scan comments on a PR for the latest Verdict line. Returns 'approve', 'request-changes', or null. */
  async function getAgentReviewVerdict(prNumber) {
    var approveRe = /(?:\*{0,2}Verdict\*{0,2}:?\*{0,2}\s*:?\s*)approve\b/i;
    var requestChangesRe = /(?:\*{0,2}Verdict\*{0,2}:?\*{0,2}\s*:?\s*)request[-_]changes\b/i;

    function warn(msg) {
      if (core && core.warning) core.warning(msg);
    }

    // Newest-first within a source; request-changes still wins over approve
    // in the same body.
    function scanBodies(bodies) {
      for (var i = bodies.length - 1; i >= 0; i--) {
        var body = bodies[i] || "";
        // The review prompt itself quotes both verdicts; never read triggers as verdicts.
        if (isTriggerComment(body)) continue;
        if (requestChangesRe.test(body)) return "request-changes";
        if (approveRe.test(body)) return "approve";
      }
      return null;
    }

    var commentBodies = [];
    try {
      var comments = await github.rest.issues.listComments({
        owner, repo, issue_number: prNumber, per_page: 100
      });
      var cdata = (comments && comments.data) ? comments.data : comments;
      for (var i = 0; i < cdata.length; i++) {
        commentBodies.push(cdata[i].body || "");
      }
    } catch (err) {
      warn("getAgentReviewVerdict: issue-comment read failed for PR #" + prNumber + ": " + err.message);
    }

    var verdict = scanBodies(commentBodies);
    if (verdict) return verdict;

    // Native PR reviews are the fallback source. An agent that tries to
    // approve natively hits GitHub's self-approval block (PRs are authored by
    // the PAT owner, so GitHub refuses the maintainer's own review) and falls
    // back to a COMMENT review - the verdict then lives only in the review
    // stream, which the comment scan above cannot see. Issue #807 stalled in
    // a retry loop (and would have failed the ticket) exactly this way.
    try {
      var reviews = await github.rest.pulls.listReviews({
        owner, repo, pull_number: prNumber, per_page: 100
      });
      var rdata = (reviews && reviews.data) ? reviews.data : reviews;
      var reviewBodies = [];
      for (var j = 0; j < rdata.length; j++) {
        reviewBodies.push(rdata[j].body || "");
      }
      return scanBodies(reviewBodies);
    } catch (err) {
      warn("getAgentReviewVerdict: review read failed for PR #" + prNumber + ": " + err.message);
      return null;
    }
  }

  /** Kinds that may auto-merge without an explicit 'auto-merge' label. */
  var SAFE_AUTO_MERGE_KINDS = ["kind/chore", "kind/wiki", "kind/test"];

  /** Label-only check: explicit 'auto-merge' or implicit safe kind. No API beyond label reads. */
  async function isAutoMergeEligible(issueNumber, prNumber) {
    var issue = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var labels = extractLabels(issue.data);
    if (labels.includes("auto-merge")) return true;
    var implicit = SAFE_AUTO_MERGE_KINDS.some(function (k) { return labels.includes(k); });
    if (!implicit) return false;

    if (prNumber) {
      try {
        var pr = await github.rest.issues.get({
          owner, repo, issue_number: prNumber
        });
        if (extractLabels(pr.data).includes("auto-merge")) return true;
      } catch (_) {}
    }
    return implicit;
  }

  /** Forbidden diff paths: ASM, build config, plugin lists. Mirrors the resolver skill self-check. */
  var FORBIDDEN_DIFF_RE = /(^|\/)(core\/src\/native\/x86\/|jnode\.properties$|all\/build\.xml$|all\/conf\/)/;
  var TEST_PATH_RE = /(^|\/)(src\/test\/|tests\/)/;

  /**
   * True when the PR diff touches a forbidden path. Distinct from a size-budget
   * failure because a forbidden path is a hard wall no human can approve past by
   * being patient: the change is out of scope for auto-merge regardless of size.
   */
  async function touchesForbiddenPath(prNumber) {
    var files;
    try {
      files = await github.rest.pulls.listFiles({
        owner, repo, pull_number: prNumber, per_page: 100
      });
    } catch (e) {
      core.warning("touchesForbiddenPath: listFiles failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    // Same guard shape as isDiffSafe, for symmetry only: an unknown response
    // makes this return false either way, since an unreadable file list holds
    // no matching path. No test can distinguish the two formulations, which is
    // why the isDiffSafe twin has shape cases and this one does not.
    var list = files && files.data;
    if (!Array.isArray(list)) return false;
    for (var i = 0; i < list.length; i++) {
      if (FORBIDDEN_DIFF_RE.test(list[i].filename || "")) return true;
    }
    return false;
  }

  // Diff-size budgets. Production additions stay tightly capped because they
  // are what can change runtime behaviour. Test additions get a much larger
  // budget: a regression test for a small fix routinely exceeds the
  // production budget on its own, and counting the test against the
  // production cap rejected 7-line fixes for carrying their own tests.
  var MAX_PROD_ADDITIONS = 100;
  var MAX_TEST_ADDITIONS = 2000;
  var MAX_PROD_FILES = 5;
  var MAX_TEST_FILES = 20;

  /** True when the PR diff is small and touches no forbidden path. */
  async function isDiffSafe(prNumber) {
    var files;
    try {
      files = await github.rest.pulls.listFiles({
        owner, repo, pull_number: prNumber, per_page: 100
      });
    } catch (e) {
      core.warning("isDiffSafe: listFiles failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    // Fail closed on an unknown shape, exactly like the >= 100 guard below: a
    // response that resolved without a `data` array is not the same as an empty
    // diff, and `files.data || []` would collapse the two and read a malformed
    // partial response as "no changed files, therefore within budget".
    var list = files && files.data;
    if (!Array.isArray(list)) return false;
    // 100 is the per_page floor, so a full page means the diff may be bigger
    // than what was read: unknown, not safe. An empty array is different - it
    // means no changed files at all, which cannot exceed any budget, and
    // reporting it as "over budget" sent an empty diff to HUMAN_REVIEW on a lie.
    if (list.length >= 100) return false;

    var prodAdditions = 0;
    var testAdditions = 0;
    var prodFiles = 0;
    var testFiles = 0;
    for (var i = 0; i < list.length; i++) {
      var filename = list[i].filename || "";
      if (FORBIDDEN_DIFF_RE.test(filename)) return false;
      var additions = list[i].additions || 0;
      if (TEST_PATH_RE.test(filename)) {
        testAdditions += additions;
        testFiles += 1;
      } else {
        prodAdditions += additions;
        prodFiles += 1;
      }
    }
    return prodAdditions <= MAX_PROD_ADDITIONS &&
           testAdditions <= MAX_TEST_ADDITIONS &&
           prodFiles <= MAX_PROD_FILES &&
           testFiles <= MAX_TEST_FILES;
  }

  /**
   * Is the PR's merge-base still the tip of its base branch?
   *
   * A branch cut before a later master merge, squash-merged as-is, DELETES
   * whatever landed in the meantime: the squash applies the branch's whole tree,
   * so the PR's own diff looks clean while master silently loses another agent's
   * feature (JNodeOceanTheme, 270 lines, on #869 - ticket #872). The loss leaves
   * no CI signal, because the reverted files still compile.
   *
   * Compares the merge-base of head and base with the base branch's current tip.
   * Fail-closed: an unreadable PR or an API error reads as "not current", the
   * same direction as isDiffSafe and isCIGreen, because a merge is unrecoverable
   * while a refusal is not.
   *
   * @return {Promise<{current: boolean, base: string, lag: Array}>} where lag
   *   lists the commits present on the base branch but not on the head - the
   *   ones the squash would revert.
   */
  /**
   * The compare API, however the running Octokit generation spells it.
   *
   * repos.compare and repos.compareCommits are the same endpoint
   * GET /repos/{owner}/{repo}/compare/{base}...{head} under two names, because
   * the SDK renamed it. Which one a job gets depends on the actions/github-script
   * version on the runner image, which changes under us without any edit here -
   * the runner image is not pinned. So the name is resolved by capability at
   * call time, and a runtime that names neither falls through to a plain
   * request against the documented URL, so this gate cannot be disabled by an
   * SDK rename.
   *
   * @param headSha the PR head commit
   * @param baseRef the base branch name
   * @return the compare response, in every shape github returns it
   */
  async function compareHeadToBase(headSha, baseRef) {
    var args = { owner: owner, repo: repo, base: headSha, head: baseRef };
    var names = ["compare", "compareCommits"];
    for (var i = 0; i < names.length; i++) {
      if (typeof github.rest.repos[names[i]] === "function") {
        core.info("checkBaseCurrency: using repos." + names[i]);
        return github.rest.repos[names[i]](args);
      }
    }
    // Neither name exists: build the documented URL by hand. A github.request
    // that does not understand the shape will throw, and the caller already
    // treats an unreadable compare as "not current".
    return github.request({
      method: "GET",
      url: "/repos/" + owner + "/" + repo + "/compare/" + headSha + "..." + baseRef
    });
  }

  async function checkBaseCurrency(prNumber) {
    var stale = { current: false, base: "", lag: [] };
    var pr;
    try {
      pr = await github.rest.pulls.get({ owner, repo, pull_number: prNumber });
    } catch (e) {
      core.warning("checkBaseCurrency: pulls.get failed for PR #" + prNumber + ": " + e.message);
      return stale;
    }
    var baseRef = pr.data && pr.data.base && pr.data.base.ref;
    var headSha = pr.data && pr.data.head && pr.data.head.sha;
    if (!baseRef || !headSha) return stale;
    stale.base = baseRef;

    var tip;
    try {
      var branch = await github.rest.repos.getBranch({ owner, repo, branch: baseRef });
      tip = branch.data && branch.data.commit && branch.data.commit.sha;
    } catch (e) {
      core.warning("checkBaseCurrency: getBranch(" + baseRef + ") failed: " + e.message);
      return stale;
    }
    if (!tip) return stale;

    // compare head...base: the commits it returns are on the base branch but not
    // on the head - exactly what the squash would revert.
    //
    // The route's JS name moved between Octokit generations: older ones expose
    // repos.compare, the generation actions/github-script now ships exposes
    // repos.compareCommits for the same GET /compare/{base}...{head} endpoint.
    // This gate learned it the hard way on ticket #884: the runner's runtime
    // answered "github.rest.repos.compare is not a function", so the check fell
    // into its fail-closed branch and refused to merge a branch that was in fact
    // current with master. Resolve by capability, never by assuming which
    // generation a runner image happens to carry, and keep the plain request as
    // a last resort for a runtime that names neither.
    try {
      var compare = await compareHeadToBase(headSha, baseRef);
      var mergeBase = compare.data && compare.data.merge_base_commit &&
        compare.data.merge_base_commit.sha;
      if (!mergeBase) return stale;
      var lag = [];
      var commits = compare.data.commits || [];
      for (var i = 0; i < commits.length; i++) {
        var c = commits[i] || {};
        var message = (c.commit && c.commit.message) || "";
        lag.push({ sha: (c.sha || "").slice(0, 8), subject: message.split("\n")[0] });
      }
      // compare paginates at 250 commits; total_commits is the exact count, so
      // the caller's "N commits" is honest even on a very lagging branch. total
      // is carried separately from lag because lag may hold a synthetic
      // truncation entry, and the history counter must not count it.
      var total = typeof compare.data.total_commits === "number"
        ? compare.data.total_commits : lag.length;
      if (total > lag.length) {
        lag.push({ sha: "", subject: "... and " + (total - lag.length) + " more" });
      }
      return { current: mergeBase === tip, base: baseRef, lag: lag, total: total };
    } catch (e) {
      core.warning("checkBaseCurrency: compare failed for PR #" + prNumber + ": " + e.message);
      return stale;
    }
  }

  /**
   * Human-readable refusal naming the commits a squash would revert.
   *
   * @param prNumber the PR
   * @param info the checkBaseCurrency result
   * @param entry how the merge was requested - "review" (an agent review the
   *   ticket-runner re-posts), "human" (a native GitHub approval), or "auto"
   *   (an agent approval that would have auto-merged). The remedy line differs:
   *   re-requesting a review does nothing for a human approval, which must be
   *   given again on the updated branch, and an auto-merge refusal has no
   *   approval to repeat at all.
   */
  function baseCurrencyComment(prNumber, info, entry) {
    var base = info.base || "master";
    var lines = [
      "Not merging: PR #" + prNumber + "'s branch is behind " + base +
        ", so a squash would revert those commits."
    ];
    if (info.lag && info.lag.length) {
      lines.push("");
      for (var i = 0; i < info.lag.length && i < 11; i++) {
        var item = info.lag[i];
        lines.push(item.sha ? "- `" + item.sha + "` " + item.subject : "- " + item.subject);
      }
      lines.push("");
      lines.push("Merge " + base + " into the branch (or re-push it on a current base), then " +
        (entry === "human"
          ? "approve again on the GitHub PR Review UI - the approval above does not carry over."
          : entry === "auto"
          ? "approve it on the GitHub PR Review UI; the agent verdict above does not merge it."
          : "re-request the review.") +
        " The diff must then contain only this PR's own changes.");
    } else {
      lines.push("The base branch state could not be read, so the merge is refused rather than guessed at.");
    }
    return lines.join("\n");
  }

  /** True when CI check runs on the PR head SHA show success and no failure. */
  async function isCIGreen(prNumber) {
    var pr;
    try {
      pr = await github.rest.pulls.get({ owner, repo, pull_number: prNumber });
    } catch (e) {
      core.warning("isCIGreen: pulls.get failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    var sha = pr.data && pr.data.head && pr.data.head.sha;
    if (!sha) return false;
    var runs;
    try {
      var res = await github.rest.checks.listForRef({ owner, repo, ref: sha, per_page: 100 });
      runs = (res.data && res.data.check_runs) || [];
    } catch (e) {
      core.warning("isCIGreen: listForRef failed for " + sha + ": " + e.message);
      return false;
    }
    var BAD = ["failure", "cancelled", "timed_out", "action_required"];
    // Only runs named for the ant.yml ("Java CI") jobs count. Names are the job
    // ids, which GitHub uses as the check-run name when a job defines no `name:`.
    // `heal` is excluded: it is skipped on green and would only ever make the
    // gate read as pending. Note this filters by name, not by workflow, so a
    // job with the same id in another workflow would also count.
    var JAVA_CI_JOBS = ["build", "test", "boot32", "boot64"];
    var seen = {};
    for (var i = 0; i < runs.length; i++) {
      var r = runs[i];
      if (JAVA_CI_JOBS.indexOf(r.name) < 0) continue;
      // A run that has not finished is not green. Without this the gate reads a
      // completed green `build` job as whole-workflow green while the QEMU boot
      // jobs are still running, which is the race the gate exists to prevent.
      if (r.status !== "completed") return false;
      if (BAD.indexOf(r.conclusion) >= 0) return false;
      if (r.conclusion === "success") seen[r.name] = true;
    }
    // Every Java CI job must have reported green. Without this a run showing only
    // a green `build` passes while the boot tests have not run at all.
    for (var j = 0; j < JAVA_CI_JOBS.length; j++) {
      if (!seen[JAVA_CI_JOBS[j]]) return false;
    }
    return true;
  }

  // Trailer shape produced by .github/hooks/prepare-commit-msg and by the
  // resolver skill's review summary. Bullet and bold markers are tolerated
  // because a reviewer may write the line as a list item or emphasise the key,
  // which would otherwise hide an otherwise valid attribution. The value is
  // restricted to a model-id charset so arbitrary comment prose can never be
  // replayed verbatim into a commit message.
  var MODEL_TRAILER_RE = /^[ \t]*(?:[-*+][ \t]*)?\*{0,2}Assisted-by\*{0,2}:[ \t*]*([A-Za-z0-9._:@/-]+)/gim;

  /**
   * Distinct model IDs advertised by `Assisted-by:` trailers on the PR's review
   * and comment bodies, in first-seen order.
   *
   * The resolver models reach the squash body through commit trailers, but a
   * reviewer makes no commit, so its model only ever exists as a line in the
   * review summary comment. Review verdicts and `/oc approve` both arrive as
   * issue comments rather than review objects, so both sources are scanned.
   * Trigger comments are skipped for the same reason `getAgentReviewVerdict`
   * skips them: they quote the skill text that spells the trailer out, and
   * reading one would credit the human who typed the trigger.
   */
  async function collectReviewerModels(prNumber) {
    var bodies = [];
    var sources = [
      function () { return github.rest.pulls.listReviews({ owner, repo, pull_number: prNumber, per_page: 100 }); },
      function () { return github.rest.issues.listComments({ owner, repo, issue_number: prNumber, per_page: 100 }); }
    ];
    for (var s = 0; s < sources.length; s++) {
      var res;
      try {
        res = await sources[s]();
      } catch (e) {
        core.warning("collectReviewerModels: read failed for PR #" + prNumber + ": " + e.message);
        continue;
      }
      var list = res && res.data;
      if (!Array.isArray(list)) continue;
      for (var i = 0; i < list.length; i++) {
        bodies.push((list[i] && list[i].body) || "");
      }
    }
    var seen = {};
    var models = [];
    for (var j = 0; j < bodies.length; j++) {
      var body = bodies[j];
      if (isTriggerComment(body)) continue;
      MODEL_TRAILER_RE.lastIndex = 0;
      var m;
      while ((m = MODEL_TRAILER_RE.exec(body)) !== null) {
        var id = m[1];
        if (seen[id]) continue;
        seen[id] = true;
        models.push(id);
      }
    }
    return models;
  }

  /** Split a commit message into its subject line and the remainder. */
  function splitMessage(message) {
    var lines = String(message == null ? "" : message).replace(/\s+$/, "").split("\n");
    var subject = (lines.shift() || "").trim();
    var body = lines.join("\n").replace(/^\n+/, "").replace(/\s+$/, "");
    return { subject: subject, body: body };
  }

  /**
   * Compose the squash-merge message BODY, reproducing what the repo's
   * `squash_merge_commit_message = COMMIT_MESSAGES` setting would have produced
   * and adding the `Reviewed-by:` trailer.
   *
   * Only a body is produced, and that is what `commit_message` means on the
   * merge API: it replaces the message body while the subject stays under the
   * repo's `squash_merge_commit_title = COMMIT_OR_PR_TITLE` setting, which also
   * appends the ` (#N)` reference. Putting the subject in here too would
   * duplicate it in the merged commit. The first commit's subject is dropped
   * because it is the one the title setting consumes; the remaining commits keep
   * subject and body, matching what COMMIT_MESSAGES concatenates.
   *
   * Passing `commit_message` overrides the repo setting, so the body has to be
   * rebuilt here or the Reviewed-by trailer cannot be added at all. Returns null
   * when there is nothing to attribute, which leaves the merge call untouched.
   */
  function buildMergeMessage(commitMessages, models, prBody) {
    if (!Array.isArray(models) || models.length === 0) return null;
    var parts = [];
    var split = (Array.isArray(commitMessages) ? commitMessages : []).map(splitMessage);
    var lead = split.length ? split[0].body : "";
    // The agent commits are subject-plus-trailer by construction (the
    // prepare-commit-msg hook appends Assisted-by and the agent writes nothing
    // else), so without a fallback the squash body degenerates to trailers and
    // says nothing about the change - which is what #840's merge message came
    // out as. The PR description is the prose source for exactly this case.
    if (!hasSubstance(lead)) {
      var desc = stripRunLinks(prBody);
      if (desc) lead = desc;
    }
    if (lead) parts.push(lead);
    for (var i = 1; i < split.length; i++) {
      var chunk = "";
      if (split[i].subject) chunk = split[i].subject;
      if (split[i].body) chunk += (chunk ? "\n\n" : "") + split[i].body;
      if (chunk) parts.push(chunk);
    }
    // The writer's attribution rides in the commits' Assisted-by trailers, but
    // the fallback above replaces the commit bodies with the PR description -
    // which silently dropped crouton's credit on #845's merge. Re-collect the
    // distinct trailers from the commit messages and the PR body so the writer
    // survives whatever replaces the prose; skip the ones already present, so a
    // kept commit body does not end up crediting its author twice.
    var writers = collectAssistedBy(commitMessages, prBody);
    var kept = parts.join("\n");
    var missing = writers.filter(function (w) {
      return !new RegExp("assisted-by:\\s*" + w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "i").test(kept);
    });
    if (missing.length) {
      parts.push("Assisted-by: " + missing.join(", "));
    }
    parts.push("Reviewed-by: " + models.join(", "));
    return parts.join("\n\n");
  }

  // True when the text says something beyond attribution trailers. An empty
  // body and a body of only "Assisted-by: x" lines are both "no substance".
  function hasSubstance(text) {
    if (!text) return false;
    return text.split("\n").some(function (line) {
      var t = line.trim();
      return t.length > 0 && !/^[A-Za-z-]+-by:\s/i.test(t);
    });
  }

  // Distinct Assisted-by trailers across the commit messages and the PR body,
  // in first-seen order. The writer's credit has to survive the description
  // fallback replacing the commit bodies, so it is collected separately rather
  // than left inside text that may be dropped.
  function collectAssistedBy(commitMessages, prBody) {
    var seen = {};
    var out = [];
    var texts = (Array.isArray(commitMessages) ? commitMessages : []).concat([prBody || ""]);
    for (var i = 0; i < texts.length; i++) {
      if (!texts[i]) continue;
      var lines = String(texts[i]).split("\n");
      for (var j = 0; j < lines.length; j++) {
        var m = lines[j].match(/^\s*Assisted-by:\s*(\S+)\s*$/i);
        if (m && !seen[m[1]]) {
          seen[m[1]] = true;
          out.push(m[1]);
        }
      }
    }
    return out;
  }

  // The PR body ends with a "[github run](...)" line the agent appends; it is
  // noise in a permanent merge message. Everything else (including "Closes
  // #N") is worth keeping.
  function stripRunLinks(body) {
    if (!body) return "";
    return body.split("\n").filter(function (line) {
      return !/^\[github run\]\(/.test(line.trim());
    }).join("\n").trim();
  }

  /** Return true if the user object represents a bot. */
  function isBotUser(user) {
    return trustedAuthor.isBotUser(user);
  }

  /** Return true if the user is an allowlisted maintainer (fail-closed on a missing user). */
  function isTrustedAuthor(user) {
    return trustedAuthor.isTrustedAuthor(user);
  }

  /**
   * The squash subject GitHub would use, and the issue number it should carry.
   *
   * GitHub uses the PR title for a multi-commit squash but the single commit's
   * SUBJECT for a one-commit squash, and the agent's commit subjects routinely
   * lack the repo's "#<issue> - " prefix (flagged as a nit on both #821 and
   * #845). The result is a merged commit like "Fixed FAT12/16 root directory
   * writes (#845)" with no issue link. So the subject is built here and passed
   * as commit_title, which overrides both the repo setting and GitHub's
   * one-commit rule.
   */
  function mergeSubject(pr) {
    var data = pr && pr.data ? pr.data : {};
    var title = data.title || "";
    var num = closingIssueNumber(data) || issueFromHeadRef(data) ||
      issueFromTitle(title);
    var strip = title.replace(/^#\d+\s*-\s*/, "").trim();
    var subject = num ? "#" + num + " - " + strip : strip;
    // GitHub appends " (#N)" itself only on the default-title path; an explicit
    // commit_title is used verbatim, so the reference is added here.
    return subject + (data.number ? " (#" + data.number + ")" : "");
  }

  function closingIssueNumber(data) {
    var m = (data.body || "").match(/(?:closes|fixes|resolves)\s+#(\d+)/i);
    return m ? parseInt(m[1], 10) : 0;
  }

  function issueFromHeadRef(data) {
    var ref = (data.head && data.head.ref) || "";
    var m = ref.match(/^opencode\/issue(\d+)-/);
    return m ? parseInt(m[1], 10) : 0;
  }

  function issueFromTitle(title) {
    var m = (title || "").match(/^#(\d+)\s*-/);
    return m ? parseInt(m[1], 10) : 0;
  }

  /** Squash-merge a PR and delete its head branch. */
  async function mergePR(prNumber) {
    core.info("Merging PR #" + prNumber + "...");
    var pr = await github.rest.pulls.get({
      owner, repo, pull_number: prNumber
    });
    var mergeArgs = {
      owner, repo, pull_number: prNumber, merge_method: "squash"
    };
    var commitMessage = null;
    try {
      var models = await collectReviewerModels(prNumber);
      if (models.length) {
        var commits = await github.rest.pulls.listCommits({
          owner, repo, pull_number: prNumber, per_page: 100
        });
        var list = commits && commits.data;
        if (Array.isArray(list)) {
          commitMessage = buildMergeMessage(list.map(function (c) {
            return (c && c.commit && c.commit.message) || "";
          }), models, (pr.data && pr.data.body) || "");
        } else {
          core.warning("mergePR: listCommits returned no data for PR #" + prNumber +
            "; merge will use the repository default message");
        }
      }
    } catch (e) {
      core.warning("mergePR: could not build the Reviewed-by message for PR #" +
        prNumber + ": " + e.message);
      commitMessage = null;
    }
    // commit_message replaces the message BODY only; the subject stays under the
    // repo's squash_merge_commit_title setting. Only override when there is
    // something to add: an unnecessary commit_message would replace the
    // repository's COMMIT_MESSAGES template for no reason.
    if (commitMessage) mergeArgs.commit_message = commitMessage;
    mergeArgs.commit_title = mergeSubject(pr);
    try {
      await github.rest.pulls.merge(mergeArgs);
    } catch (err) {
      var message = String((err && err.message) || "");
      if (err && err.status === 204) {
        core.info("Merge API returned 204; verifying PR state");
      } else if (/Unexpected end of JSON input/i.test(message)) {
        core.info("Merge API returned an empty body; verifying PR state");
      } else {
        throw err;
      }
    }
    var merged = await github.rest.pulls.get({
      owner, repo, pull_number: prNumber
    });
    if (!merged.data || merged.data.merged !== true) {
      throw new Error("merge not confirmed; PR state=" +
        (merged.data && merged.data.state ? merged.data.state : "unknown"));
    }
    try {
      await github.rest.git.deleteRef({
        owner, repo, ref: "heads/" + pr.data.head.ref
      });
    } catch (e) {
      core.warning("Could not delete branch " + pr.data.head.ref + ": " + e.message);
    }
  }

  /** List open PR numbers whose head SHA matches (for CI-completion wakeups). */
  async function findPRsForSHA(sha) {
    var pulls = await github.rest.pulls.list({
      owner, repo, state: "open", per_page: 100
    });
    var out = [];
    for (var i = 0; i < pulls.data.length; i++) {
      var pr = pulls.data[i];
      if (pr.head && pr.head.sha === sha) out.push(pr.number);
    }
    return out;
  }
  /** Extract labels from an issue response as an array of strings. */
  function extractLabels(issueData) {
    return (issueData.labels || []).map(function (l) {
      return (typeof l === "string") ? l : l.name;
    });
  }

  /** Labels that signal a task is \"complete\" (no further dev work needed). */
  var COMPLETION_LABELS = [
    "agent/done", "agent/investigated", "agent/skip",
    "agent/blocked", "agent/needs-info", "agent/duplicate"
  ];

  /** Labels that short-circuit the task (skip, blocked, duplicate, etc. -- not 'agent/done'). */
  var SHORT_CIRCUIT_LABELS = [
    "agent/skip", "agent/needs-info", "agent/investigated",
    "agent/blocked", "agent/duplicate"
  ];

  return {
    triggerTask: triggerTask,
    findPRForIssue: findPRForIssue,
    getReviewPrompt: getReviewPrompt,
    getAgentReviewVerdict: getAgentReviewVerdict,
    isAutoMergeEligible: isAutoMergeEligible,
    isDiffSafe: isDiffSafe,
    checkBaseCurrency: checkBaseCurrency,
    baseCurrencyComment: baseCurrencyComment,
    touchesForbiddenPath: touchesForbiddenPath,
    parkForCI: parkForCI,
    DEFAULT_MAX_TURNS: DEFAULT_MAX_TURNS,
    isCIGreen: isCIGreen,
    SAFE_AUTO_MERGE_KINDS: SAFE_AUTO_MERGE_KINDS,
    isBotUser: isBotUser,
    collectReviewerModels: collectReviewerModels,
    buildMergeMessage: buildMergeMessage,
    isTrustedAuthor: isTrustedAuthor,
    mergePR: mergePR,
    extractLabels: extractLabels,
    findPRsForSHA: findPRsForSHA,
    COMPLETION_LABELS: COMPLETION_LABELS,
    SHORT_CIRCUIT_LABELS: SHORT_CIRCUIT_LABELS
  };
};

// Module-level exports so the runners and YAML steps can read them without instantiating.
module.exports.MAX_HISTORY_ENTRIES = MAX_HISTORY_ENTRIES;
module.exports.DEFAULT_MAX_TURNS = DEFAULT_MAX_TURNS;
module.exports.TRUSTED_AUTHOR_LOGINS = TRUSTED_AUTHOR_LOGINS;

