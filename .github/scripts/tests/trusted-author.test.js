const test = require("node:test");
const assert = require("node:assert/strict");

const trustedAuthor = require("../trusted-author.js");
const helpers = require("../orchestrator-helpers.js");

test("TRUSTED_AUTHOR_LOGINS is the single allowlist", () => {
  assert.deepEqual(trustedAuthor.TRUSTED_AUTHOR_LOGINS, ["LSantha"]);
});

test("isBotUser rejects a missing user", () => {
  assert.equal(trustedAuthor.isBotUser(null), false);
});

test("isBotUser detects the Bot user type case-insensitively", () => {
  assert.equal(trustedAuthor.isBotUser({ type: "Bot" }), true);
});

test("isBotUser detects the [bot] login suffix", () => {
  assert.equal(trustedAuthor.isBotUser({ login: "github-actions[bot]" }), true);
});

test("isBotUser detects the app/ login prefix", () => {
  assert.equal(trustedAuthor.isBotUser({ login: "app/jnode-orchestrator" }), true);
});

test("isBotUser passes a plain human user", () => {
  assert.equal(trustedAuthor.isBotUser({ login: "some-contributor", type: "User" }), false);
});

test("isTrustedAuthor accepts the allowlisted login", () => {
  assert.equal(trustedAuthor.isTrustedAuthor({ login: "LSantha", type: "User" }), true);
});

test("isTrustedAuthor rejects a stranger", () => {
  assert.equal(trustedAuthor.isTrustedAuthor({ login: "stranger", type: "User" }), false);
});

test("isTrustedAuthor rejects a bot impersonating the allowlisted login", () => {
  assert.equal(trustedAuthor.isTrustedAuthor({ login: "LSantha[bot]", type: "Bot" }), false);
});

test("isTrustedAuthor fails closed on a missing user", () => {
  assert.equal(trustedAuthor.isTrustedAuthor(null), false);
});

test("isTrustedAuthor fails closed on an empty user object", () => {
  assert.equal(trustedAuthor.isTrustedAuthor({}), false);
});

test("orchestrator-helpers re-exports the same allowlist", () => {
  assert.equal(helpers.TRUSTED_AUTHOR_LOGINS, trustedAuthor.TRUSTED_AUTHOR_LOGINS);
});