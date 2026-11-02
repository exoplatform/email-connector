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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The organiser or an attendee of a calendar invitation (EXO-90840), as the invitation
 * names them. Text written by the sender: the reader shows it as text, never as markup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarInvitationPerson {

  /** The display name (CN), null when the invitation gives none. */
  private String name;

  /** The address, without its {@code mailto:}; null when it is not a mail address. */
  private String address;

  /**
   * The person's participation status as the invitation states it (ACCEPTED, TENTATIVE,
   * DECLINED, NEEDS-ACTION, DELEGATED), null for the organiser.
   */
  private String partStat;
}
