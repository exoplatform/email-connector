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

  /**
   * Patches an edit into a card AS THE SERVER HOLDS IT, and answers the whole
   * card back — the only safe way to write onto somebody's real address-book
   * entry, because {@link ParsedVCard} is lossy on purpose: reading the card
   * into the model and formatting it back would silently strip every property
   * the model has no slot for.
   * <p>
   * So the contract is a merge, not a rewrite. Only the properties the contact
   * form owns are touched — FN, N's given/family halves, EMAIL, TEL, ORG's
   * company, BDAY, the first ADR, the first NOTE, the first URL — and each
   * only when the edit actually differs from what the card currently reads as
   * through {@link #parse}: an untouched field keeps its property untouched,
   * parameters, group and all. Everything else — TITLE (the form has no such
   * field), PHOTO, UID, instant-messaging handles, {@code X-} extensions,
   * second addresses and beyond — passes through unchanged, and the card keeps
   * its own vCard version.
   *
   * @param rawVCard the card exactly as the server answered it
   * @param edited the fields as the user saved them — addresses normalized,
   *          the first address being the primary
   * @return the whole card with the edit folded in, or null when the text
   *         holds no card this parser can safely patch — which the caller must
   *         treat as "do not write", never as "write something else"
   */
  String merge(String rawVCard, ParsedVCard edited);
}
