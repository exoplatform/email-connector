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
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;

/**
 * Capabilities derived from the lines a server re-issues after STARTTLS.
 */
public class SieveCapabilityDerivationTest {

  /**
   * Stalwart v0.11.8's line supports the reply, its window and every server rule
   * element Sieve can express; attachments, HTML and forwarding writes are not offered.
   */
  @Test
  public void testStalwartLine() {
    ServerRuleCapabilities capabilities = SieveCapabilityDerivation.derive(caps("PLAIN OAUTHBEARER",
                                                                                FakeManageSieveServer.STALWART_SIEVE));
    assertTrue(capabilities.supported());
    assertEquals(VocabularySource.DYNAMIC, capabilities.vocabularySource());
    assertFalse(capabilities.readsForeignRules());
    for (String element : List.of(ServerRuleCapabilities.VACATION,
                                  ServerRuleCapabilities.VACATION_DATE_WINDOW,
                                  ServerRuleCapabilities.FORWARDING_READ,
                                  ServerRuleCapabilities.BODY,
                                  ServerRuleCapabilities.MOVE_TO_FOLDER,
                                  ServerRuleCapabilities.TAG,
                                  ServerRuleCapabilities.FROM,
                                  ServerRuleCapabilities.IS_LIST)) {
      assertTrue(capabilities.isSupported(element), element);
    }
    for (String element : List.of(ServerRuleCapabilities.VACATION_HTML,
                                  ServerRuleCapabilities.FORWARDING_WRITE,
                                  ServerRuleCapabilities.READS_FOREIGN_VACATION,
                                  ServerRuleCapabilities.ATTACHMENT_NAME)) {
      assertFalse(capabilities.isSupported(element), element);
    }
    assertEquals(ServerRuleCapabilities.ELEMENTS.size(), capabilities.elements().size());
  }

  /**
   * Each element follows its own extension, and says which one is missing.
   */
  @Test
  public void testElementsFollowTheirExtension() {
    ServerRuleCapabilities capabilities = SieveCapabilityDerivation.derive(caps("PLAIN", "vacation date fileinto"));
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.VACATION));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION_DATE_WINDOW));
    assertEquals("emailConnector.rules.unsupported.sieveExtension.relational",
                 capabilities.elements().get(ServerRuleCapabilities.VACATION_DATE_WINDOW).reasonKey());
    assertTrue(capabilities.isSupported(ServerRuleCapabilities.MOVE_TO_FOLDER));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.STAR));
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.BODY));
    ServerRuleCapabilities noVacation = SieveCapabilityDerivation.derive(caps("PLAIN", "date relational"));
    assertEquals("emailConnector.rules.unsupported.sieveExtension.vacation",
                 noVacation.elements().get(ServerRuleCapabilities.VACATION_DATE_WINDOW).reasonKey());
  }

  /**
   * Without PLAIN after TLS nothing is supported.
   */
  @Test
  public void testNoPlainNothingSupported() {
    ServerRuleCapabilities capabilities = SieveCapabilityDerivation.derive(caps("OAUTHBEARER",
                                                                                FakeManageSieveServer.STALWART_SIEVE));
    assertFalse(capabilities.supported());
    assertEquals("emailConnector.rules.unsupported.saslPlain", capabilities.reasonCode());
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION));
  }

  /**
   * The shared record: an engine that can do nothing answers every element unsupported
   * for one reason, an element is supported only when the engine is, and the
   * account-level conflict flag is set by the probe without touching the rest.
   */
  @Test
  public void testSharedRecordHelpers() {
    ServerRuleCapabilities none = ServerRuleCapabilities.unsupported("emailConnector.rules.unsupported.noEngine",
                                                                     VocabularySource.NONE);
    assertEquals(ServerRuleCapabilities.ELEMENTS, List.copyOf(none.elements().keySet()));
    assertTrue(none.elements().values().stream().noneMatch(ServerRuleCapabilities.ElementSupport::supported));
    ServerRuleCapabilities stalwart = SieveCapabilityDerivation.derive(caps("PLAIN", FakeManageSieveServer.STALWART_SIEVE));
    ServerRuleCapabilities conflicting = stalwart.withPublishConflict(true);
    assertTrue(conflicting.publishConflict());
    assertEquals(stalwart.elements(), conflicting.elements());
    assertFalse(new ServerRuleCapabilities(false, "x", false, false, VocabularySource.DYNAMIC, stalwart.elements())
                                                                                                                  .isSupported(ServerRuleCapabilities.VACATION));
    assertFalse(stalwart.isSupported("unknownElement"));
  }

  /**
   * The capability lines are parsed case-insensitively into mechanisms and extensions.
   */
  @Test
  public void testCapabilityLinesParse() {
    ManageSieveCapabilities capabilities = ManageSieveCapabilities.fromLines(List.of(List.of("IMPLEMENTATION", "Stalwart"),
                                                                                     List.of("sasl", "plain OAuthBearer"),
                                                                                     List.of("SIEVE", "Vacation  Date"),
                                                                                     List.of("STARTTLS"),
                                                                                     List.of("VERSION", "1.0"),
                                                                                     List.of("MAXREDIRECTS", "4")));
    assertEquals("Stalwart", capabilities.implementation());
    assertEquals(Set.of("PLAIN", "OAUTHBEARER"), capabilities.saslMechanisms());
    assertEquals(Set.of("vacation", "date"), capabilities.sieveExtensions());
    assertTrue(capabilities.starttls());
    assertTrue(capabilities.supportsCheckScript());
  }

  /**
   * Builds post-TLS capabilities.
   *
   * @param sasl the SASL line
   * @param sieve the SIEVE line
   * @return the capabilities
   */
  private static ManageSieveCapabilities caps(String sasl, String sieve) {
    return ManageSieveCapabilities.fromLines(List.of(List.of("SASL", sasl), List.of("SIEVE", sieve), List.of("VERSION", "1.0")));
  }
}
