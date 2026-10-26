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
package org.exoplatform.emailConnector.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * EXO-90910 -- the one definition of the kinds of attachment a search can ask for: by
 * the stored MIME type, or by the file name's extension.
 */
class SearchAttachmentTypeTest {

  /**
   * The MIME type decides, its case and its parameters ignored, whatever the name.
   */
  @Test
  void theMimeTypeTellsTheKindWhateverTheName() {
    assertEquals(EnumSet.of(SearchAttachmentType.PDF), kindsOf("scan", "Application/PDF; name=scan"));
    assertEquals(EnumSet.of(SearchAttachmentType.DOCUMENT),
                 kindsOf("letter", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    assertEquals(EnumSet.of(SearchAttachmentType.SPREADSHEET), kindsOf(null, "application/vnd.oasis.opendocument.spreadsheet"));
    assertEquals(EnumSet.of(SearchAttachmentType.PRESENTATION), kindsOf("deck", "application/vnd.ms-powerpoint"));
    assertEquals(EnumSet.of(SearchAttachmentType.IMAGE), kindsOf("photo", "image/heic"), "any image type");
    assertEquals(EnumSet.of(SearchAttachmentType.ARCHIVE), kindsOf("bundle", "application/x-7z-compressed"));
    assertEquals(EnumSet.of(SearchAttachmentType.VIDEO), kindsOf("clip", "video/quicktime"), "any video type");
  }

  /**
   * A generic MIME type leaves the name's extension to decide, case ignored; a name with
   * no extension, or one ending with a dot, is of no kind.
   */
  @Test
  void theExtensionTellsTheKindWhenTheMimeTypeDoesNot() {
    assertEquals(EnumSet.of(SearchAttachmentType.PDF), kindsOf("Contract.PDF", "application/octet-stream"));
    assertEquals(EnumSet.of(SearchAttachmentType.DOCUMENT), kindsOf("notes.v2.txt", "text/plain"));
    assertEquals(EnumSet.of(SearchAttachmentType.SPREADSHEET), kindsOf("budget.csv", "text/plain"),
                 "a CSV sent as text/plain is a spreadsheet, never a document");
    assertEquals(EnumSet.of(SearchAttachmentType.PRESENTATION), kindsOf("deck.odp", null));
    assertEquals(EnumSet.of(SearchAttachmentType.IMAGE), kindsOf("logo.SVG", ""));
    assertEquals(EnumSet.of(SearchAttachmentType.ARCHIVE), kindsOf("backup.tar.gz", "application/octet-stream"));
    for (String video : new String[] { "demo.mp4", "demo.MOV", "demo.avi", "demo.mkv", "demo.webm" }) {
      assertEquals(EnumSet.of(SearchAttachmentType.VIDEO), kindsOf(video, "application/octet-stream"), video);
    }
    assertEquals(EnumSet.noneOf(SearchAttachmentType.class), kindsOf("song.mp3", "audio/mpeg"), "a sound is no video");
    assertEquals(EnumSet.noneOf(SearchAttachmentType.class), kindsOf("README", "application/octet-stream"));
    assertEquals(EnumSet.noneOf(SearchAttachmentType.class), kindsOf("pdf.", "text/plain"));
    assertEquals(EnumSet.noneOf(SearchAttachmentType.class), kindsOf("invite.ics", "text/calendar"));
    assertEquals(EnumSet.noneOf(SearchAttachmentType.class), kindsOf(null, null));
  }

  /**
   * A key names its kind whatever its case; anything else names none.
   */
  @Test
  void aKeyNamesItsKind() {
    assertEquals(SearchAttachmentType.SPREADSHEET, SearchAttachmentType.fromKey(" spreadsheet "));
    assertEquals(SearchAttachmentType.PDF, SearchAttachmentType.fromKey("PDF"));
    assertEquals(SearchAttachmentType.VIDEO, SearchAttachmentType.fromKey("video"));
    assertNull(SearchAttachmentType.fromKey("EXECUTABLE"));
    assertNull(SearchAttachmentType.fromKey(" "));
    assertNull(SearchAttachmentType.fromKey(null));
    assertTrue(SearchAttachmentType.IMAGE.matches("a.png", null));
    assertFalse(SearchAttachmentType.IMAGE.matches("a.pdf", "application/pdf"));
  }

  /**
   * The kinds an attachment is of.
   *
   * @param name its name
   * @param mimeType its stored MIME type
   * @return every kind it matches
   */
  private static Set<SearchAttachmentType> kindsOf(String name, String mimeType) {
    return Arrays.stream(SearchAttachmentType.values())
                 .filter(type -> type.matches(name, mimeType))
                 .collect(Collectors.toCollection(() -> EnumSet.noneOf(SearchAttachmentType.class)));
  }
}
