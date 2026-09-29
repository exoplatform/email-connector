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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.dao.EmailDelegationDAO;
import org.exoplatform.emailConnector.entity.EmailDelegationEntity;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.listener.EmailBoxCleanupListener;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.web.security.codec.CodecInitializer;

import io.meeds.social.translation.service.TranslationService;

/**
 * End to end from an administrator's change to the shares it ends (EXO-89654 x
 * EXO-90457): moving a connector to another credentials provider disconnects every
 * user of it through {@link UserEmailSettingService#deleteUserEmailSetting(String)},
 * whose {@code EmailBoxCleanupEvent} reaches {@link EmailBoxCleanupListener}, which
 * ends the mailboxes shared with that user as a leave would. The real disconnection
 * service, settings service, cleanup listener, delegation service and delegation
 * storage run here against the real schema, with the event published by Spring and
 * the disconnection run as on its executor's bare thread -- no transaction around it.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailManagedDisconnectionService.class, UserEmailSettingService.class, EmailBoxCleanupListener.class,
    EmailDelegationService.class, EmailDelegationStorage.class, ManagedDisconnectionEndsSharesTest.DelegationEvents.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManagedDisconnectionEndsSharesTest {

  private static final long   CONNECTOR_ID       = 7L;

  private static final long   OTHER_CONNECTOR_ID = 8L;

  private static final String DISCONNECTED_USER  = "bob";

  @Autowired
  private EmailManagedDisconnectionService disconnectionService;

  @Autowired
  private EmailDelegationStorage           emailDelegationStorage;

  @Autowired
  private EmailDelegationDAO               emailDelegationDAO;

  @Autowired
  private DelegationEvents                 delegationEvents;

  @MockitoBean
  private SettingService                   settingService;

  @MockitoBean
  private EmailFolderStorage               emailFolderStorage;

  @MockitoBean
  private EmailBoxService                  emailBoxService;

  @MockitoBean
  private EmailContactService              emailContactService;

  @MockitoBean
  private EmailSignatureService            emailSignatureService;

  @MockitoBean
  private EmailConnectorService            emailConnectorService;

  @MockitoBean
  private EmailCredentialsResolver         emailCredentialsResolver;

  @MockitoBean
  private MailboxAclEngineRegistry         aclEngineRegistry;

  @MockitoBean
  private IdentityManager                  identityManager;

  @MockitoBean
  private ManagedConnectorService          managedConnectorService;

  @MockitoBean
  private EmailManagedModeService          emailManagedModeService;

  @MockitoBean
  private CodecInitializer                 codecInitializer;

  @MockitoBean
  private TranslationService               translationService;

  @MockitoBean
  private ExoFeatureService                featureService;

  /**
   * The JPA slice of the delegation table.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailDelegationEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailDelegationDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Records the delegation events published, as the owner-side notification reads them.
   */
  @Component
  static class DelegationEvents {

    private final List<EmailDelegationEvent> received = new CopyOnWriteArrayList<>();

    /**
     * @param event a published delegation event
     */
    @EventListener
    public void onDelegationEvent(EmailDelegationEvent event) {
      received.add(event);
    }
  }

  /**
   * Runs the disconnections on the caller's thread, and stores one user of the
   * connector, {@value #DISCONNECTED_USER}, as the settings service would.
   */
  @BeforeEach
  void setup() {
    emailDelegationDAO.deleteAll();
    delegationEvents.received.clear();
    disconnectionService.setExecutor(Runnable::run);
    when(settingService.getContextsByTypeAndScopeAndSettingName(eq(Context.USER.getName()),
                                                                anyString(),
                                                                eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE_ID),
                                                                eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                                                                anyInt(),
                                                                anyInt()))
                                                                          .thenReturn(List.of(Context.USER.id(DISCONNECTED_USER)));
    when(settingService.get(Context.USER.id(DISCONNECTED_USER),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            UserEmailSettingService.USER_EMAIL_SETTING_KEY))
                                                                            .thenAnswer(invocation -> SettingValue.create("{\"emailConnectorId\":\""
                                                                                + CONNECTOR_ID + "\"}"));
  }

  /**
   * Clears the rows the test wrote.
   */
  @AfterEach
  void cleanup() {
    emailDelegationDAO.deleteAll();
  }

  /**
   * The disconnected user's accepted share ends as a leave: back to DECLINED, the
   * delegated folders dropped, the owner told with a LEFT event. Their pending
   * invitation, the share they granted as owner and another user's share are left
   * as they were -- the ACLs on the server are not eXo's to remove.
   */
  @Test
  void aProviderChangeDisconnectionEndsTheSharesTheUserWasReading() {
    EmailDelegation accepted = emailDelegationStorage.create(delegation("alice", DISCONNECTED_USER, DelegationStatus.ACCEPTED));
    EmailDelegation pending = emailDelegationStorage.create(delegation("carol", DISCONNECTED_USER, DelegationStatus.PENDING));
    EmailDelegation granted = emailDelegationStorage.create(delegation(DISCONNECTED_USER, "dave", DelegationStatus.ACCEPTED));
    EmailDelegation someoneElses = emailDelegationStorage.create(delegation("alice", "erin", DelegationStatus.ACCEPTED));

    disconnectionService.disconnectAllUsersOf(CONNECTOR_ID);

    verify(settingService).remove(Context.USER.id(DISCONNECTED_USER),
                                  UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                  UserEmailSettingService.USER_EMAIL_SETTING_KEY);
    verify(emailBoxService).deleteUserEmails(DISCONNECTED_USER);

    EmailDelegation ended = emailDelegationStorage.getAsGrantee(DISCONNECTED_USER, accepted.getId());
    assertEquals(DelegationStatus.DECLINED, ended.getStatus(), "the share the user was reading ends as a leave");
    assertNotNull(ended.getRespondedDate());
    verify(emailFolderStorage).deleteDelegatedFolders(DISCONNECTED_USER, accepted.getId());
    assertTrue(delegationEvents.received.stream()
                                        .anyMatch(event -> event.type() == EmailDelegationEvent.Type.LEFT
                                            && DISCONNECTED_USER.equals(event.actor())
                                            && accepted.getId() == event.delegation().getId()),
               "the owner is told as for a leave: " + delegationEvents.received);
    assertEquals(1, delegationEvents.received.size(), "one share ended, one event: " + delegationEvents.received);

    assertEquals(DelegationStatus.PENDING, emailDelegationStorage.getAsGrantee(DISCONNECTED_USER, pending.getId()).getStatus());
    assertEquals(DelegationStatus.ACCEPTED, emailDelegationStorage.getAsGrantee("dave", granted.getId()).getStatus());
    assertEquals(DelegationStatus.ACCEPTED, emailDelegationStorage.getAsGrantee("erin", someoneElses.getId()).getStatus());
    verify(emailFolderStorage, never()).deleteDelegatedFolders(eq("dave"), anyLong());
    verify(emailFolderStorage, never()).deleteDelegatedFolders(eq("erin"), anyLong());
  }

  /**
   * A share discovered on the server goes back to AVAILABLE, not DECLINED: nobody
   * invited the user, so it is offered again once a mailbox is connected.
   */
  @Test
  void aServerDiscoveredShareGoesBackToAvailable() {
    EmailDelegation row = delegation("alice", DISCONNECTED_USER, DelegationStatus.ACCEPTED);
    row.setOrigin(DelegationOrigin.SERVER);
    EmailDelegation discovered = emailDelegationStorage.create(row);

    disconnectionService.disconnectAllUsersOf(CONNECTOR_ID);

    assertEquals(DelegationStatus.AVAILABLE, emailDelegationStorage.getAsGrantee(DISCONNECTED_USER, discovered.getId()).getStatus());
  }

  /**
   * A connector nobody is stored on disconnects nobody, so no share moves.
   */
  @Test
  void anotherConnectorsChangeLeavesTheSharesAlone() {
    when(settingService.get(any(), any(), eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY)))
                                                                                          .thenAnswer(invocation -> SettingValue.create("{\"emailConnectorId\":\""
                                                                                              + OTHER_CONNECTOR_ID + "\"}"));
    EmailDelegation accepted = emailDelegationStorage.create(delegation("alice", DISCONNECTED_USER, DelegationStatus.ACCEPTED));

    disconnectionService.disconnectAllUsersOf(CONNECTOR_ID);

    assertEquals(DelegationStatus.ACCEPTED, emailDelegationStorage.getAsGrantee(DISCONNECTED_USER, accepted.getId()).getStatus());
    assertTrue(delegationEvents.received.isEmpty(), "no share ended: " + delegationEvents.received);
  }

  /**
   * @param owner the mailbox owner
   * @param grantee the user the mailbox is shared with
   * @param status the row's status
   * @return an eXo-granted Reader share on {@link #CONNECTOR_ID}
   */
  private EmailDelegation delegation(String owner, String grantee, DelegationStatus status) {
    EmailDelegation row = new EmailDelegation();
    row.setOwnerId(owner);
    row.setOwnerMailbox(owner + "@acme.com");
    row.setGranteeId(grantee);
    row.setConnectorId(CONNECTOR_ID);
    row.setPreset(DelegationPreset.READER);
    row.setRights("lrs");
    row.setStatus(status);
    row.setOrigin(DelegationOrigin.EXO);
    return row;
  }
}
