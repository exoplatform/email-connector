/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.filters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * The server's spam marks (EXO-90668): the junk keywords, the user's "not junk" taking
 * them back, and the spam filters' verdict headers read from their first word.
 */
class SpamSignalsTest {

  /**
   * The junk keywords, any case, say spam; none says nothing.
   */
  @Test
  void theJunkKeywordsSaySpam() {
    assertTrue(SpamSignals.isFlagged(Set.of("$Junk"), null));
    assertTrue(SpamSignals.isFlagged(Set.of("JUNK", "$Forwarded"), null));
    assertFalse(SpamSignals.isFlagged(Set.of("$Forwarded"), null));
    assertFalse(SpamSignals.isFlagged(null, null));
  }

  /**
   * The user's "not junk" wins over a junk keyword and over a verdict header.
   */
  @Test
  void theUsersNotJunkWins() {
    assertFalse(SpamSignals.isFlagged(Set.of("$Junk", "$NotJunk"), null));
    assertFalse(SpamSignals.isFlagged(Set.of("NotJunk"), headers(Map.of("X-Spam-Flag", List.of("YES")))));
  }

  /**
   * The verdict headers say spam on a yes, and only on a yes: SpamAssassin's flag,
   * Rspamd's header, the status with its score after the verdict.
   */
  @Test
  void theVerdictHeadersSaySpamOnAYes() {
    assertTrue(SpamSignals.isFlagged(Set.of(), headers(Map.of("X-Spam-Flag", List.of("YES")))));
    assertTrue(SpamSignals.isFlagged(Set.of(), headers(Map.of("X-Spam", List.of("Yes")))));
    assertTrue(SpamSignals.isFlagged(Set.of(), headers(Map.of("X-Spam-Status", List.of("Yes, score=7.1 required=5.0")))));
    assertFalse(SpamSignals.isFlagged(Set.of(), headers(Map.of("X-Spam-Status", List.of("No, score=0.3 required=5.0")))));
    assertFalse(SpamSignals.isFlagged(Set.of(), headers(Map.of("X-Spam-Flag", List.of("NO")))));
    assertFalse(SpamSignals.isFlagged(Set.of(), headers(Map.of("Subject", List.of("Yes")))), "another header");
    assertFalse(SpamSignals.isFlagged(Set.of(), name -> null), "an unreadable header");
  }

  /**
   * A reader of the given headers.
   *
   * @param values the headers' values, by name
   * @return the reader
   */
  private static Function<String, List<String>> headers(Map<String, List<String>> values) {
    return name -> values.getOrDefault(name, List.of());
  }
}
