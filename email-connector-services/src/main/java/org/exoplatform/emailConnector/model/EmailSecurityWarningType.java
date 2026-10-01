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
 * The rules that make a received message look suspicious (EXO-90841).
 */
public enum EmailSecurityWarningType {
  /** The sender's display name is a platform user's, the address is outside the organisation. */
  IMPERSONATION,
  /** A link's text shows a web address on one domain while it leads to another. */
  DECEPTIVE_LINK,
  /** The receiving server reports that the sender's domain failed DMARC, SPF or DKIM. */
  AUTHENTICATION_FAILED,
}
