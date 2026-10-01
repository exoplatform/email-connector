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
package org.exoplatform.emailConnector.utils;

import org.exoplatform.emailConnector.model.MailImportRefusal;

/**
 * Where {@link MailArchiveReader} hands what it finds in an imported file (EXO-90846):
 * each mail that passed every check, as its bytes, and each one it refused, with the
 * reason. Either answer tells the reader whether to go on.
 */
public interface MailArchiveSink {

  /**
   * A mail that passed every check of the reader: an RFC 822 message within the size
   * limits, as the file holds it.
   *
   * @param message the message's bytes
   * @return true to go on reading, false to stop
   */
  boolean mail(byte[] message);

  /**
   * A mail the reader refused.
   *
   * @param reason why
   * @return true to go on reading, false to stop
   */
  boolean refused(MailImportRefusal reason);
}
