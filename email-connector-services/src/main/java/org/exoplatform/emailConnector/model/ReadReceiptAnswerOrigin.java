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
 * Where the durable answer to a read-receipt request came from (EXO-90435).
 */
public enum ReadReceiptAnswerOrigin {
  /** The user answered here: this add-on sent the receipt, or recorded the refusal. */
  LOCAL,
  /**
   * The mail server said the request was answered ({@code $MDNSent}, set by another
   * client or by this one before its cache was rebuilt). How it was answered is not
   * known: the keyword says "answered", nothing more.
   */
  SERVER
}
