'use strict';

function isRefusalComment(body) {
  if (!body) return false;
  return /^\s*##\s*(?:\ud83e\udd16\s*)?Refusal\b/im.test(body);
}

function isNeedsInfoComment(body) {
  if (!body) return false;
  return /needs more info from reporter|needs the following|suggested next:\s*needs-info/i.test(body);
}

function isTriageComment(body) {
  if (!body) return false;
  return /## .*Triage/i.test(body);
}

function isTriageClearComment(body) {
  return isTriageComment(body) && !isNeedsInfoComment(body) && !isRefusalComment(body);
}

function isTriggerComment(body) {
  if (!body) return false;
  return /(^|\s)\/(oc|run|orchestrate)(\s|$)/.test(body);
}

module.exports = {
  isRefusalComment,
  isNeedsInfoComment,
  isTriageComment,
  isTriageClearComment,
  isTriggerComment
};
