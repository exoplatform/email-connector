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
package org.exoplatform.emailConnector.exception;

/**
 * A write was asked for on a folder of a shared mailbox, and the RFC 4314 right it
 * needs is not among the ones the server grants this delegate on that folder.
 * <p>
 * It is an {@link IllegalAccessException} on purpose, so that it travels the refusal
 * path this add-on already has -- every write entry point declares it, and the REST
 * layer maps it to <b>401</b> (the add-on's own convention, not the platform's 403;
 * see the domain doc). What this subclass adds is the one thing a bare refusal loses:
 * <b>which letter was missing</b>, as a message code the interface translates, so the
 * user reads "you may read this mailbox but not delete from it" rather than a blank
 * denial, and so a client whose chrome went stale (the share was narrowed while the
 * drawer was open) can correct itself instead of retrying.
 * <p>
 * The refusal is a courtesy, never the enforcement. The server enforces the rights,
 * and would answer the same operation with a {@code NO} -- but a {@code NO} arrives as
 * a {@code MessagingException} whose text may name internal paths and the acting user,
 * which is not a response body. This exception is what turns that into a fixed code.
 */
public class MailboxRightMissingException extends IllegalAccessException {

  private static final long serialVersionUID = 1L;

  /**
   * The message-code prefix; the missing letter is appended, giving codes such as
   * {@code emailConnector.delegation.right.missing.s} for mark-read,
   * {@code ….w} for star, {@code ….t} for delete or move-out,
   * {@code ….i} for move-in and {@code ….x} for folder rename or delete.
   */
  public static final String CODE_PREFIX = "emailConnector.delegation.right.missing.";

  private final char         right;

  /**
   * @param right the RFC 4314 letter the folder's rights lack
   */
  public MailboxRightMissingException(char right) {
    super(CODE_PREFIX + right);
    this.right = right;
  }

  /**
   * @return the RFC 4314 letter the folder's rights lack
   */
  public char getRight() {
    return right;
  }
}
