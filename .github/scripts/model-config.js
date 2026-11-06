/*
 * Model selection for the agent runs, in one place.
 *
 * Two axes are recorded per run: the model, and its reasoning-effort variant
 * ("effort"). Both live here rather than in workflow literals because they
 * drift: the runner writes the model into every Assisted-by/Reviewed-by
 * trailer, and an effort that is wrong for a model silently under-runs it
 * (OpenCode's variant is per-model: "max"/"xhigh" exist on some providers and
 * are ignored or rejected on others). The session archaeology store records
 * model+variant per session, so whatever is chosen here is what the history
 * shows later.
 *
 * This module is the whole edit surface: swapping the review model, or
 * correcting an effort, is a map entry - not a workflow edit.
 *
 * Editing rules:
 *   - ROLE_MODELS: which model runs which role. Adding a role means the caller
 *     asks for it (opencode.yml resolves by verb; triage.yml asks for its own).
 *   - MODEL_VARIANTS: the effort each model wants. Only list a model when its
 *     preference differs from DEFAULT_VARIANT, so there is no dead entry to
 *     maintain. An unlisted model keeps the default rather than failing the
 *     job on a typo here.
 */
'use strict';

// Effort per model. OpenCode's accepted set is provider-dependent; record what
// actually works for each model rather than forcing one value on all of them.
// "max" is the strongest lever where the model offers it (pulse-alpha), and is
// deliberately not applied to deepseek-free: that would change the effort of
// every ticket run as a side effect, which is a decision to take on its own.
const MODEL_VARIANTS = {
  "opencode/deepseek-v4.1-flash-free": "high",
  "opencode/pulse-alpha-free": "max"
};

const DEFAULT_VARIANT = "high";

// Role = what the run is for. The verb in the trigger comment picks the role
// (see resolveModel); triage.yml asks for its own by name.
//
// coding is the workhorse: every ticket's DEV turn and every /oc fix. It runs
// pulse-alpha (free, and the only role where the effort matters most), which
// also keeps the review and triage roles on deepseek - a different model
// reviewing the work than the one that wrote it. First live trial: the ticket
// filed immediately after this change.
const ROLE_MODELS = {
  coding: "opencode/pulse-alpha-free",
  review: "opencode/deepseek-v4.1-flash-free",
  triage: "opencode/deepseek-v4.1-flash-free"
};

function variantFor(model) {
  return MODEL_VARIANTS[model] || DEFAULT_VARIANT;
}

function modelFor(role) {
  var model = ROLE_MODELS[role];
  if (!model) {
    throw new Error("Unknown agent role: " + role);
  }
  return { role: role, model: model, variant: variantFor(model) };
}

// Review is the only verb that changes the model today. Match it the way the
// workflow's own trigger gate matches a verb: anchored at the start of the
// body. A "/oc fix" prompt that quotes "/oc review" must still run the coding
// model - a contains() match would flip the model on any body that merely
// mentions a review (the multi-line /run body already showed how a loose verb
// match silently misroutes a trigger).
var REVIEW_RE = /^\s*\/oc\s+review\b/;

function resolveModel(commentBody) {
  var body = commentBody || "";
  return modelFor(REVIEW_RE.test(body) ? "review" : "coding");
}

module.exports = {
  MODEL_VARIANTS: MODEL_VARIANTS,
  DEFAULT_VARIANT: DEFAULT_VARIANT,
  ROLE_MODELS: ROLE_MODELS,
  variantFor: variantFor,
  modelFor: modelFor,
  resolveModel: resolveModel
};
