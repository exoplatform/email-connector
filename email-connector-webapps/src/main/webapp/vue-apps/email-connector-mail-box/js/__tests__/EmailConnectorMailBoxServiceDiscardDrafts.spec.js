/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// EXO-90438 — a bulk Discard is a bounded loop over the per-draft endpoint, because
// discarding is a per-draft operation under that draft's own lock rather than a folder
// move. What the loop owes its caller: one request per draft, never more than a few in
// flight, no draft skipped because an earlier one failed, and a count of each outcome —
// a 409 apart, since it says the mail is already on its way out.

import { discardDrafts } from '../EmailConnectorMailBoxService.js';

/**
 * A fetch whose every call is recorded and resolved by hand, so the spec can see how
 * many requests are in flight at one moment.
 *
 * @param {Object} statusByUrlPart the HTTP status to answer per draft id; 200 by default
 * @returns {Object} {fetch, pending, releaseAll}
 */
function deferredFetch(statusByUrlPart = {}) {
  const pending = [];
  const fetchMock = jest.fn(url => new Promise(resolve => {
    const draftLocalId = decodeURIComponent(url.substring(url.lastIndexOf('/') + 1));
    const status = statusByUrlPart[draftLocalId] || 200;
    pending.push({ url, draftLocalId, resolve: () => resolve({ ok: status === 200, status }) });
  }));
  // Four microtask turns: a discard's answer travels through the response check, the
  // outcome tally and the worker's own continuation before the next request is sent,
  // and the drain must not run ahead of that chain.
  const settle = () => Promise.resolve().then(() => null).then(() => null).then(() => null);
  /**
   * Answers the requests in flight, one per turn, until none is left and none follows.
   *
   * @param {Number} idleTurns how many turns have passed with nothing in flight
   * @returns {Promise} resolving once every draft has been answered
   */
  const drain = idleTurns => {
    if (pending.length) {
      pending.shift().resolve();
      return settle().then(() => drain(0));
    }
    if (idleTurns >= 2) {
      return Promise.resolve();
    }
    return settle().then(() => drain(idleTurns + 1));
  };
  return {
    fetch: fetchMock,
    pending,
    releaseAll: () => drain(0),
  };
}

describe('discardDrafts', () => {
  afterEach(() => delete global.fetch);

  it('sends one DELETE per draft, to the per-draft endpoint', async () => {
    const stub = deferredFetch();
    global.fetch = stub.fetch;

    const outcome = discardDrafts(['local-1', 'local-2'], 4);
    await stub.releaseAll();

    expect(await outcome).toEqual({ discarded: 2, failed: 0, conflicted: 0 });
    expect(stub.fetch).toHaveBeenCalledTimes(2);
    expect(stub.fetch.mock.calls[0][0]).toBe('/email-connector/rest/email-box/drafts/local-1');
    expect(stub.fetch.mock.calls[0][1].method).toBe('DELETE');
  });

  it('keeps no more than the allowed number of requests in flight', async () => {
    const stub = deferredFetch();
    global.fetch = stub.fetch;

    const outcome = discardDrafts(['a', 'b', 'c', 'd', 'e', 'f'], 2);
    // Nothing has answered yet, so only the first two workers have sent anything.
    expect(stub.fetch).toHaveBeenCalledTimes(2);

    await stub.releaseAll();
    expect(await outcome).toEqual({ discarded: 6, failed: 0, conflicted: 0 });
    expect(stub.fetch).toHaveBeenCalledTimes(6);
  });

  it('a draft that fails stops nothing, and is counted', async () => {
    const stub = deferredFetch({ 'local-2': 500 });
    global.fetch = stub.fetch;

    const outcome = discardDrafts(['local-1', 'local-2', 'local-3'], 4);
    await stub.releaseAll();

    expect(await outcome).toEqual({ discarded: 2, failed: 1, conflicted: 0 });
    expect(stub.fetch).toHaveBeenCalledTimes(3);
  });

  it('a 409 is counted apart: the scheduled send has already claimed that draft', async () => {
    const stub = deferredFetch({ 'local-2': 409 });
    global.fetch = stub.fetch;

    const outcome = discardDrafts(['local-1', 'local-2'], 4);
    await stub.releaseAll();

    expect(await outcome).toEqual({ discarded: 1, failed: 0, conflicted: 1 });
  });

  it('asks nothing for an empty selection', async () => {
    global.fetch = jest.fn();

    expect(await discardDrafts([])).toEqual({ discarded: 0, failed: 0, conflicted: 0 });
    expect(global.fetch).not.toHaveBeenCalled();
  });
});
