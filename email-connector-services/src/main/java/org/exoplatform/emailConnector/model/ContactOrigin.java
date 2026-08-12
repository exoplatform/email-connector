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
package org.exoplatform.emailConnector.model;

/**
 * How a contact came to be created — the question the automatic address-book
 * push turns on, and deliberately NOT the same question as
 * {@link EmailContactSource}.
 * <p>
 * The source cannot answer it. A contact typed into the form and a contact
 * read out of a 500-card {@code .vcf} both land as
 * {@link EmailContactSource#MANUAL}, because that is what they are: rows the
 * user owns, that no server and no mailbox vouches for. What tells them apart
 * is not the row, it is the act — one person, one contact, one Save, with
 * somebody watching. Only that act may publish a card to a third-party server
 * without asking again, so only the caller who witnessed it can say so, and it
 * has to say so explicitly.
 * <p>
 * Which is why there is no "unknown" value and no null contract: a caller
 * either names {@link #USER_FORM} or gets {@link #UNATTENDED}, including by
 * taking the create overload that carries no origin at all. Silence is the
 * safe answer, so a caller written next year — a new importer, a new agent, a
 * new backfill — is quiet by construction rather than by remembering to be.
 */
public enum ContactOrigin {

  /**
   * One contact, authored through the contact form, with a person watching the
   * Save: typed by hand, confirmed from a vCard somebody sent (mail attachment
   * or chat), or started from a click on a sender's address. The form always
   * shows what will be stored before it is stored, so this is the one path
   * where the user has already seen — and confirmed — the exact card that
   * would go out.
   */
  USER_FORM,

  /**
   * Everything else: the vCard file import, the automatic collection from
   * mail, the one-time backfill, the hand-over of a rebound mailbox's
   * contacts, the directory import, an agent's tool call. Bulk or invisible,
   * sometimes both — and publishing any of them silently would be a privacy
   * event rather than a convenience. These rows still publish, by the user's
   * own click on the contact or through the reviewed bulk queue; they simply
   * never leave on their own.
   */
  UNATTENDED
}
