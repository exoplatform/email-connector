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
package org.exoplatform.emailConnector.service.bluemind;

import org.apache.commons.lang3.StringUtils;

/**
 * BlueMind's {@code MailFilter.Vacation}, member for member (core API javadoc 5.0.7563:
 * {@code boolean enabled; Date start; Date end; String subject; String text; String
 * textHtml}). The two dates are instants in epoch milliseconds, the encoding assumed for a
 * {@code java.util.Date} member of BlueMind's JSON (to verify on a live server).
 *
 * @param enabled whether the reply is on
 * @param start the first instant the reply is sent, or null for "from now"
 * @param end the last instant the reply is sent, or null for "until switched off"
 * @param subject the reply's subject, possibly null
 * @param text the reply's plain text, possibly null
 * @param textHtml the reply's HTML text, possibly null
 */
public record BlueMindVacation(boolean enabled, Long start, Long end, String subject, String text, String textHtml) {

  /**
   * Whether nothing was ever set: off, no window, no subject, no text.
   *
   * @return true when every member is empty
   */
  public boolean isEmpty() {
    return !enabled && start == null && end == null && StringUtils.isBlank(subject) && StringUtils.isBlank(text)
        && StringUtils.isBlank(textHtml);
  }
}
