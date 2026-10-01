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
 * Why a mail of an import was not added (EXO-90846). Each refused mail counts under
 * exactly one reason, and the end report says how many fell under each.
 */
public enum MailImportRefusal {

  /** Not an RFC 822 message: no header block, a malformed header line, no From or no Date. */
  NOT_A_MAIL,

  /** Larger than one mail may be, or a header block larger than one mail's may be. */
  TOO_LARGE,

  /** A zip entry whose name climbs out of the archive or names an absolute path. */
  UNSAFE_NAME,

  /** The mail server refused to add it. */
  SERVER_REFUSED;
}
