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

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A message as the mail server holds it, for the "Show original" view (EXO-90842): its
 * header block and its RFC 822 source, both as text, never as markup. The source is cut
 * at {@code EmailBoxService#RAW_SOURCE_SHOWN_MAX_BYTES}; a longer message says so through
 * {@link #truncated}, and the whole of it is the {@code .eml} download.
 */
@Data
@NoArgsConstructor
public class RawEmailSource {

  /**
   * The message's header block, as it stands at the top of the source: every header
   * line, folded lines kept, up to the blank line that ends it.
   */
  private String  headers;

  /** The RFC 822 source, from its first byte, up to the shown limit. */
  private String  source;

  /**
   * The message's size in bytes as the mail server reports it (RFC822.SIZE), or the
   * number of bytes read when the server reports none.
   */
  private long    size;

  /** Whether {@link #source} stops before the end of the message. */
  private boolean truncated;
}
