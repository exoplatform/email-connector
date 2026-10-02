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

/*
 * The pictures of the senders the mail list shows (EXO-90891), one cache for the page.
 * The server answers, in this order, the platform user's own photo, the photo of the
 * viewing user's own contact at the address (EXO-90908), and the platform's generated
 * picture of a user with no photo; an address with none of them is drawn with initials.
 * A listed row carries no picture -- the server resolves none for a list, that is one
 * directory query per row --, so the rows on screen ask for theirs here, by address,
 * and the addresses asked within one moment leave in one request
 * (POST /contacts/avatars). An address is asked once: its answer, a picture or none
 * (drawn with initials), is kept for the page; a failed request is not. A row off
 * screen asks nothing: a shared IntersectionObserver hands an address over only once
 * its avatar is in view. The reader, which reads a whole message with its sender's
 * picture, fills the same cache (rememberSenderAvatar).
 */
/**
 * The most addresses one request carries: EmailSenderProfileService.AVATARS_MAX_ADDRESSES
 * on the server, which refuses more. Change both together.
 */
export const MAX_AVATAR_BATCH = 50;

/** How long the addresses asked for are gathered before they leave, in ms. */
const BATCH_DELAY_MS = 50;

// The answers, by normalized address: a URL, or null for an address with no picture
// (no platform photo, no contact of the viewer's with one). An address whose request
// failed is not kept: drawn with initials, it is asked again by the next avatar of it
// watched -- a row drawn again, or showing that sender anew -- not by the one already
// asked for, which is watched no more.
const answers = new Map();

// Read by every avatar showing a picture, bumped on every answer: a Map is not
// observable in Vue 2, one counter is cheaper than a reactive key per sender. Made on
// first use: the platform's Vue is a global, and a context without it gets a plain
// object.
let state = null;

/**
 * The counter the avatars read, made on first use.
 *
 * @returns {Object} {version}
 */
function answersState() {
  if (!state) {
    state = typeof Vue !== 'undefined' && Vue.observable ? Vue.observable({ version: 0 }) : { version: 0 };
  }
  return state;
}

// The addresses waiting for the next request, and the ones a request is out for.
const queued = new Set();
const asking = new Set();
let batchTimer = null;

// The avatars watched for coming into view, element -> address.
const watched = new Map();
let observer = null;

/**
 * The address as the server keys it.
 *
 * @param {string} address - the address
 * @returns {string} the trimmed, lower-cased address, or null when it is not one
 */
function keyOf(address) {
  const key = (address || '').trim().toLowerCase();
  return key.indexOf('@') > 0 ? key : null;
}

/**
 * The picture of the platform user at an address, as far as the page knows it.
 * Reactive: an avatar reading it shows the picture once its answer lands.
 *
 * @param {string} address - the sender's address
 * @returns {string} the picture's URL, or null when none is known (yet)
 */
export function senderAvatarUrl(address) {
  // Read for the dependency: an answer landing bumps it.
  if (answersState().version < 0) {
    return null;
  }
  return answers.get(keyOf(address)) || null;
}

/**
 * Records what the page learnt of a sender elsewhere: the reader reads a whole message
 * with its sender's picture -- a platform user's photo, or for anybody else the
 * generated initials (a data: URL), which says there is no photo to ask for.
 *
 * @param {string} address - the sender's address
 * @param {string} avatarUrl - the picture the server gave the message's sender
 * @returns {void}
 */
export function rememberSenderAvatar(address, avatarUrl) {
  const key = keyOf(address);
  if (!key || !avatarUrl || answers.has(key)) {
    return;
  }
  answers.set(key, avatarUrl.startsWith('data:') ? null : avatarUrl);
  answersState().version++;
}

/**
 * Asks for the picture of an address, with the others asked for within the same
 * moment, unless it is known or already asked for.
 *
 * @param {string} address - the sender's address
 * @returns {void}
 */
export function requestSenderAvatar(address) {
  const key = keyOf(address);
  if (!key || answers.has(key) || queued.has(key) || asking.has(key)) {
    return;
  }
  queued.add(key);
  if (!batchTimer) {
    batchTimer = setTimeout(sendQueued, BATCH_DELAY_MS);
  }
}

/**
 * Sends the addresses gathered, MAX_AVATAR_BATCH a request.
 *
 * @returns {void}
 */
function sendQueued() {
  batchTimer = null;
  const addresses = Array.from(queued);
  queued.clear();
  for (let start = 0; start < addresses.length; start += MAX_AVATAR_BATCH) {
    askFor(addresses.slice(start, start + MAX_AVATAR_BATCH));
  }
}

/**
 * Asks the server for one batch of pictures, and keeps every address's answer. A failed
 * request keeps nothing: the addresses are asked again by the next avatar of them
 * watched.
 *
 * @param {Array<string>} addresses - the normalized addresses, at most MAX_AVATAR_BATCH
 * @returns {void}
 */
function askFor(addresses) {
  addresses.forEach(address => asking.add(address));
  fetch('/email-connector/rest/contacts/avatars', {
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(addresses),
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error(`Avatars not answered: ${resp?.status}`);
    }
    return resp.json();
  }).then(found => {
    addresses.forEach(address => answers.set(address, found?.[address] || null));
  }).catch(() => {
    // Initials meanwhile; nothing kept, so the next avatar of them asks again.
  }).finally(() => {
    addresses.forEach(address => asking.delete(address));
    answersState().version++;
  });
}

/**
 * The observer every avatar shares; null where the browser has none, and the
 * addresses are then asked for at once.
 *
 * @returns {IntersectionObserver} the observer, or null
 */
function sharedObserver() {
  if (!observer && typeof IntersectionObserver === 'function') {
    observer = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting && watched.has(entry.target)) {
        requestSenderAvatar(watched.get(entry.target));
        unwatchSenderAvatar(entry.target);
      }
    }));
  }
  return observer;
}

/**
 * Asks for an address's picture once its avatar comes into view -- at once when it is
 * known, already asked for, or the browser cannot tell.
 *
 * @param {Element} element - the avatar
 * @param {string} address - the sender's address
 * @returns {void}
 */
export function watchSenderAvatar(element, address) {
  const key = keyOf(address);
  unwatchSenderAvatar(element);
  if (!key || answers.has(key) || queued.has(key) || asking.has(key)) {
    return;
  }
  const watcher = element && sharedObserver();
  if (!watcher) {
    requestSenderAvatar(key);
    return;
  }
  watched.set(element, key);
  watcher.observe(element);
}

/**
 * Stops watching an avatar: it left the page, or shows another sender.
 *
 * @param {Element} element - the avatar
 * @returns {void}
 */
export function unwatchSenderAvatar(element) {
  if (element && watched.delete(element)) {
    observer?.unobserve(element);
  }
}

/**
 * Forgets every answer and every pending request: for the tests, which share one page.
 *
 * @returns {void}
 */
export function resetSenderAvatars() {
  answers.clear();
  queued.clear();
  asking.clear();
  watched.clear();
  clearTimeout(batchTimer);
  batchTimer = null;
  observer?.disconnect();
  observer = null;
  state = null;
}
