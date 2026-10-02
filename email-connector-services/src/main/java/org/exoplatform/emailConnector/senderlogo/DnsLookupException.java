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
package org.exoplatform.emailConnector.senderlogo;

/**
 * The DNS could not answer a sender logo lookup (EXO-90893): a timeout, a server
 * failure, no DNS at all. Distinct from "no record", which is an answer.
 */
public class DnsLookupException extends Exception {

  private static final long serialVersionUID = 2795136904227841573L;

  /**
   * Builds the exception.
   *
   * @param cause what the DNS client threw
   */
  public DnsLookupException(Throwable cause) {
    super("The DNS lookup of a sender logo failed", cause);
  }
}
