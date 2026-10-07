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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.ComponentRequestLifecycle;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorStorage;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.MembershipEntry;

/**
 * What an administrator's change does to the users managed mode attached,
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

  private ExoContainer                     container;

  private final List<Runnable>             queued = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // inItsOwnLifecycle is @ContainerTransactional: the woven aspect reads the current
    // container, and with none bound it would boot the kernel.
    container = mock(ExoContainer.class);
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(container);
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
      lenient().when(userEmailSettingService.isConnectedByManagedMode(user)).thenReturn(true);
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

  /** A checked exception thrown sneakily by one disconnection does not abandon the others either. */
  @Test
  void aCheckedFailureOfOneDisconnectionDoesNotStopTheOthers() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(null);
    doAnswer(invocation -> {
      throw new IOException("store unreachable");
    }).when(userEmailSettingService).deleteUserEmailSetting("alice");

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
    when(userEmailSettingService.isConnectedByManagedMode("bob")).thenReturn(true);
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
      throw new IOException("malformed document");
    });
    when(userEmailSettingService.getStoredEmailConnectorId("bob")).thenReturn("3");
    when(userEmailSettingService.isConnectedByManagedMode("bob")).thenReturn(true);
    inForce(5L);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /** Counting the users of a connector that does not exist is refused as such, not answered zero. */
  @Test
  void countingTheUsersOfAnUnknownConnectorIsNotFound() {
    when(emailConnectorService.getEmailConnector(99L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.countUsersOf(99L, ADMIN));
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
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenReturn("3");
    when(userEmailSettingService.getStoredEmailConnectorId("chloe")).thenReturn("3");

    assertEquals(2, service.countUsersOf(3L, ADMIN));
    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("chloe");
  }

  /**
   * Managed mode turned off: alice was selected, then connected her own mailbox before
   * her turn, which cleared the mark. Her connection is hers, and stays. Killed by the
   * mutant that drops the mark from isStillNoLongerManaged.
   */
  @Test
  void aUserWhoConnectedThemselvesDuringTheRunIsLeftConnected() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(null);
    when(userEmailSettingService.isConnectedByManagedMode("alice")).thenReturn(false);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting("alice");
    verify(userEmailSettingService).deleteUserEmailSetting("bob");
  }

  /**
   * The designation moved to 7: alice, on 5, was selected, then logged in before her
   * turn and was attached to 7. That connection is the one managed mode now wants, and
   * stays. Killed by the mutant that drops the verdict from isStillNoLongerManaged.
   */
  @Test
  void aUserAttachedToTheDesignatedConnectorDuringTheRunIsLeftConnected() {
    attachedByManagedMode("5", "alice");
    inForce(7L);
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenReturn("5", "7");

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * Managed mode was off when alice was selected, and designates her own connector again
   * before her turn: the verdict is computed against the designation read again, and she
   * stays. Killed by the mutant that keeps the selection's designation.
   */
  @Test
  void theDesignationIsReadAgainWhenAUsersTurnComes() {
    attachedByManagedMode("3", "alice");
    when(emailManagedModeService.getManagedConnectorId()).thenReturn(null, 3L);
    lenient().when(emailManagedModeService.getExcludedGroups()).thenReturn(List.of());

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * A provider change on 3: chloe moved to connector 4 before her turn, and that
   * connection is not one the change touches. Killed by the mutant that drops isStillOn.
   */
  @Test
  void aUserWhoMovedToAnotherConnectorDuringAProviderChangeIsLeftConnected() {
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenReturn(List.of("alice", "chloe"));
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenReturn("3");
    when(userEmailSettingService.getStoredEmailConnectorId("chloe")).thenReturn("4");

    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("chloe");
  }

  /**
   * /externals was excluded when alice, a member, was selected, and is no longer excluded
   * when her turn comes: the verdict is computed against the exclusions read again, and
   * she stays on 3, the connector managed mode designates. Killed by the mutant that keeps
   * the selection's exclusions.
   */
  @Test
  void theExclusionsAreReadAgainWhenAUsersTurnComes() {
    attachedByManagedMode("3", "alice");
    when(emailManagedModeService.getManagedConnectorId()).thenReturn(3L);
    when(emailManagedModeService.getExcludedGroups()).thenReturn(List.of("/externals"), List.of());
    groupsOf("alice", "/externals");

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * The designation moved from alice's 3 to 5 with no exclusions, so her selection needed
   * no identity; /externals is excluded before her turn, and her identity then cannot be
   * resolved. The re-check fails and she is left connected, never taken for excluded.
   * Killed by the mutant whose isStillNoLongerManaged catch answers true.
   */
  @Test
  void aReCheckThatCannotResolveTheIdentityLeavesTheUserConnected() {
    attachedByManagedMode("3", "alice");
    when(emailManagedModeService.getManagedConnectorId()).thenReturn(5L);
    when(emailManagedModeService.getExcludedGroups()).thenReturn(List.of(), List.of("/externals"));
    when(userAcl.getUserIdentity("alice")).thenReturn(null);

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * A provider change on 3: chloe's setting cannot be read when her turn comes, so she is
   * left connected and alice is still processed. Killed by the mutant whose isStillOn
   * catch answers true.
   */
  @Test
  void aProviderChangeLeavesConnectedAUserWhoseSettingCannotBeRead() {
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenReturn(List.of("alice", "chloe"));
    when(userEmailSettingService.getStoredEmailConnectorId("alice")).thenReturn("3");
    when(userEmailSettingService.getStoredEmailConnectorId("chloe")).thenThrow(new IllegalStateException("unreadable"));

    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("chloe");
  }

  /**
   * A provider change on 3: the selection loads chloe's and dan's settings, then dan
   * moves to connector 4 from another session before his turn. His turn reads his
   * setting in a request lifecycle of its own, finds 4 and leaves him connected. Killed
   * by the mutant that runs the whole walk in the selection's lifecycle
   * ({@code inItsOwnLifecycle(() -> disconnectAllNow(id))} on the executor), whose
   * persistence context still serves dan's row as the selection loaded it.
   */
  @Test
  void aUserWhoMovedAfterTheSelectionLoadedTheirSettingIsReadAgainAtTheirTurn() {
    Map<String, String> stored = new HashMap<>(Map.of("chloe", "3", "dan", "3"));
    PersistenceContext context = new PersistenceContext(stored);
    when(container.getComponentInstancesOfType(ComponentRequestLifecycle.class)).thenReturn(List.of(context));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenAnswer(invocation -> {
      List<String> users = List.of("chloe", "dan");
      users.forEach(context::read);
      stored.put("dan", "4");
      return users;
    });
    when(userEmailSettingService.getStoredEmailConnectorId(anyString())).thenAnswer(invocation -> context.read(invocation.getArgument(0)));

    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("chloe");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("dan");
  }

  /**
   * Both runs open one request lifecycle for their selection and one for each user's
   * turn, never one around the whole run: two users, three lifecycles. Killed by the
   * mutants that run either walk in a single lifecycle, as the previous test describes.
   */
  @Test
  void eachStepOfARunOpensARequestLifecycleOfItsOwn() {
    PersistenceContext context = new PersistenceContext(new HashMap<>());
    when(container.getComponentInstancesOfType(ComponentRequestLifecycle.class)).thenReturn(List.of(context));
    attachedByManagedMode("3", "alice", "bob");
    inForce(5L);
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenReturn(List.of("chloe", "dan"));
    lenient().when(userEmailSettingService.getStoredEmailConnectorId("chloe")).thenReturn("3");
    lenient().when(userEmailSettingService.getStoredEmailConnectorId("dan")).thenReturn("3");

    service.disconnectUsersNoLongerManaged();
    assertEquals(3, context.lifecycles);

    service.disconnectAllUsersOf(3L);
    assertEquals(6, context.lifecycles);
  }

  /**
   * bob is re-checked at his own turn, after alice's delete, which clears his mark: he
   * is left connected. Killed by the mutant that re-checks every user before the first
   * delete ({@code .toList().stream()} between the re-check and the delete).
   */
  @Test
  void aUserTheDeleteBeforeTheirTurnUnmarksIsLeftConnected() {
    attachedByManagedMode("3", "alice", "bob");
    inForce(5L);
    Set<String> marked = new HashSet<>(Set.of("alice", "bob"));
    when(userEmailSettingService.isConnectedByManagedMode(anyString())).thenAnswer(invocation -> marked.contains(invocation.getArgument(0)));
    doAnswer(invocation -> marked.remove("bob")).when(userEmailSettingService).deleteUserEmailSetting("alice");

    service.disconnectUsersNoLongerManaged();

    verify(userEmailSettingService).deleteUserEmailSetting("alice");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("bob");
  }

  /**
   * A provider change on 3: dan is re-checked at his own turn, after chloe's delete,
   * which moves him to connector 4: he is left connected. Killed by the mutant that
   * re-checks every user before the first delete ({@code .toList().stream()} between the
   * re-check and the delete).
   */
  @Test
  void aUserTheDeleteBeforeTheirTurnMovesIsLeftConnectedByAProviderChange() {
    Map<String, String> stored = new HashMap<>(Map.of("chloe", "3", "dan", "3"));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(3L)).thenReturn(List.of("chloe", "dan"));
    when(userEmailSettingService.getStoredEmailConnectorId(anyString())).thenAnswer(invocation -> stored.get(invocation.getArgument(0)));
    doAnswer(invocation -> stored.put("dan", "4")).when(userEmailSettingService).deleteUserEmailSetting("chloe");

    service.disconnectAllUsersOf(3L);

    verify(userEmailSettingService).deleteUserEmailSetting("chloe");
    verify(userEmailSettingService, never()).deleteUserEmailSetting("dan");
  }

  /**
   * A persistence context as a request lifecycle opens and closes it: a setting read
   * while it is open is served, until it closes, as it was first loaded, the way
   * Hibernate serves an entity already in its session.
   */
  private static final class PersistenceContext implements ComponentRequestLifecycle {

    private final Map<String, String> stored;

    private final Map<String, String> loaded = new HashMap<>();

    private boolean                   started;

    private int                       lifecycles;

    PersistenceContext(Map<String, String> stored) {
      this.stored = stored;
    }

    @Override
    public void startRequest(ExoContainer exoContainer) {
      started = true;
      lifecycles++;
    }

    @Override
    public void endRequest(ExoContainer exoContainer) {
      started = false;
      loaded.clear();
    }

    @Override
    public boolean isStarted(ExoContainer exoContainer) {
      return started;
    }

    String read(String user) {
      assertTrue(started, "a setting is read outside any request lifecycle");
      return loaded.computeIfAbsent(user, stored::get);
    }
  }
}
