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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.ConnectorForwarding;
import org.exoplatform.emailConnector.model.EmailConnector;

/**
 * The one resolver of whether users of a connector may forward, and where: the
 * deployment's kill switch first, then what the connector administration screen saved,
 * then the properties; applied at once, without a restart.
 */
public class ForwardingGuardTest {

  private static final long         CONNECTOR_ID = 7L;

  private static final String       MAILBOX      = "alice@example.org";

  private final Map<String, String> global       = new HashMap<>();

  private SettingService            settingService;

  private ForwardingGuard           guard;

  private EmailConnector            connector;

  /**
   * A guard over a setting store in a map.
   */
  @BeforeEach
  public void setUp() {
    settingService = mock(SettingService.class);
    when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenAnswer(invocation -> {
      String value = global.get(invocation.getArgument(2, String.class));
      return value == null ? null : SettingValue.create(value);
    });
    doAnswer(invocation -> {
      global.put(invocation.getArgument(2, String.class), invocation.getArgument(3, SettingValue.class).getValue().toString());
      return null;
    }).when(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
    guard = new ForwardingGuard();
    ReflectionTestUtils.setField(guard, "settingService", settingService);
    connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
  }

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY);
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    System.clearProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY);
    System.clearProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY + "." + CONNECTOR_ID);
  }

  /**
   * Never saved, the properties decide: off by default, the per-connector key over the
   * deployment-wide one.
   */
  @Test
  public void testThePropertiesApplyUntilTheScreenIsSaved() {
    assertFalse(guard.authoringEnabled(connector));
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY, "true");
    assertTrue(guard.authoringEnabled(connector));
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID, "false");
    assertFalse(guard.authoringEnabled(connector));
    System.setProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY + "." + CONNECTOR_ID, "partner.com");
    assertEquals(List.of("partner.com"), guard.allowedDomains(connector, MAILBOX));
    assertFalse(ForwardingGuard.resolve(settingService, connector).isSaved());
  }

  /**
   * The screen's saved value wins over the properties, both ways, and applies at once.
   */
  @Test
  public void testTheScreenBeatsTheProperties() {
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID, "true");
    System.setProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY + "." + CONNECTOR_ID, "partner.com");
    ForwardingGuard.save(settingService, CONNECTOR_ID, false, List.of("other.org"));
    assertFalse(guard.authoringEnabled(connector));
    assertEquals(List.of("other.org"), guard.allowedDomains(connector, MAILBOX));
    assertFalse(guard.isAllowedDomain("bob@partner.com", connector, MAILBOX));
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID, "false");
    ForwardingGuard.save(settingService, CONNECTOR_ID, true, List.of());
    assertTrue(guard.authoringEnabled(connector));
    // An empty list: each user's own mailbox domain, not the properties' list.
    assertEquals(List.of("example.org"), guard.allowedDomains(connector, MAILBOX));
    assertTrue(ForwardingGuard.resolve(settingService, connector).isSaved());
  }

  /**
   * The deployment's kill switch wins over the screen.
   */
  @Test
  public void testTheKillSwitchBeatsTheScreen() {
    ForwardingGuard.save(settingService, CONNECTOR_ID, true, List.of());
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY, "false");
    assertFalse(guard.authoringEnabled(connector));
    ConnectorForwarding shown = ForwardingGuard.resolve(settingService, connector);
    assertTrue(shown.isKillSwitch());
    // The screen still shows, and a save keeps, what the administrator set.
    assertTrue(shown.isAuthoringEnabled());
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY);
    assertTrue(guard.authoringEnabled(connector));
  }

  /**
   * Only plain domains are saved, lower-cased, a leading at-sign dropped.
   */
  @Test
  public void testOnlyPlainDomainsAreSaved() {
    for (String bad : List.of("", "partner", "a b.com", "*.partner.com", "partner..com", "-x.com", "bob@partner.com")) {
      assertEquals(ForwardingGuard.INVALID_DOMAIN,
                   assertThrows(IllegalArgumentException.class, () -> ForwardingGuard.save(settingService, CONNECTOR_ID, true, List.of(bad)))
                                                                                                                                       .getMessage(),
                   bad);
    }
    ForwardingGuard.save(settingService, CONNECTOR_ID, true, List.of(" @Partner.COM "));
    assertEquals(List.of("partner.com"), guard.allowedDomains(connector, MAILBOX));
  }
}
