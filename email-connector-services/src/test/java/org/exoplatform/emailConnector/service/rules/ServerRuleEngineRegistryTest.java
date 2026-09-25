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
package org.exoplatform.emailConnector.service.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.bluemind.BlueMindMailboxTransport;
import org.exoplatform.emailConnector.service.rules.bluemind.BlueMindRuleEngine;

/**
 * The engine a preset uses is the property's, per preset first, {@code none} by default;
 * the no-op engine opens nothing and refuses every write; the rules verbs answer
 * "unsupported" until an engine implements them.
 */
public class ServerRuleEngineRegistryTest {

  private static final long        CONNECTOR_ID = 42L;

  private final ServerRuleEngine   sieve        = mock(ServerRuleEngine.class);

  private final NoopRuleEngine     noop         = new NoopRuleEngine();

  private final BlueMindRuleEngine bluemind     = new BlueMindRuleEngine(mock(BlueMindMailboxTransport.class),
                                                                         mock(EmailCredentialsResolver.class));

  private ServerRuleEngineRegistry registry;

  /**
   * A registry with a named engine and the no-op one.
   */
  @BeforeEach
  public void setUp() {
    when(sieve.getName()).thenReturn("sieve");
    registry = new ServerRuleEngineRegistry();
    ReflectionTestUtils.setField(registry, "engines", List.of(sieve, bluemind, noop));
  }

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY);
    System.clearProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID);
  }

  /**
   * Nothing configured: no engine manages the preset's server.
   */
  @Test
  public void testTheDefaultIsNone() {
    assertSame(noop, registry.engineFor(preset()));
  }

  /**
   * The global property selects an engine for every preset.
   */
  @Test
  public void testTheGlobalPropertySelects() {
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY, "SIEVE ");
    assertSame(sieve, registry.engineFor(preset()));
  }

  /**
   * The preset's own property wins over the global one, both ways.
   */
  @Test
  public void testThePresetPropertyWins() {
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY, "none");
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, "sieve");
    assertSame(sieve, registry.engineFor(preset()));
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY, "sieve");
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, "none");
    assertSame(noop, registry.engineFor(preset()));
  }

  /**
   * {@code bluemind} selects the BlueMind engine, per preset, whatever the global one.
   */
  @Test
  public void testBlueMindIsSelectedByName() {
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY, "sieve");
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, "bluemind");
    assertSame(bluemind, registry.engineFor(preset()));
  }

  /**
   * A name no engine has falls back to the no-op engine, never to another engine.
   */
  @Test
  public void testAnUnknownNameFallsBackToNone() {
    System.setProperty(ServerRuleEngineRegistry.ENGINE_PROPERTY, "exchange");
    assertSame(noop, registry.engineFor(preset()));
  }

  /**
   * The no-op engine answers unsupported and reads nothing without opening the session,
   * and refuses a write.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheNoopEngineOpensNothingAndRefusesWrites() throws Exception {
    MailboxAclSession session = mock(MailboxAclSession.class);
    ServerRuleCapabilities capabilities = noop.probe(session);
    assertFalse(capabilities.supported());
    assertFalse(capabilities.isSupported(ServerRuleCapabilities.VACATION));
    assertEquals(NoopRuleEngine.REASON, capabilities.reasonCode());
    assertEquals(VacationState.NONE, noop.readVacation(session).state());
    ServerRuleUnsupportedException e = assertThrows(ServerRuleUnsupportedException.class,
                                                    () -> noop.writeVacation(session, new VacationSetting(), 7, null));
    assertEquals(ServerRuleUnsupportedException.VACATION_UNSUPPORTED, e.getMessage());
    verifyNoInteractions(session);
  }

  /**
   * The rules verbs are declared on every engine and answer "unsupported" until an engine
   * implements them.
   */
  @Test
  public void testTheRulesVerbsAreUnsupportedByDefault() {
    MailboxAclSession session = mock(MailboxAclSession.class);
    assertEquals(ServerRuleUnsupportedException.RULES_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> noop.listRules(session)).getMessage());
    assertEquals(ServerRuleUnsupportedException.RULES_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class,
                              () -> noop.saveRule(session, new ServerRule("r", "n", true, true, List.of(), List.of(), false), null))
                                  .getMessage());
    assertEquals(ServerRuleUnsupportedException.RULES_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> noop.deleteRule(session, "r", null)).getMessage());
    assertEquals(ServerRuleUnsupportedException.RULES_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> noop.publishRules(session, null)).getMessage());
    assertEquals(ServerRuleUnsupportedException.RULES_UNSUPPORTED,
                 assertThrows(ServerRuleUnsupportedException.class, () -> noop.reconcile(session, List.of(), null)).getMessage());
  }

  /**
   * A preset.
   *
   * @return the preset
   */
  private static EmailConnector preset() {
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    return connector;
  }
}
