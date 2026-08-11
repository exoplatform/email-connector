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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
