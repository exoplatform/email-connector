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
 * One reason a received message looks suspicious, as the reader's banner explains it
 * (EXO-90841). {@code shown} and {@code actual} are the two things the banner sets side
 * by side, both taken from the message and rendered as text, never as markup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailSecurityWarning {

  /** The rule the message tripped. */
  private EmailSecurityWarningType type;

  /**
   * What the message presents: the display name of the platform user it claims to be
   * ({@code IMPERSONATION}), the domain a link's text shows ({@code DECEPTIVE_LINK}), or
   * the authentication method that failed ({@code AUTHENTICATION_FAILED}).
   */
  private String                   shown;

  /**
   * What it really is: the sender's address ({@code IMPERSONATION}) or the domain the
   * link leads to ({@code DECEPTIVE_LINK}); null for {@code AUTHENTICATION_FAILED}.
   */
  private String                   actual;
}
