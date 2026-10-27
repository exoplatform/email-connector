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
package org.exoplatform.emailConnector.constant;

import java.util.Locale;

/**
 * Who wrote a received message, as its headers and MIME structure declare it
 * (EXO-91995): a mail server reporting that it could not deliver, an automatic
 * reply, or a person. Decided at sync by
 * {@code EmailMessageKindUtils.classify}, never from the text of the message, so
 * the answer does not depend on the language or the mail server.
 */
public enum MessageKind {

  /**
   * A delivery failure report (RFC 3464 {@code delivery-status} with an
   * {@code Action: failed} recipient, or an {@code X-Failed-Recipients} header).
   */
  BOUNCE,

  /**
   * Sent without a person typing it (RFC 3834 {@code Auto-Submitted}, the
   * {@code X-Autoreply}/{@code X-Autorespond} conventions): an out-of-office, a
   * read receipt, a delivery delay notice.
   */
  AUTO_REPLY,

  /** Typed by a person, a mail relayed by a mailing list included. */
  NORMAL;

  /**
   * The value the agent tools show, lower case.
   *
   * @return {@code bounce}, {@code auto_reply} or {@code normal}
   */
  public String toolValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
