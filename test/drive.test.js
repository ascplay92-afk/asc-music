import assert from "node:assert/strict";
import test from "node:test";
import { DriveConnector } from "../web/src/drive.js";

test("Google Drive clears expired in-memory credentials after an unauthorized response", () => {
  const drive = new DriveConnector({ clientId: "client-id", apiKey: "restricted-key" });
  drive.accessToken = "expired-access-token";
  drive.tokenExpiresAt = Date.now() + 60_000;

  assert.throws(
    () => drive.checkedResponse(new Response(null, { status: 401 })),
    /authorization expired/i,
  );
  assert.equal(drive.accessToken, "");
  assert.equal(drive.tokenExpiresAt, 0);
});
