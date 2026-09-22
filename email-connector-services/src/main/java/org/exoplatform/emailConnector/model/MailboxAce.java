/**
 * Copyright (C) 2026 eXo Platform SAS
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
package org.exoplatform.emailConnector.model;

/**
 * One entry of a mailbox's access-control list as an engine read it (GETACL, or a
 * vendor's REST rows) or wrote it: who, which RFC 4314 letters, what the server
 * itself said, and the preset the engine recognises in it.
 * <p>
 * {@code nativeRights} exists because a server's own vocabulary is not always
 * letters: BlueMind's {@code _acls} rows carry verbs ({@code Read}, {@code Write},
 * {@code SendAs}, {@code Freebusy}, {@code Invitation}, {@code Manage}, ...) and the
 * translation to letters is lossy both ways (delegation plan, section 3.4). The
 * letters drive the logic and the chrome; the native string is kept for display, so
 * a share the server's own interface created with verbs no letter expresses is shown
 * as it is rather than silently collapsed. On an IMAP engine the two are the same
 * letters.
 *
 * @param identifier the server-side identifier the rights are granted to (a login, an
 *          address, {@code anyone})
 * @param rights the letters
 * @param nativeRights the server's own vocabulary as observed, for display; the
 *          letters themselves on an IMAP engine
 * @param preset the preset the engine recognises in this entry, CUSTOM when none --
 *          the engine decides, because the letters a preset pushes differ per server
 *          (BlueMind's read share is {@code lrp}, an IMAP Reader is {@code lrs})
 */
public record MailboxAce(String identifier, MailboxRights rights, String nativeRights, DelegationPreset preset) {

  /**
   * An entry of a server whose vocabulary is the letters themselves, its preset read
   * letter for letter.
   *
   * @param identifier the identifier
   * @param rights the letters
   * @return the entry
   */
  public static MailboxAce ofLetters(String identifier, MailboxRights rights) {
    MailboxRights safe = rights == null ? MailboxRights.NONE : rights;
    return new MailboxAce(identifier, safe, safe.letters(), DelegationPreset.fromRights(safe));
  }
}
