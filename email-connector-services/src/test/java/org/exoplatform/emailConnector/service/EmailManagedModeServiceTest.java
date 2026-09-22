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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailManagedMode;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;

/**
 * The decision lives in commons-exo; what this class owes it is the mail
 * knowledge it lacks, and what these tests pin is exactly that seam.
 * <p>
 * Pinned: the kind every call carries is {@code email}; the per-viewer verdict
 * is asked of commons-exo per user, never derived from the global designation;
 * a connector that is unknown or deactivated is refused here before commons-exo
 * is asked anything; the provider handed to commons-exo is the one the
 * connector is configured with; exclusions are written before the designation;
 * off clears both; and the guard refuses the writes that would strand the mode.
 */
@ExtendWith(MockitoExtension.class)
public class EmailManagedModeServiceTest {

  private static final String     KIND = "email";

  private static final String     USER = "mary";

  private static final String     ADMIN = "root";

  @Mock
  private ManagedConnectorService managedConnectorService;

  @Mock
  private EmailConnectorStorage   emailConnectorStorage;

  @InjectMocks
  private EmailManagedModeService emailManagedModeService;

  @BeforeEach
  public void nothingIsDesignatedByDefault() {
    lenient().when(managedConnectorService.designationOf(KIND)).thenReturn(null);
    lenient().when(managedConnectorService.exclusionsOf(KIND)).thenReturn(List.of());
    lenient().when(managedConnectorService.designatedConnectorFor(eq(KIND), anyString())).thenReturn(null);
  }

  private EmailConnector connector(long id, boolean active) {
    EmailConnector connector = new EmailConnector();
    connector.setId(id);
    connector.setName("BlueMind");
    connector.setActive(active);
    connector.setAuthProviderName("bluemind-sudo");
    return connector;
  }

  private void designated(long connectorId) {
    lenient().when(managedConnectorService.designationOf(KIND)).thenReturn(connectorId);
    lenient().when(managedConnectorService.designatedConnectorFor(eq(KIND), anyString())).thenReturn(connectorId);
  }

  @Test
  public void noDesignationIsManagedModeOff() {
    assertNull(emailManagedModeService.getManagedConnectorId());
    assertFalse(emailManagedModeService.isManagedFor(USER));

    EmailManagedMode mode = emailManagedModeService.getManagedMode(USER);

    assertNull(mode.connectorId());
    assertNull(mode.connectorName());
    assertEquals(List.of(), mode.excludedGroups());
    assertFalse(mode.managedForMe());
  }

  @Test
  public void aDesignationIsManagedModeOnAndNamesTheConnector() {
    designated(7);
    when(managedConnectorService.exclusionsOf(KIND)).thenReturn(List.of("/externals"));
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, true));

    EmailManagedMode mode = emailManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.connectorId());
    assertEquals("BlueMind", mode.connectorName());
    assertEquals(List.of("/externals"), mode.excludedGroups());
    assertTrue(mode.managedForMe());
  }

  /**
   * The verdict is per viewer, and it is commons-exo's: a designation exists and
   * this user is excluded from it — the instance's choice is shown, and it does
   * not apply to them.
   */
  @Test
  public void anExcludedUserSeesTheChoiceAndIsNotGovernedByIt() {
    designated(7);
    when(managedConnectorService.designatedConnectorFor(KIND, USER)).thenReturn(null);
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, true));

    assertFalse(emailManagedModeService.isManagedFor(USER));

    EmailManagedMode mode = emailManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.connectorId());
    assertFalse(mode.managedForMe());
  }

  /** Managed mode is deliberately ON here: the refusal comes from having no user. */
  @Test
  public void anAnonymousCallerIsNeverManaged() {
    designated(7);

    assertFalse(emailManagedModeService.isManagedFor(""));
    assertFalse(emailManagedModeService.isManagedFor(null));
    verify(managedConnectorService, never()).designatedConnectorFor(anyString(), any());
  }

  /** Saving hands commons-exo everything in one call: provider, exclusions and caller, under the email kind. */
  @Test
  public void savingDesignatesTheConnectorWithItsProviderExclusionsAndCaller() throws Exception {
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, true));

    emailManagedModeService.saveManagedConnector(7, List.of("/externals"), ADMIN);

    verify(managedConnectorService).designate(KIND, 7, "bluemind-sudo", List.of("/externals"), ADMIN);
  }

  /** A caller commons-exo refuses is refused here, untouched. */
  @Test
  public void aNonAdministratorIsRefusedByCommons() throws Exception {
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, true));
    doThrow(new IllegalAccessException("managedConnector.administrator.required")).when(managedConnectorService)
                                                                                 .designate(eq(KIND), anyLong(), any(), any(), eq(USER));
    doThrow(new IllegalAccessException("managedConnector.administrator.required")).when(managedConnectorService)
                                                                                 .clearDesignation(KIND, USER);

    assertThrows(IllegalAccessException.class, () -> emailManagedModeService.saveManagedConnector(7, List.of(), USER));
    assertThrows(IllegalAccessException.class, () -> emailManagedModeService.clearManagedConnector(USER));
  }

  @Test
  public void aDeactivatedConnectorIsRefusedBeforeAnythingIsWritten() throws Exception {
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, false));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailManagedModeService.saveManagedConnector(7, List.of(), ADMIN));

    assertEquals("emailConnector.managed.connectorNotEligible", refusal.getMessage());
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  @Test
  public void anUnknownConnectorIsRefusedBeforeAnythingIsWritten() throws Exception {
    when(emailConnectorStorage.getEmailConnector(9)).thenReturn(null);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailManagedModeService.saveManagedConnector(9, List.of(), ADMIN));

    assertEquals("emailConnector.managed.connectorNotEligible", refusal.getMessage());
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  /** commons-exo's refusal comes through untouched: the screen renders that code. */
  @Test
  public void aProviderThatAsksTheUserIsRefusedWithTheCommonsCode() throws Exception {
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(connector(7, true));
    doThrow(new IllegalArgumentException("managedConnector.provider.asksTheUser")).when(managedConnectorService)
                                                                                .designate(KIND, 7, "bluemind-sudo", List.of(), ADMIN);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailManagedModeService.saveManagedConnector(7, List.of(), ADMIN));

    assertEquals("managedConnector.provider.asksTheUser", refusal.getMessage());
  }

  /** Off is commons-exo's clear, with the caller: designation and exclusions go together there. */
  @Test
  public void switchingOffClearsThroughCommons() throws Exception {
    emailManagedModeService.clearManagedConnector(ADMIN);

    verify(managedConnectorService).clearDesignation(KIND, ADMIN);
    verify(managedConnectorService, never()).designate(anyString(), anyLong(), any(), any(), anyString());
  }

  /**
   * The managed connector may not move to a provider that asks the user: designating it
   * refused exactly that. Any other connector changes provider freely, and commons-exo
   * is not even asked.
   */
  @Test
  public void theManagedConnectorMayNotMoveToAProviderThatAsksTheUser() {
    designated(700);
    doThrow(new IllegalArgumentException("managedConnector.provider.asksTheUser")).when(managedConnectorService)
                                                                                .requireEligible("personal");

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailManagedModeService.checkProviderChangeAllowed(700, "personal"));

    assertEquals("emailConnector.managed.providerNotEligible", refusal.getMessage());
    assertDoesNotThrow(() -> emailManagedModeService.checkProviderChangeAllowed(700, "bluemind-sudo"));
    assertDoesNotThrow(() -> emailManagedModeService.checkProviderChangeAllowed(800, "anything"));
    verify(managedConnectorService, never()).requireEligible("anything");
  }

  @Test
  public void theManagedConnectorRefusesWritesAndOtherConnectorsDoNot() {
    // Ids above the Long cache (-128..127): a boxed == would still pass at 7.
    designated(700);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailManagedModeService.checkConnectorNotManaged(700));

    assertEquals("emailConnector.managed.connectorInUse", refusal.getMessage());
    assertDoesNotThrow(() -> emailManagedModeService.checkConnectorNotManaged(800));
  }

  @Test
  public void nothingIsProtectedWhenManagedModeIsOff() {
    assertDoesNotThrow(() -> emailManagedModeService.checkConnectorNotManaged(700));
  }

  @Test
  public void aVanishedManagedConnectorStillAnswers() {
    designated(7);
    when(emailConnectorStorage.getEmailConnector(7)).thenReturn(null);

    EmailManagedMode mode = emailManagedModeService.getManagedMode(USER);

    assertEquals(7L, mode.connectorId());
    assertNull(mode.connectorName());
    assertTrue(mode.managedForMe());
  }
}
