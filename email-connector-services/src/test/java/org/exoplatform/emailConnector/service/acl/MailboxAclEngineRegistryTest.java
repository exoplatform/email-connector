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
package org.exoplatform.emailConnector.service.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.model.EmailConnector;

/**
 * Which engine a connector preset gets: the per-preset property wins over the global
 * one, {@code imap} is the default, and a name no bean carries falls back to the no-op
 * engine -- the registered one when there is, a fresh one otherwise.
 */
class MailboxAclEngineRegistryTest {

  private static final long CONNECTOR_ID = 42L;

  /**
   * Clears the selector properties every test may have set.
   */
  @AfterEach
  void clearProperties() {
    System.clearProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY);
    System.clearProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID);
  }

  /**
   * With no property set, every preset -- and a null one -- reads {@code imap}.
   */
  @Test
  void theDefaultEngineIsImap() {
    MailboxAclEngineRegistry registry = registry(List.of());
    assertEquals(ImapAclEngine.NAME, registry.engineName(connector()));
    assertEquals(ImapAclEngine.NAME, registry.engineName(null));
  }

  /**
   * The global property applies to every preset, trimmed and lower-cased; a preset's
   * own property overrides it.
   */
  @Test
  void thePresetPropertyOverridesTheGlobalOne() {
    MailboxAclEngineRegistry registry = registry(List.of());
    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY, " BlueMind ");
    assertEquals("bluemind", registry.engineName(connector()));

    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, "None");
    assertEquals(NoopAclEngine.NAME, registry.engineName(connector()));
    assertEquals("bluemind", registry.engineName(null));
  }

  /**
   * The configured name is matched against the engines' names, ignoring case.
   */
  @Test
  void theNamedEngineIsReturned() {
    MailboxAclEngine imap = engine("IMAP");
    MailboxAclEngine noop = engine(NoopAclEngine.NAME);
    MailboxAclEngineRegistry registry = registry(List.of(noop, imap));
    assertSame(imap, registry.engineFor(connector()));
  }

  /**
   * A name no engine carries falls back to the registered no-op engine.
   */
  @Test
  void anUnknownNameFallsBackToTheRegisteredNoopEngine() {
    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY, "exchange");
    MailboxAclEngine imap = engine(ImapAclEngine.NAME);
    MailboxAclEngine noop = engine(NoopAclEngine.NAME);
    MailboxAclEngineRegistry registry = registry(List.of(imap, noop));
    assertSame(noop, registry.engineFor(connector()));
  }

  /**
   * With neither the named engine nor a registered no-op one, a fresh no-op engine is
   * returned, so sharing is disabled rather than failing.
   */
  @Test
  void anUnknownNameWithoutANoopBeanGetsAFreshNoopEngine() {
    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY, "exchange");
    MailboxAclEngineRegistry registry = registry(List.of(engine(ImapAclEngine.NAME)));
    assertInstanceOf(NoopAclEngine.class, registry.engineFor(null));
  }

  /**
   * @param engines the engine beans the registry collects
   * @return a registry over them
   */
  private MailboxAclEngineRegistry registry(List<MailboxAclEngine> engines) {
    MailboxAclEngineRegistry registry = new MailboxAclEngineRegistry();
    ReflectionTestUtils.setField(registry, "engines", engines);
    return registry;
  }

  /**
   * @param name the engine's name
   * @return a mocked engine answering that name
   */
  private MailboxAclEngine engine(String name) {
    MailboxAclEngine engine = mock(MailboxAclEngine.class);
    when(engine.getName()).thenReturn(name);
    return engine;
  }

  /**
   * @return a connector preset with {@link #CONNECTOR_ID}
   */
  private EmailConnector connector() {
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    return connector;
  }
}
