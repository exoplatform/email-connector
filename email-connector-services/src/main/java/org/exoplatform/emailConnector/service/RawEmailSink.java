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
package org.exoplatform.emailConnector.service;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Where {@code EmailBoxService#writeRawEmail} writes a message's RFC 822 source
 * (EXO-90842). Asked for its stream only once the caller's access was checked and the
 * message was found on the mail server, so a caller that answers over HTTP sets its
 * headers here, and every refusal or absence before that is still free to become a
 * status of its own.
 */
@FunctionalInterface
public interface RawEmailSink {

  /**
   * Opens the stream the source is written to, once.
   *
   * @param subject the message's subject as cached, possibly null, for a file name
   * @param size the message's size as the mail server reports it, or -1 when unknown
   * @return the stream to write the source to; the caller of the sink flushes it and
   *         leaves closing it to its owner
   * @throws IOException when the stream cannot be opened
   */
  OutputStream open(String subject, int size) throws IOException;
}
