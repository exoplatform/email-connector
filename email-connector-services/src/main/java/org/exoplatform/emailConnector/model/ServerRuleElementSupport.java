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

/**
 * One form element's answer in {@link ServerRuleCapabilities}.
 *
 * @param supported whether the element can be used
 * @param reasonKey why not, a message key; null when supported
 */
public record ServerRuleElementSupport(boolean supported, String reasonKey) {

  /** The answer for a supported element. */
  public static final ServerRuleElementSupport SUPPORTED_ELEMENT = new ServerRuleElementSupport(true, null);

  /**
   * The answer for an unsupported element.
   *
   * @param reasonKey the message key explaining why
   * @return the answer
   */
  public static ServerRuleElementSupport unsupported(String reasonKey) {
    return new ServerRuleElementSupport(false, reasonKey);
  }
}
