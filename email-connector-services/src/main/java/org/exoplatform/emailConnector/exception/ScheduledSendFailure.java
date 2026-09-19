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

import org.exoplatform.emailConnector.model.ScheduledSendError;

/**
 * Why a scheduled mail's transmission did not complete, classified by what it means
 * for the at-most-once guarantee:
 * <ul>
 * <li>{@link Kind#TRANSIENT}: the mail server could not even be reached, so nothing
 * was transmitted and an automatic retry is safe;</li>
 * <li>{@link Kind#PERMANENT}: refused before the mail server accepted anything, so
 * nothing was transmitted, but retrying unchanged would be refused again;</li>
 * <li>{@link Kind#AMBIGUOUS}: failed once the message was on its way, so it may have
 * been delivered and must never be sent again without its owner.</li>
 * </ul>
 * The error is a code, never the server's text.
 */
public class ScheduledSendFailure extends Exception {

  private static final long        serialVersionUID = 1L;

  /** What a failure means for sending the mail again. */
  public enum Kind {
    /** Nothing transmitted; retry automatically. */
    TRANSIENT,
    /** Nothing transmitted; retrying unchanged would fail again. */
    PERMANENT,
    /** Possibly transmitted; never retry automatically. */
    AMBIGUOUS
  }

  private final Kind               kind;

  private final ScheduledSendError error;

  /**
   * @param kind what the failure means for sending again
   * @param error the code shown to the owner
   * @param cause the underlying failure, for the log only
   */
  public ScheduledSendFailure(Kind kind, ScheduledSendError error, Throwable cause) {
    super(kind + " " + error, cause);
    this.kind = kind;
    this.error = error;
  }

  /**
   * @return what the failure means for sending again
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * @return the code shown to the owner
   */
  public ScheduledSendError getError() {
    return error;
  }
}
