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

import java.util.Arrays;
import java.util.List;

/**
 * One MIME part of a message as read from the server, with the addresses its message
 * says it is from: every {@code From} address and the {@code Sender}, as the server
 * reads the message's header.
 *
 * @param content the decoded bytes, capped by the reader
 * @param authors the {@code From} and {@code Sender} addresses, bare, possibly empty
 */
public record MessagePart(byte[] content, List<String> authors) {

  /**
   * @param content the decoded bytes
   * @param authors the addresses, null for none
   */
  public MessagePart {
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MessagePart part && Arrays.equals(content, part.content) && authors.equals(part.authors);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(content) + authors.hashCode();
  }

  @Override
  public String toString() {
    return "MessagePart[" + (content == null ? 0 : content.length) + " bytes, authors=" + authors + "]";
  }
}
