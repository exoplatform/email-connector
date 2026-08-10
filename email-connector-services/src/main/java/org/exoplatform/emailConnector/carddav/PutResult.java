/**
 * Copyright (C) 2025 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */

package org.exoplatform.emailConnector.carddav;

/**
 * What one PUT of a vCard came back with.
 * <p>
 * The status travels alongside the etag on purpose: a 412 is the server
 * refusing a precondition — "this card already exists" under
 * {@code If-None-Match: *}, "somebody changed it first" under a later
 * {@code If-Match} — and the caller decides what that refusal means. Folding
 * it into an exception here would make the client decide business policy,
 * which is the sync service's job, not the protocol's.
 *
 * @param status the HTTP status the server answered — 200, 201 or 204 for a
 *          write that happened, 412 for a precondition the server refused
 * @param etag the entity tag of the stored card exactly as the server sent it
 *          (quotes included, the same raw shape PROPFIND listings answer), or
 *          null — not every server returns one, and callers must live with
 *          that by re-reading on the next sync
 * @param location where the server actually stored the card, as an absolute
 *          URL resolved against the request, or null when the server said
 *          nothing. Not decoration: BlueMind files a PUT card under a path of
 *          its own choosing, so the URL the caller asked for and the entry
 *          that now exists are two different names — and the caller must
 *          bookkeep the server's, not its own
 */
public record PutResult(int status, String etag, String location) {

  /** The precondition-failed status, the one answer that is a refusal, not an error. */
  public static final int PRECONDITION_FAILED = 412;

  /**
   * Whether the server refused the precondition rather than storing the card.
   * Under {@code If-None-Match: *} this means the resource already exists.
   *
   * @return true when the card was NOT written because a precondition failed
   */
  public boolean preconditionFailed() {
    return status == PRECONDITION_FAILED;
  }
}
