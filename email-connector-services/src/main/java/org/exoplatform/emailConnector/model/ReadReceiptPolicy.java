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
 * What the user wants done when a message they receive asks for a read receipt
 * (RFC 8098, the {@code Disposition-Notification-To} header). A user setting, never
 * a per-message choice: the per-message choice is the banner the policy leads to.
 */
public enum ReadReceiptPolicy {
  /** Show the banner and let the user decide, message by message. The default. */
  ASK,
  /** Never send a receipt, and never show the banner. */
  NEVER,
  /**
   * Send the receipt without asking -- but only where it is safe to (see
   * {@code ReadReceiptService}); every other case still asks. An administrator can
   * disable this choice platform-wide, and a stored ALWAYS then behaves as ASK.
   */
  ALWAYS
}
