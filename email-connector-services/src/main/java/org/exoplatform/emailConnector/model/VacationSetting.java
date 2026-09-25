/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The user's automatic reply, as the form edits it and as eXo reads it back from the
 * mail server. eXo keeps no copy: this value exists on the way in (a save) and on the way
 * out (a read of what the server holds).
 * <p>
 * Days are the user's own calendar days ({@code YYYY-MM-DD}) in {@code timeZone}, the
 * IANA zone the browser reports -- the scheduled-send contract. Both are optional; with
 * either, the zone is required. The text is plain; the subject is one line.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VacationSetting {

  /** Where a reply eXo reads back was set. */
  public enum Source {
    /** eXo wrote it (the Sieve header of eXo's own script). */
    EXO,
    /** The server's own model holds it, whoever set it (BlueMind). */
    SERVER
  }

  /** Whether the reply is on; a reply switched off keeps its text on the server. */
  private boolean enabled;

  /** The first day, {@code YYYY-MM-DD}, or null for "from now". */
  private String  start;

  /** The last day, inclusive, {@code YYYY-MM-DD}, or null for "until switched off". */
  private String  end;

  /** The IANA zone the days are in. */
  private String  timeZone;

  /** The reply's subject, one line. */
  private String  subject;

  /** The reply's text, plain. */
  private String  text;

  /**
   * The minimum number of days between two replies to one sender; set by eXo from the
   * deployment property, ignored on the way in.
   */
  private int     days;

  /** Where the reply was set; answered on a read, ignored on the way in. */
  private Source  source;
}
