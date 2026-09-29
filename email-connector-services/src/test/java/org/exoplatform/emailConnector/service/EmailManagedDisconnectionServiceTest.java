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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorStorage;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.MembershipEntry;

/**
 * EXO-89654. What an administrator's change does to the users managed mode attached,
 * and to the users of a connector moved to another provider. The verdict is the real
 * {@link ManagedConnectorService}'s: only the user's groups are stubbed.
 */
@ExtendWith(MockitoExtension.class)
class EmailManagedDisconnectionServiceTest {

  private static final String              ADMIN = "root";

  @Mock
  private EmailManagedModeService          emailManagedModeService;

  @Mock
  private UserEmailSettingService          userEmailSettingService;

  @Mock
  private EmailConnectorService            emailConnectorService;

  @Mock
  private UserACL                          userAcl;

  private EmailManagedDisconnectionService service;

  private MockedStatic<ExoContainerContext> containerContext;

  private final List<Runnable>             queued = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // reconcile() and disconnectAll() are @ContainerTransactional: the woven aspect reads the current
    // container, and with none bound it would boot the kernel.
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(mock(ExoContainer.class));
    service = new EmailManagedDisconnectionService();
    ReflectionTestUtils.setField(service,
                                 "managedConnectorService",
                                 new ManagedConnectorService(mock(ManagedConnectorStorage.class),
                                                             mock(ConnectorCredentialsService.class),
                                                             userAcl));
    ReflectionTestUtils.setField(service, "emailManagedModeService", emailManagedModeService);
    ReflectionTestUtils.setField(service, "userEmailSettingService", userEmailSettingService);
    ReflectionTestUtils.setField(service, "emailConnectorService", emailConnectorService);
    service.setExecutor(Runnable::run);
    lenient().when(emailConnectorService.canEdit(ADMIN)).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    containerContext.close();
  }

  private void attachedByManagedMode(String connectorId, String... users) {
    List<String> all = new ArrayList<>(List.of(users));
    when(userEmailSettingService.getUsersConnectedByManagedMode()).thenReturn(all);
    for (String user : users) {
      lenient().when(userEmailSettingService.getStoredEmailConnectorId(user)).thenReturn(connectorId);
    }
  }

  private void inForce(Long designation, String... excludedGroups) {
    when(emailManagedModeService.getManagedConnectorId()).thenReturn(designation);
    lenient().when(emailManagedModeService.getExcludedGroups()).thenReturn(List.of(excludedGroups));
  }

  private void groupsOf(String user, String... groups) {
    List<MembershipEntry> memberships = new ArrayList<>();
    for (String group : groups) {
      memberships.add(new MembershipEntry(group, "member"));
    }
    when(userAcl.getUserIdentity(user)).thenReturn(new Identity(user, memberships));
  }

  /** A becomes B: the users managed mode attached to A are disconnected. */
  @Test
  void anotherDesignatedConnectorDisconnectsTheUsersAttachedToTheFormerOne() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(5L);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /** Managed mode off: every user it attached is disconnected. */
  @Test
  void managedModeOffDisconnectsEveryUserItAttached() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(null);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /** A group excluded: only its members managed mode attached are disconnected. */
  @Test
  void aNewlyExcludedGroupDisconnectsOnlyItsMembers() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(3L, "/externals");
    groupsOf("alice", "/platform/users", "/externals");
    groupsOf("bob", "/platform/users");

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("bob");
  }

  /**
   * A user whose identity cannot be resolved while exclusions apply - the identity cache
   * answers null on a directory failure - is skipped, not taken for excluded: deleting
   * their connection on that answer could not be undone.
   */
  @Test
  void aUserWhoseIdentityCannotBeResolvedIsNotTakenForExcluded() throws Exception {
    attachedByManagedMode("3", "alice", "bob");
    inForce(3L, "/externals");
    groupsOf("alice", "/externals");
    when(userAcl.getUserIdentity("bob")).thenReturn(null);

    assertEquals(1, service.countUsersNoLongerManaged(3L, List.of("/externals"), ADMIN));
    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("bob");
  }

  /** A save that changes nothing disconnects nobody. */
  @Test
  void aSaveThatChangesNothingDisconnectsNobody() {
    attachedByManagedMode("3", "alice");
    inForce(3L);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * Only the users managed mode attached are considered: the users who chose a
   * connector themselves are not even listed, so they are never touched.
   */
  @Test
  void theUsersWhoChoseTheirConnectorAreNeverConsidered() {
    attachedByManagedMode("3");
    inForce(null);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).getUserEmailSettingsByEmailConnectorId(org.mockito.ArgumentMatchers.anyLong());
    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /** The failure of one disconnection does not abandon the others. */
  @Test
  void aFailedDisconnectionDoesNotStopTheOthers() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(null);
    doThrow(new IllegalStateException("settings unavailable")).when(userEmailSettingService).deleteUserEmailSetting("alice");

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /**
   * A user whose verdict cannot be computed - an unreadable setting, an identity the
   * platform cannot resolve - is skipped: the others are still disconnected.
   */
  @Test
  void aUserWhoseVerdictFailsIsSkippedAndTheOthersProcessed() {
    when(userEmailSettingService.getUsersConnectedByManagedMode()).thenReturn(List.of("alice", "bob"));
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenThrow(new IllegalStateException("unreadable document"));
    when(userEmailSettingService.getStoredEmailConnectorId("bob")).thenReturn("3");
    inForce(5L);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /**
   * A malformed stored document fails as Jackson's checked exception, thrown through
   * {@code @SneakyThrows}: that user is skipped too, the others still disconnected.
   */
  @Test
  void aUserWhoseDocumentIsMalformedIsSkippedAndTheOthersProcessed() {
    when(userEmailSettingService.getUsersConnectedByManagedMode()).thenReturn(List.of("alice", "bob"));
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenAnswer(invocation -> {
      throw new java.io.IOException("malformed document");
    });
    when(userEmailSettingService.getStoredEmailConnectorId("bob")).thenReturn("3");
    inForce(5L);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /** Counting the users of a connector that does not exist is refused as such, not answered zero. */
  @Test
  void countingTheUsersOfAnUnknownConnectorIsNotFound() {
    when(emailConnectorService.getEmailConnector(99L)).thenReturn(null);

    assertThrows(org.exoplatform.commons.exception.ObjectNotFoundException.class, () -> service.countUsersOf(99L, ADMIN));
  }

  /** The selection and the disconnections run on the executor, not in the administrator's request. */
  @Test
  void theDisconnectionsRunInTheBackground() {
    service.setExecutor(queued::add);
    attachedByManagedMode("3", "alice");
    inForce(null);

    service.disconnectUsersNoLongerManaged();

    // Nothing is even read before the task runs: the request returns at once.
    verify(userEmailSettingService, never()).getUsersConnectedByManagedMode();
    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
    assertEquals(1, queued.size());
    queued.get(0).run();
    verify(userEmailSettingService).deleteUserEmailSetting("alice");
  }

  /** The announced count is what the change then disconnects, and counting writes nothing. */
  @Test
  void theCountIsWhatTheChangeDisconnects() throws Exception {
    attachedByManagedMode("3", "alice", "bob");
    groupsOf("alice", "/externals");
    groupsOf("bob", "/platform/users");

    assertEquals(1, service.countUsersNoLongerManaged(3L, List.of("/externals"), ADMIN));
    assertEquals(2, service.countUsersNoLongerManaged(null, List.of(), ADMIN));
    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /** Counting is an administration act. */
  @Test
  void onlyAnAdministratorMayCount() {
    when(emailConnectorService.canEdit("mary")).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> service.countUsersNoLongerManaged(null, List.of(), "mary"));
    assertThrows(IllegalAccessException.class, () -> service.countUsersOf(3L, "mary"));
    verifyNoInteractions(userEmailSettingService);
  }

  /** A provider change disconnects every user of the connector, whoever connected them. */
  @Test
  void aProviderChangeDisconnectsEveryUserOfTheConnector() throws Exception {
    when(emailConnectorService.getEmailConnector(3L)).thenReturn(new org.exoplatform.emailConnector.model.EmailConnector());
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenReturn(List.of("alice", "chloe"));

    assertEquals(2, service.countUsersOf(3L, ADMIN));
    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("chloe");
  }
}
