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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The custom-folder key and the behaviour-class questions {@link MailFolder} answers.
 */
class MailFolderTest {

  /**
   * The key is the prefix and the id, and parses back to the id it was made from.
   */
  @Test
  void aCustomKeyRoundTripsItsRegistryId() {
    assertEquals("CUSTOM:42", MailFolder.customKey(42L));
    assertTrue(MailFolder.isCustom("CUSTOM:42"));
    assertEquals(42L, MailFolder.customId("CUSTOM:42"));
  }

  /**
   * A key that is not a custom key is refused with the message code the REST layer
   * answers 400 with -- never parsed as something else, never defaulted to the inbox.
   */
  @Test
  void anythingButAWellFormedCustomKeyIsRefused() {
    assertFalse(MailFolder.isCustom(null));
    assertFalse(MailFolder.isCustom("INBOX"));
    assertFalse(MailFolder.isCustom("CUSTOM:"));
    assertFalse(MailFolder.isCustom("custom:1"));
    for (String key : new String[] { "INBOX", "CUSTOM:", "CUSTOM:x", "CUSTOM:1.5", null }) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> MailFolder.customId(key));
      assertEquals("emailConnector.folder.unknown", refusal.getMessage());
    }
  }

  /**
   * The browsable set: the six built-ins the interface lists, plus any custom key;
   * never ALL_MAIL, never an unknown word.
   */
  @Test
  void browsableIsTheSixListedBuiltInsAndEveryCustomKey() {
    for (String key : MailFolder.BROWSABLE_BUILT_INS) {
      assertTrue(MailFolder.isBrowsable(key), key);
    }
    assertTrue(MailFolder.isBrowsable("CUSTOM:7"));
    assertFalse(MailFolder.isBrowsable(MailFolder.ALL_MAIL));
    assertFalse(MailFolder.isBrowsable("OUTBOX"));
    assertFalse(MailFolder.isBrowsable(null));
  }

  /**
   * Resurfaced is the complement of the hidden set, and a custom folder is on the
   * resurfaced side -- the Archive class, which is what makes filing not deleting.
   */
  @Test
  void customFoldersAreResurfacedLikeTheArchive() {
    assertTrue(MailFolder.isResurfaced("CUSTOM:7"));
    assertTrue(MailFolder.isResurfaced(MailFolder.ARCHIVE));
    assertFalse(MailFolder.isResurfaced(MailFolder.TRASH));
    assertFalse(MailFolder.isResurfaced(MailFolder.JUNK));
    assertTrue(MailFolder.isBuiltIn(MailFolder.ALL_MAIL));
    assertFalse(MailFolder.isBuiltIn("CUSTOM:7"));
  }
}
