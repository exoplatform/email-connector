/**
 * Copyright (C) 2025 eXo Platform SAS
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

package org.exoplatform.emailConnector.carddav;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads vCard text into the handful of fields a contact row keeps, and writes
 * it back out.
 * <p>
 * An interface because vCard is the messy part of CardDAV — three versions in
 * the wild, several encodings, folding rules — and the sync should never care
 * which library is doing that work, nor be rewritten if the library changes.
 * The same reasoning covers the file import and export: everything that speaks
 * vCard in this add-on speaks it through here.
 */
public interface VCardParser {

  /**
   * Reads one vCard.
   *
   * @param vcardText the raw vCard
   * @return the parsed fields, or null when the text holds no usable vCard —
   *         which the sync treats as an entry to skip, not as a failure
   */
  ParsedVCard parse(String vcardText);

  /**
   * Reads every vCard in a stream, delivering them one at a time.
   * <p>
   * Streaming rather than parse-then-loop because the input is an uploaded
   * export file: a Gmail export holds thousands of cards, and a hostile one
   * holds whatever fits in the size cap — so no more than one card's worth of
   * it may ever be in memory at once. Only stream trouble is thrown; a card
   * that cannot be read is delivered to the sink as unreadable and the next
   * one is tried.
   *
   * @param vcards the raw text, closed by the caller
   * @param sink where each card lands, and which says when to stop
   * @throws IOException when the stream itself fails
   */
  void parseAll(Reader vcards, VCardSink sink) throws IOException;

  /**
   * Writes one card as vCard 3.0 text — the one dialect Gmail, Outlook and
   * iCloud all accept on import, which is the whole job of an export.
   * <p>
   * FN and N are both always written, even when a half is empty: 3.0 requires
   * them, and importers that meet a card missing either invent a name from
   * whatever is left.
   *
   * @param card the fields to write
   * @return the vCard text, ready to append to a .vcf file
   */
  String format(ParsedVCard card);
}
