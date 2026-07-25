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
package org.exoplatform.emailConnector.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class EmailThreadingUtilsTest {

  @Test
  void parseMessageIdsExtractsBracketedTokens() {
    List<String> ids = EmailThreadingUtils.parseMessageIds("<a@host> <b@host>\n\t<c@host>");
    assertEquals(List.of("<a@host>", "<b@host>", "<c@host>"), ids);
  }

  @Test
  void parseMessageIdsHandlesNullAndBlank() {
    assertTrue(EmailThreadingUtils.parseMessageIds(null).isEmpty());
    assertTrue(EmailThreadingUtils.parseMessageIds("   ").isEmpty());
    assertTrue(EmailThreadingUtils.parseMessageIds("no brackets here").isEmpty());
  }

  @Test
  void collectReferencedIdsMergesReferencesThenInReplyToWithoutDuplicates() {
    Set<String> ids = EmailThreadingUtils.collectReferencedIds("<b@host>", "<a@host> <b@host>");
    // References first (fuller chain), In-Reply-To's <b@host> is already present
    assertEquals(List.of("<a@host>", "<b@host>"), List.copyOf(ids));
  }

  @Test
  void buildReferencesHeaderAppendsParentIdToParentChain() {
    // RFC 5322 §3.6.4: the third message keeps the whole chain
    assertEquals("<a@host> <b@host>", EmailThreadingUtils.buildReferencesHeader("<a@host>", "<b@host>"));
  }

  @Test
  void buildReferencesHeaderFromNoParentChainIsJustTheParentId() {
    assertEquals("<a@host>", EmailThreadingUtils.buildReferencesHeader(null, "<a@host>"));
    assertEquals("<a@host>", EmailThreadingUtils.buildReferencesHeader("  ", "<a@host>"));
  }

  @Test
  void buildReferencesHeaderIsNullWhenNothingToReference() {
    assertNull(EmailThreadingUtils.buildReferencesHeader(null, null));
  }

  @Test
  void synthesizeMessageIdIsStableAndBracketed() {
    String id = EmailThreadingUtils.synthesizeMessageId(42L, "root");
    assertEquals("<42.root@email-connector.local>", id);
    assertEquals(id, EmailThreadingUtils.synthesizeMessageId(42L, "root"));
  }

  @Test
  void normalizeSubjectStripsChainedReplyForwardPrefixes() {
    assertEquals("Invoice", EmailThreadingUtils.normalizeSubject("Re: Fwd: Invoice"));
    assertEquals("Invoice", EmailThreadingUtils.normalizeSubject("RE: Invoice"));
    assertEquals("Invoice", EmailThreadingUtils.normalizeSubject("Re[2]: Invoice"));
    // localized prefixes (FR/DE/SV)
    assertEquals("Invoice", EmailThreadingUtils.normalizeSubject("TR: AW: SV: Invoice"));
    assertEquals("Invoice", EmailThreadingUtils.normalizeSubject("Invoice"));
    assertEquals("", EmailThreadingUtils.normalizeSubject(null));
  }

  @Test
  void extractThreadIndexRootReturnsSharedConversationGuid() {
    assertNull(EmailThreadingUtils.extractThreadIndexRoot(null));
    assertNull(EmailThreadingUtils.extractThreadIndexRoot("   "));
    assertNull(EmailThreadingUtils.extractThreadIndexRoot("!!not-base64!!"));
    // Well-formed base64 but shorter than the 22-byte header → not a Thread-Index.
    assertNull(EmailThreadingUtils.extractThreadIndexRoot(Base64.getEncoder().encodeToString(new byte[10])));

    // A 22-byte header carrying a known 16-byte conversation GUID at offset 6.
    byte[] base = new byte[22];
    for (int i = 6; i < 22; i++) {
      base[i] = (byte) (i * 7);
    }
    String parent = Base64.getEncoder().encodeToString(base);
    String root = EmailThreadingUtils.extractThreadIndexRoot(parent);
    assertNotNull(root);
    assertEquals(32, root.length()); // 16 bytes rendered as hex

    // A reply appends a 5-byte block but keeps the same first 22 bytes → same root.
    byte[] reply = new byte[27];
    System.arraycopy(base, 0, reply, 0, 22);
    reply[24] = 0x2a;
    assertEquals(root, EmailThreadingUtils.extractThreadIndexRoot(Base64.getEncoder().encodeToString(reply)));

    // A different conversation GUID → a different root.
    byte[] other = new byte[22];
    for (int i = 6; i < 22; i++) {
      other[i] = (byte) (i * 3 + 1);
    }
    assertTrue(!root.equals(EmailThreadingUtils.extractThreadIndexRoot(Base64.getEncoder().encodeToString(other))));
  }
}
