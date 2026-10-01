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
 * An attendee's answer to a calendar invitation (EXO-90840), and the iCalendar
 * participation status it is sent as (RFC 5545 PARTSTAT).
 */
public enum InvitationAnswer {

  /** Accept: PARTSTAT=ACCEPTED. */
  ACCEPTED("ACCEPTED", "Accepted"),

  /** Maybe: PARTSTAT=TENTATIVE. */
  TENTATIVE("TENTATIVE", "Tentative"),

  /** Decline: PARTSTAT=DECLINED. */
  DECLINED("DECLINED", "Declined");

  /** The PARTSTAT value. */
  private final String partStat;

  /** What the reply's subject starts with. */
  private final String subjectPrefix;

  /**
   * @param status the PARTSTAT value
   * @param prefix what the reply's subject starts with, as other clients write it
   */
  InvitationAnswer(String status, String prefix) {
    this.partStat = status;
    this.subjectPrefix = prefix;
  }

  /**
   * The participation status this answer is sent as.
   *
   * @return the PARTSTAT value
   */
  public String getPartStat() {
    return partStat;
  }

  /**
   * The word the reply's subject starts with: English, whatever the user's language, as
   * a read receipt's is -- its reader is the organiser.
   *
   * @return the prefix, without its colon
   */
  public String getSubjectPrefix() {
    return subjectPrefix;
  }

  /**
   * The answer a PARTSTAT value stands for.
   *
   * @param partStat the PARTSTAT value, may be null
   * @return the answer, null for NEEDS-ACTION, DELEGATED, an unknown value or none
   */
  public static InvitationAnswer ofPartStat(String partStat) {
    for (InvitationAnswer answer : values()) {
      if (answer.partStat.equalsIgnoreCase(partStat == null ? "" : partStat.trim())) {
        return answer;
      }
    }
    return null;
  }
}
