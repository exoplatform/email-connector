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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.ConnectorEngines;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.storage.ConnectorEngineChoiceStorage;

/**
 * The connector administration screen's engines (EXO-90793): only an administrator
 * reads or sets them, a connector that does not exist is not found, the choice is made
 * among the engines installed, and the screen is told what overrides it and whether the
 * connector's credentials provider is missing.
 */
@ExtendWith(MockitoExtension.class)
public class EmailConnectorEngineServiceTest {

  private static final long            CONNECTOR_ID = 7L;

  private static final String          ADMIN        = "root";

  @Mock
  private EmailConnectorService        emailConnectorService;

  @Mock
  private ServerRuleEngineRegistry     serverRuleEngineRegistry;

  @Mock
  private MailboxAclEngineRegistry     mailboxAclEngineRegistry;

  @Mock
  private ConnectorEngineChoiceStorage connectorEngineChoiceStorage;

  @Mock
  private EmailCredentialsResolver     emailCredentialsResolver;

  @InjectMocks
  private EmailConnectorEngineService  service;

  private EmailConnector               connector;

  /**
   * An administrator, a connector on the BlueMind service account, and the engines
   * installed: none and Sieve for the rules, IMAP and none for sharing.
   */
  @BeforeEach
  public void setUp() {
    connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    connector.setAuthProviderName("bluemind-sudo");
    lenient().when(emailConnectorService.canEdit(ADMIN)).thenReturn(true);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(serverRuleEngineRegistry.engineNames()).thenReturn(List.of("none", "sieve"));
    lenient().when(mailboxAclEngineRegistry.engineNames()).thenReturn(List.of("imap", "none"));
    lenient().when(serverRuleEngineRegistry.engineName(connector)).thenReturn("none");
    lenient().when(mailboxAclEngineRegistry.engineName(connector)).thenReturn("imap");
    lenient().when(emailCredentialsResolver.isProviderRegistered("bluemind-sudo")).thenReturn(true);
  }

  /**
   * The screen reads what applies, what is installed, what overrides it and whether
   * the provider is missing.
   *
   * @throws Exception never
   */
  @Test
  public void theScreenReadsTheEnginesAndWhatOverridesThem() throws Exception {
    when(serverRuleEngineRegistry.overridingProperty(connector)).thenReturn("email.connector.rulesEngine.7");

    ConnectorEngines engines = service.getEngines(CONNECTOR_ID, ADMIN);

    assertEquals("none", engines.getRulesEngine());
    assertEquals("imap", engines.getAclEngine());
    assertEquals(List.of("none", "sieve"), engines.getRulesEngines());
    assertEquals(List.of("imap", "none"), engines.getAclEngines());
    assertEquals("email.connector.rulesEngine.7", engines.getRulesEngineProperty());
    assertNull(engines.getAclEngineProperty());
    assertEquals("bluemind-sudo", engines.getAuthProviderName());
    assertFalse(engines.isAuthProviderMissing());
  }

  /**
   * A provider that is not installed is flagged; a connector naming none, or a platform
   * without the credentials contract, is not.
   *
   * @throws Exception never
   */
  @Test
  public void aProviderThatIsNotInstalledIsFlagged() throws Exception {
    when(emailCredentialsResolver.isProviderRegistered("bluemind-sudo")).thenReturn(false);
    assertTrue(service.getEngines(CONNECTOR_ID, ADMIN).isAuthProviderMissing());

    connector.setAuthProviderName(" ");
    assertFalse(service.getEngines(CONNECTOR_ID, ADMIN).isAuthProviderMissing());

    connector.setAuthProviderName("bluemind-sudo");
    ReflectionTestUtils.setField(service, "emailCredentialsResolver", null);
    assertFalse(service.getEngines(CONNECTOR_ID, ADMIN).isAuthProviderMissing());
  }

  /**
   * Somebody who does not administer email connectors, and a blank user whatever the
   * administration check answers for one, are refused before anything is read; both
   * on the read and on the save.
   */
  @Test
  public void onlyAnAdministratorReadsOrSetsTheEngines() {
    lenient().when(emailConnectorService.canEdit("mary")).thenReturn(false);
    lenient().when(emailConnectorService.canEdit("")).thenReturn(true);
    lenient().when(emailConnectorService.canEdit(null)).thenReturn(true);

    for (String user : new String[] { "mary", "", null }) {
      assertThrows(IllegalAccessException.class, () -> service.getEngines(CONNECTOR_ID, user));
      assertThrows(IllegalAccessException.class, () -> service.saveEngines(CONNECTOR_ID, choice("sieve", null), user));
    }
    verify(emailConnectorService, never()).getEmailConnector(anyLong());
    verifyNoInteractions(connectorEngineChoiceStorage);
  }

  /**
   * A connector that does not exist, or no id at all, is not found.
   */
  @Test
  public void aConnectorThatDoesNotExistIsNotFound() {
    assertThrows(ObjectNotFoundException.class, () -> service.getEngines(99L, ADMIN));
    assertThrows(ObjectNotFoundException.class, () -> service.getEngines(null, ADMIN));
    assertThrows(ObjectNotFoundException.class, () -> service.saveEngines(99L, choice("sieve", null), ADMIN));
  }

  /**
   * An installed engine is kept, lower-case, and the answer is the engines after the
   * save; a null one keeps what is kept.
   *
   * @throws Exception never
   */
  @Test
  public void anInstalledEngineIsKeptAndANullOneKeepsTheKeptOne() throws Exception {
    ConnectorEngines engines = service.saveEngines(CONNECTOR_ID, choice(" Sieve ", null), ADMIN);

    verify(connectorEngineChoiceStorage).setChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, CONNECTOR_ID, "sieve");
    verify(connectorEngineChoiceStorage, never()).setChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID, "imap");
    assertEquals(List.of("none", "sieve"), engines.getRulesEngines());

    service.saveEngines(CONNECTOR_ID, choice(null, "none"), ADMIN);
    verify(connectorEngineChoiceStorage).setChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID, "none");
  }

  /**
   * An engine that is not installed is refused, and nothing is kept, not even the other
   * one; unless it is the one already kept, which a save stating it back keeps as it
   * is.
   *
   * @throws Exception never
   */
  @Test
  public void anEngineThatIsNotInstalledIsRefusedUnlessItIsTheOneKept() throws Exception {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.saveEngines(CONNECTOR_ID, choice("bluemind", "none"), ADMIN));
    assertEquals(EmailConnectorEngineService.UNKNOWN_ENGINE, refused.getMessage());
    verify(connectorEngineChoiceStorage, never()).setChoice(anyString(), anyLong(), anyString());

    when(connectorEngineChoiceStorage.getChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, CONNECTOR_ID)).thenReturn("bluemind");
    service.saveEngines(CONNECTOR_ID, choice("bluemind", "none"), ADMIN);
    verify(connectorEngineChoiceStorage, never()).setChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, CONNECTOR_ID, "bluemind");
    verify(connectorEngineChoiceStorage).setChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, CONNECTOR_ID, "none");
  }

  /**
   * A save without a body is refused.
   */
  @Test
  public void aSaveWithoutEnginesIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> service.saveEngines(CONNECTOR_ID, null, ADMIN));
    verify(connectorEngineChoiceStorage, never()).setChoice(any(), anyLong(), any());
  }

  /**
   * The engines a save states.
   *
   * @param rules the server rules engine, may be null
   * @param acl the mailbox sharing engine, may be null
   * @return the body
   */
  private static ConnectorEngines choice(String rules, String acl) {
    ConnectorEngines engines = new ConnectorEngines();
    engines.setRulesEngine(rules);
    engines.setAclEngine(acl);
    return engines;
  }
}
