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
 * Why a scheduled mail was not sent, as the {@code LAST_ERROR} column holds it: a
 * code the interface translates, never the mail server's own words, which may carry
 * addresses or internal host names and are in no language the owner reads.
 */
public enum ScheduledSendError {

  /** The mail server could not be reached, after every automatic retry. */
  NETWORK,

  /** The mail server refused one or more recipients. */
  RECIPIENT_REFUSED,

  /** The mail server refused the owner's credentials, or none could be produced. */
  AUTHENTICATION,

  /** A file the draft shows has no bytes behind it any more. */
  ATTACHMENT_GONE,

  /** The message is over the size a message may carry. */
  TOO_LARGE,

  /** The owner's mailbox is disconnected, disabled, or the owner's account is. */
  DISCONNECTED,

  /** The mail server refused the message itself. */
  REFUSED,

  /** The message could not be built. */
  INTERNAL,

  /** The node sending it stopped mid-send; whether it went out is unknown. */
  INTERRUPTED,

  /** The transmission failed once the message was on its way; whether it went out is unknown. */
  UNCONFIRMED,

  /**
   * The mail was written in a mailbox shared with its owner, and that mailbox is no longer
   * shared with them (EXO-90595): nothing was sent, and it is never sent from the owner's
   * own mailbox instead.
   */
  MAILBOX_UNSHARED,
  /**
   * The mail was to go out in the shared mailbox owner's name, and the owner's consent no
   * longer covers that name -- withdrawn, or narrowed from "as me" to "on my behalf"
   * (EXO-90584, PO decision Q-5): nothing was sent, and it is never sent in its writer's
   * own name instead.
   */
  SEND_MODE_WITHDRAWN,
  /**
   * The mail was to go out in the shared mailbox owner's name, and that name cannot be
   * used any more although the owner still allows it: the administrator switched writing
   * in another's name off or no longer declares that shape for the connector, or the
   * owner's mail server refused it before (EXO-90584). Nothing was sent, never in the
   * writer's own name instead.
   */
  SEND_MODE_UNAVAILABLE,
  /**
   * The owner's mail server refused this mail because it was sent in the owner's name
   * (EXO-90584): nothing was sent, the refusal is recorded on the share, and it is never
   * sent again in the writer's own name instead.
   */
  SEND_MODE_REFUSED
}
