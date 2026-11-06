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

// EXO-92345 -- the page keys a sender's picture by the address with its ASCII letters
// only lower-cased, as the server answers it: an address that only looks like another
// one (the Kelvin sign for K) is never shown that one's picture, while the same address
// in other ASCII capitals is.
import { rememberSenderAvatar, resetSenderAvatars, senderAvatarUrl } from '../EmailConnectorSenderAvatars.js';

const KATE_PICTURE = '/portal/rest/v1/social/users/kate/avatar';

describe('the sender picture key (EXO-92345)', () => {
  beforeEach(() => {
    resetSenderAvatars();
    rememberSenderAvatar('kate@example.org', KATE_PICTURE);
  });

  it('answers a lookalike address no picture', () => {
    expect(senderAvatarUrl('\u212Aate@example.org')).toBeNull();
  });

  it('answers the same address in other ASCII capitals its picture', () => {
    expect(senderAvatarUrl('Kate@Example.org')).toBe(KATE_PICTURE);
  });

  it('keeps a lookalike\'s own picture apart from the genuine one\'s', () => {
    rememberSenderAvatar('\u212Aate@example.org', '/sender-logo/lookalike.png');

    expect(senderAvatarUrl('kate@example.org')).toBe(KATE_PICTURE);
    expect(senderAvatarUrl('\u212Aate@example.org')).toBe('/sender-logo/lookalike.png');
  });
});
