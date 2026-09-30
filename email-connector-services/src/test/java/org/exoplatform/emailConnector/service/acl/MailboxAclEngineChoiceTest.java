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
package org.exoplatform.emailConnector.service.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.storage.ConnectorEngineChoiceStorage;

/**
 * The mailbox sharing engine of a connector, chosen in the connector administration
 * screen (EXO-90793): the screen's choice applies unless a deployment property is set,
 * the connector's own first; with neither, {@code imap}. The screen is told which
 * engines are installed and which property decides.
 */
public class MailboxAclEngineChoiceTest {

  private static final long            CONNECTOR_ID = 42L;

  private final MailboxAclEngine       imap        = mock(MailboxAclEngine.class);

  private final MailboxAclEngine       contributed  = mock(MailboxAclEngine.class);

  private final NoopAclEngine         noop         = new NoopAclEngine();

  private final ConnectorEngineChoiceStorage choices = mock(ConnectorEngineChoiceStorage.class);

  private MailboxAclEngineRegistry     registry;

  /**
   * A registry over IMAP, an engine another add-on contributes, and the no-op one, and
   * over the screen's choices.
   */
  @BeforeEach
  public void setUp() {
    when(imap.getName()).thenReturn("imap");
    when(contributed.getName()).thenReturn("BlueMind");
    registry = new MailboxAclEngineRegistry();
    ReflectionTestUtils.setField(registry, "engines", List.of(imap, contributed, noop));
    ReflectionTestUtils.setField(registry, "choices", choices);
  }

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY);
    System.clearProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID);
  }

  /**
   * With no property set, the screen's choice decides, and nothing overrides it.
   */
  @Test
  public void theScreensChoiceAppliesWhenNoPropertyIsSet() {
    when(choices.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID)).thenReturn("bluemind");

    assertSame(contributed, registry.engineFor(connector()));
    assertEquals("bluemind", registry.engineName(connector()));
    assertNull(registry.overridingProperty(connector()));
  }

  /**
   * Nothing chosen and no property: the default is still {@code imap}.
   */
  @Test
  public void theDefaultIsStillImap() {
    assertSame(imap, registry.engineFor(connector()));
    assertEquals(ImapAclEngine.NAME, registry.engineName(connector()));
  }

  /**
   * A deployment-wide property wins over the screen's choice, and is named as what
   * decides; the connector's own property wins over both. The screen's choice is still
   * answered apart, as what applies once no property decides.
   */
  @Test
  public void aPropertyWinsOverTheScreenTheConnectorsOwnFirst() {
    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY, "imap");

    assertSame(imap, registry.engineFor(connector()));
    assertEquals(MailboxAclEngineRegistry.ENGINE_PROPERTY, registry.overridingProperty(connector()));

    when(choices.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID)).thenReturn("bluemind");
    assertEquals("bluemind", registry.chosenEngineName(connector()), "the screen's choice, whatever decides");

    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, "none");
    assertSame(noop, registry.engineFor(connector()));
    assertEquals(MailboxAclEngineRegistry.ENGINE_PROPERTY_PREFIX + CONNECTOR_ID, registry.overridingProperty(connector()));
  }

  /**
   * A property set blank decides nothing: the screen's choice applies.
   */
  @Test
  public void aBlankPropertyDecidesNothing() {
    System.setProperty(MailboxAclEngineRegistry.ENGINE_PROPERTY, " ");
    when(choices.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID)).thenReturn("imap");

    assertSame(imap, registry.engineFor(connector()));
    assertNull(registry.overridingProperty(connector()));
  }

  /**
   * A connector without an id, and a registry without the screen's storage, fall to
   * the default.
   */
  @Test
  public void noIdOrNoStorageFallsToTheDefault() {
    assertEquals(ImapAclEngine.NAME, registry.engineName(new EmailConnector()));
    assertEquals(ImapAclEngine.NAME, registry.engineName(null));
    assertNull(registry.overridingProperty(null));
    assertEquals(ImapAclEngine.NAME, registry.chosenEngineName(null));
    ReflectionTestUtils.setField(registry, "choices", null);
    assertEquals(ImapAclEngine.NAME, registry.chosenEngineName(connector()));
    assertEquals(ImapAclEngine.NAME, registry.engineName(connector()));
  }

  /**
   * The engines installed are offered by name, lower-case, each once.
   */
  @Test
  public void theInstalledEnginesAreOfferedByName() {
    MailboxAclEngine twin = mock(MailboxAclEngine.class);
    when(twin.getName()).thenReturn("imap ");
    MailboxAclEngine nameless = mock(MailboxAclEngine.class);
    ReflectionTestUtils.setField(registry, "engines", List.of(imap, contributed, twin, nameless, noop));

    assertEquals(List.of("imap", "bluemind", "none"), registry.engineNames());
  }

  /**
   * A chosen engine that is not installed falls to the no-op one, said once per
   * connector and name at WARN rather than on every request.
   */
  @Test
  public void anEngineThatIsNotInstalledIsReportedOnce() {
    when(choices.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID)).thenReturn("exchange");
    when(choices.getChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID + 1)).thenReturn("exchange");
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(MailboxAclEngineRegistry.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertSame(noop, registry.engineFor(connector()));
      assertSame(noop, registry.engineFor(connector()));
      EmailConnector other = connector();
      other.setId(CONNECTOR_ID + 1);
      assertSame(noop, registry.engineFor(other));

      assertEquals(2, appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count(), appender.list.toString());
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * @return a connector with an id
   */
  private static EmailConnector connector() {
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    return connector;
  }
}
