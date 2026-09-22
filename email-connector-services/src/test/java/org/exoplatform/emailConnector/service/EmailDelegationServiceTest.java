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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The delegation lifecycle over a mocked engine, storage and settings: the security
 * contract as tests -- the owner's own session and mailbox, the grantee resolved from
 * their own setting, the grant expanded and capped by the engine and recorded as
 * written, the grant at invite and NOT at accept, the decline that does not revoke,
 * the rows read with their viewer, the engine owning its transport -- and the state
 * machine around them, including the two meanings of "Accept" (plan, section 5.2).
 */
@ExtendWith(MockitoExtension.class)
class EmailDelegationServiceTest {

  private static final String                 OWNER           = "alice";

  private static final String                 OWNER_MAILBOX   = "alice@acme.com";

  private static final String                 GRANTEE         = "bob";

  private static final String                 GRANTEE_MAILBOX = "bob@acme.com";

  private static final long                   CONNECTOR_ID    = 7L;

  private static final String                 INBOX           = "INBOX";

  /** A plain RFC 4314 server that advertises what it does. */
  private static final MailboxAclCapabilities SUPPORTED       = MailboxAclCapabilities.imap(true, true);

  /** A server with a real acceptance step and its own owner e-mails -- BlueMind's shape. */
  private static final MailboxAclCapabilities SUBSCRIBING     =
                                                          new MailboxAclCapabilities(true,
                                                                                     false,
                                                                                     true,
                                                                                     GrantGranularity.MAILBOX,
                                                                                     true,
                                                                                     true,
                                                                                     null);

  @Mock
  private UserEmailSettingService             userEmailSettingService;

  @Mock
  private EmailConnectorService               emailConnectorService;

  @Mock
  private MailboxAclEngineRegistry            aclEngineRegistry;

  @Mock
  private EmailDelegationStorage              emailDelegationStorage;

  @Mock
  private EmailFolderStorage                  emailFolderStorage;

  @Mock
  private IdentityManager                     identityManager;

  @Mock
  private ApplicationEventPublisher           eventPublisher;

  @Mock
  private MailboxAclEngine                    engine;

  @Mock
  private Store                               store;

  @InjectMocks
  private EmailDelegationService              service;

  private EmailConnector                      connector;

  /**
   * Alice and Bob both connected on connector 7; the registry answers the mocked
   * engine; every row written back is answered as written.
   */
  @BeforeEach
  void setUp() throws Exception {
    connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(aclEngineRegistry.engineFor(connector)).thenReturn(engine);
    lenient().when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(setting(CONNECTOR_ID, OWNER_MAILBOX));
    lenient().when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(setting(CONNECTOR_ID, GRANTEE_MAILBOX));
    lenient().when(userEmailSettingService.connect(anyString(), anyString())).thenReturn(store);
    lenient().when(emailDelegationStorage.create(any())).thenAnswer(invocation -> {
      EmailDelegation created = invocation.getArgument(0);
      created.setId(100L);
      return created;
    });
    lenient().when(emailDelegationStorage.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(engine.presetOf(any())).thenAnswer(invocation -> DelegationPreset.fromRights(invocation.getArgument(0)));
    Identity bob = new Identity("organization", GRANTEE);
    bob.setEnable(true);
    lenient().when(identityManager.getOrCreateUserIdentity(GRANTEE)).thenReturn(bob);
  }

  /**
   * Puts the cap back the way the JVM had it.
   */
  @AfterEach
  void clearTheTunables() {
    System.clearProperty(EmailDelegationService.MAX_PER_USER_PROPERTY);
  }

  // ---------------------------------------------------------------------------------
  // Invite
  // ---------------------------------------------------------------------------------

  /**
   * The grant: on a session built for the OWNER (their connector, their username --
   * whose store, when the engine asks for it, is opened with the owner's connector and
   * username), on the owner's INBOX, for the identifier read from the GRANTEE's own
   * setting, handing the engine the preset and the owner's own rights; recorded
   * PENDING/EXO as the engine says it wrote it, with the identifier kept for the revoke.
   */
  @Test
  void inviteGrantsThePresetOnTheOwnersOwnInboxAsTheOwner() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    MailboxRights ownerRights = MailboxRights.of("lrswipkxtea");
    when(engine.myRights(any(), eq(INBOX))).thenReturn(ownerRights);
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(ownerRights)))
                                                                                                          .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                           MailboxRights.of("lrswit")));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).grant(session.capture(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(ownerRights));
    assertEquals(OWNER, session.getValue().username(), "the session is the owner's");
    assertSame(connector, session.getValue().connector());
    assertEquals(OWNER_MAILBOX, session.getValue().mailboxIdentifier());
    assertSame(store, session.getValue().store(), "and its store is opened with the owner's connector and username");
    verify(userEmailSettingService).connect(String.valueOf(CONNECTOR_ID), OWNER);
    assertEquals(DelegationStatus.PENDING, delegation.getStatus());
    assertEquals(DelegationOrigin.EXO, delegation.getOrigin());
    assertEquals(DelegationPreset.EDITOR, delegation.getPreset());
    assertEquals("lrswit", delegation.getRights());
    assertEquals("lrswit", delegation.getNativeRights(), "the server's own words, letters on IMAP");
    assertEquals(OWNER, delegation.getOwnerId());
    assertEquals(OWNER_MAILBOX, delegation.getOwnerMailbox());
    assertEquals(GRANTEE, delegation.getGranteeId());
    assertEquals(GRANTEE_MAILBOX, delegation.getGranteeMailbox());
    assertEquals(CONNECTOR_ID, delegation.getConnectorId());
    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.INVITED, event.getValue().type());
  }

  /**
   * The row records what the engine WROTE, not what was asked: an Editor the engine
   * capped to {@code lrs} (the owner holds {@code lrsa}) is recorded a Reader, with the
   * engine's own vocabulary kept beside the letters. The capping itself is the
   * engine's ({@code ImapAclEngineTest}); the service passes the owner's rights in.
   */
  @Test
  void inviteRecordsWhatTheEngineWroteNotWhatWasAsked() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrsa"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(MailboxRights.of("lrsa"))))
                                                                                                                       .thenReturn(new MailboxAce(GRANTEE_MAILBOX,
                                                                                                                                                  MailboxRights.of("lrs"),
                                                                                                                                                  "Read",
                                                                                                                                                  DelegationPreset.READER));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR);

    assertEquals("lrs", delegation.getRights());
    assertEquals("Read", delegation.getNativeRights());
    assertEquals(DelegationPreset.READER, delegation.getPreset(), "what was granted, not what was asked");
  }

  /**
   * An owner whose MYRIGHTS carries no {@code a} cannot SETACL: reported with its code,
   * nothing written. Read per session, never assumed -- BlueMind's owner does hold it.
   */
  @Test
  void inviteRefusesWhenTheOwnerCannotAdministerTheirInbox() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswit"));

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER));

    assertEquals(MailboxAclException.OWNER_CANNOT_ADMINISTER, thrown.getCode());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
    verify(emailDelegationStorage, never()).create(any());
  }

  /**
   * A server the probe reports as unable to share: the probe's reason, nothing else
   * tried.
   */
  @Test
  void inviteRefusesAServerWithoutAcls() throws Exception {
    when(engine.probe(any())).thenReturn(MailboxAclCapabilities.unsupported(MailboxAclException.UNSUPPORTED));

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER));

    assertEquals(MailboxAclException.UNSUPPORTED, thrown.getCode());
    verify(engine, never()).myRights(any(), any());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
  }

  /**
   * The engine owns its transport: the service opens nothing itself, so an engine that
   * never asks for the store (a REST one, a no-op) costs no IMAP connection. Fails if
   * the service goes back to opening a store at its call sites.
   */
  @Test
  void inviteOpensNoConnectionTheEngineDidNotAskFor() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrsa"));
    when(engine.grant(any(), any(), any(), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrs")));

    service.invite(OWNER, GRANTEE, DelegationPreset.READER);

    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * The grantee's identifier comes from THEIR connected setting on the SAME preset; a
   * grantee connected elsewhere, or not at all, cannot be granted to -- and the engine
   * is never even asked.
   */
  @Test
  void inviteRefusesAGranteeNotConnectedOnTheSamePreset() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(setting(8L, GRANTEE_MAILBOX));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER));

    assertEquals(EmailDelegationService.GRANTEE_NOT_CONNECTED_MESSAGE, thrown.getMessage());
    verify(engine, never()).probe(any());
    verify(engine, never()).grant(any(), any(), any(), any(), any());

    when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(new UserEmailSetting());
    assertEquals(EmailDelegationService.GRANTEE_NOT_CONNECTED_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER)).getMessage());
  }

  /**
   * An eXo username nobody holds, or a disabled account, is refused before anything.
   */
  @Test
  void inviteRefusesAnUnknownOrDisabledGrantee() throws Exception {
    when(identityManager.getOrCreateUserIdentity(GRANTEE)).thenReturn(null);

    assertEquals(EmailDelegationService.GRANTEE_UNKNOWN_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER)).getMessage());
    verify(engine, never()).probe(any());
  }

  /**
   * Self, and a preset that is not grantable (CUSTOM, null): refused before anything.
   */
  @Test
  void inviteRefusesSelfAndUngrantablePresets() throws Exception {
    assertEquals(EmailDelegationService.SELF_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, OWNER, DelegationPreset.READER)).getMessage());
    assertEquals(EmailDelegationService.PRESET_INVALID_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.CUSTOM)).getMessage());
    assertEquals(EmailDelegationService.PRESET_INVALID_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, null)).getMessage());
    verify(engine, never()).probe(any());
  }

  /**
   * A share that already stands (pending or accepted) is not granted twice.
   */
  @Test
  void inviteRefusesAShareThatAlreadyStands() throws Exception {
    when(emailDelegationStorage.getByKey(GRANTEE, CONNECTOR_ID, OWNER_MAILBOX)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));

    assertEquals(EmailDelegationService.ALREADY_SHARED_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER)).getMessage());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
  }

  /**
   * A declined (or revoked, gone, available) row of the same key is reused: the unique
   * key holds and the history stays on one row.
   */
  @Test
  void inviteReusesADeclinedRow() throws Exception {
    EmailDelegation declined = row(DelegationStatus.DECLINED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getByKey(GRANTEE, CONNECTOR_ID, OWNER_MAILBOX)).thenReturn(declined);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswita"));
    when(engine.grant(any(), any(), any(), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit")));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR);

    verify(emailDelegationStorage).update(declined);
    verify(emailDelegationStorage, never()).create(any());
    assertEquals(DelegationStatus.PENDING, delegation.getStatus());
    assertNull(delegation.getRespondedDate());
  }

  /**
   * A mailbox that cannot be connected is a typed, fixed code -- translated by the
   * session the moment an engine asks for the store, the library's text kept out.
   */
  @Test
  void inviteTranslatesAnUnreachableMailbox() throws Exception {
    when(userEmailSettingService.connect(anyString(), anyString())).thenThrow(new MessagingException("Connection refused: imap.acme.com"));
    when(engine.probe(any())).thenAnswer(invocation -> {
      invocation.getArgument(0, MailboxAclSession.class).store();
      return SUPPORTED;
    });

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER));

    assertEquals(MailboxAclException.UNREACHABLE, thrown.getCode());
    assertFalse(thrown.getMessage().contains("imap.acme.com"));
  }

  // ---------------------------------------------------------------------------------
  // Decline and leave: the ACL stays
  // ---------------------------------------------------------------------------------

  /**
   * THE lifecycle rule: declining records the answer and touches NOTHING on the server
   * -- no revoke, not even an engine asked. Fails if a revoke (which could only run as
   * the owner, unattended) is ever added to the decline.
   */
  @Test
  void declineRecordsTheAnswerAndDoesNotRevoke() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));

    EmailDelegation delegation = service.decline(GRANTEE, 100L);

    assertEquals(DelegationStatus.DECLINED, delegation.getStatus());
    assertTrue(delegation.getRespondedDate() != null);
    verify(engine, never()).revoke(any(), any(), any());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
    verify(aclEngineRegistry, never()).engineFor(any());
  }

  /**
   * Only a pending invitation can be declined.
   */
  @Test
  void declineRefusesANonPendingRow() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));

    assertEquals(EmailDelegationService.NOT_PENDING_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.decline(GRANTEE, 100L)).getMessage());
  }

  /**
   * Leaving an accepted eXo share: back to DECLINED (the owner still sees the grantee
   * and can remove them), folders dropped, ACL untouched -- and on an IMAP engine,
   * whose unsubscribe is a no-op, no connection opened.
   */
  @Test
  void leaveKeepsTheAclAndDropsTheRegisteredFolders() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));

    EmailDelegation delegation = service.leave(GRANTEE, 100L);

    assertEquals(DelegationStatus.DECLINED, delegation.getStatus());
    verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 100L);
    verify(engine, never()).revoke(any(), any(), any());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * #432-3 -- a disconnected or rebound mailbox ends the shares its user was using, as a
   * leave would: eXo-made ones back to DECLINED, server-made ones to AVAILABLE, their
   * folders dropped, the owner told; a pending or declined one is left alone, and no
   * server is asked.
   */
  @Test
  void aDisconnectEndsTheSharesInUseAsALeaveWould() throws Exception {
    EmailDelegation exoMade = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    EmailDelegation serverMade = row(DelegationStatus.ACCEPTED, DelegationOrigin.SERVER);
    serverMade.setId(101L);
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    pending.setId(102L);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(exoMade, serverMade, pending));

    service.endReceivedShares(GRANTEE);

    assertEquals(DelegationStatus.DECLINED, exoMade.getStatus());
    assertEquals(DelegationStatus.AVAILABLE, serverMade.getStatus());
    assertEquals(DelegationStatus.PENDING, pending.getStatus());
    verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 100L);
    verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 101L);
    verify(emailFolderStorage, never()).deleteDelegatedFolders(GRANTEE, 102L);
    verify(eventPublisher, times(2)).publishEvent(any(EmailDelegationEvent.class));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * #432-5 -- a namespace segment names an owner by the local part only when a single
   * connected user has it: on a preset serving two domains, "anne" is nobody.
   */
  @Test
  void aLocalPartNamesAnOwnerOnlyWhenItIsUnique() {
    Map<String, String> oneAnne = Map.of("anne@acme.com", "anne", "bob@acme.com", "bob");
    Map<String, String> twoAnnes = Map.of("anne@acme.com", "anne", "anne@globex.com", "anne2");

    assertEquals("anne", ReflectionTestUtils.invokeMethod(service, "ownerFor", oneAnne, "anne"));
    assertEquals("anne2", ReflectionTestUtils.invokeMethod(service, "ownerFor", twoAnnes, "anne@globex.com"));
    assertNull(ReflectionTestUtils.invokeMethod(service, "ownerFor", twoAnnes, "anne"));

    EmailDelegation acme = row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER);
    acme.setOwnerMailbox("anne@acme.com");
    EmailDelegation globex = row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER);
    globex.setId(101L);
    globex.setOwnerMailbox("anne@globex.com");
    SharedMailbox listed = new SharedMailbox("anne", "Other Users/anne", "Other Users/anne/INBOX", "/");
    assertSame(acme, ReflectionTestUtils.invokeMethod(service, "rowFor", List.of(acme), CONNECTOR_ID, listed));
    assertNull(ReflectionTestUtils.invokeMethod(service, "rowFor", List.of(acme, globex), CONNECTOR_ID, listed));
  }

  /**
   * #432-6 -- two listings creating the same share at once: the second insert is
   * refused by the unique key, and the row the first created is answered instead of a
   * server error.
   */
  @Test
  void aConcurrentCreateAnswersTheRowTheFirstCreated() {
    EmailDelegation mine = row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER);
    EmailDelegation theirs = row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER);
    doThrow(new DataIntegrityViolationException("UQ_EMAIL_DELEGATION")).when(emailDelegationStorage).create(any());
    when(emailDelegationStorage.getByKey(GRANTEE, CONNECTOR_ID, OWNER_MAILBOX)).thenReturn(theirs);

    assertSame(theirs, ReflectionTestUtils.invokeMethod(service, "createOrReread", mine));
  }

  /**
   * Leaving withdraws the grantee's server-side subscription where the engine has one
   * (BlueMind), as the grantee, best effort: a refusal is logged and the leave stands.
   */
  @Test
  void leaveWithdrawsTheServerSubscriptionBestEffort() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));

    service.leave(GRANTEE, 100L);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).unsubscribe(session.capture(), eq(OWNER_MAILBOX));
    assertEquals(GRANTEE, session.getValue().username(), "as the grantee, never as the owner");

    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));
    doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).unsubscribe(any(), any());
    assertEquals(DelegationStatus.DECLINED, service.leave(GRANTEE, 100L).getStatus(), "the eXo-side leave stands");
  }

  /**
   * Leaving a server-discovered share offers it again as AVAILABLE.
   */
  @Test
  void leaveOfAServerShareGoesBackToAvailable() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.SERVER));

    assertEquals(DelegationStatus.AVAILABLE, service.leave(GRANTEE, 100L).getStatus());

    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));
    assertEquals(EmailDelegationService.NOT_ACCEPTED_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.leave(GRANTEE, 100L)).getMessage());
  }

  // ---------------------------------------------------------------------------------
  // Accept: the server decides
  // ---------------------------------------------------------------------------------

  /**
   * Accept runs on the GRANTEE's session, writes no ACL, finds the mailbox under the
   * namespace, reads MYRIGHTS on its INBOX, and registers that INBOX as a
   * DELEGATED_INBOX folder of the grantee with the sync OFF. On an IMAP engine there is
   * no server-side step: "Accept" is purely eXo-side.
   */
  @Test
  void acceptConfirmsWithTheServerOnTheGranteesSessionAndRegistersTheInbox() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));
    when(emailDelegationStorage.count(GRANTEE, DelegationStatus.ACCEPTED)).thenReturn(0L);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    SharedMailbox shared = new SharedMailbox("alice", "Other Users/alice", "Other Users/alice/INBOX", "/");
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(shared);
    when(engine.myRights(any(), eq("Other Users/alice/INBOX"))).thenReturn(MailboxRights.of("lrswit"));
    when(emailFolderStorage.getFolderByRemoteName(GRANTEE, "Other Users/alice/INBOX")).thenReturn(null);
    when(emailFolderStorage.createFolder(any())).thenAnswer(invocation -> {
      EmailFolder created = invocation.getArgument(0);
      created.setId(77L);
      return created;
    });

    EmailDelegation delegation = service.accept(GRANTEE, 100L);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).findSharedMailbox(session.capture(), eq(OWNER_MAILBOX));
    assertEquals(GRANTEE, session.getValue().username(), "the grantee's own session");
    assertEquals(GRANTEE_MAILBOX, session.getValue().mailboxIdentifier());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
    verify(engine, never()).subscribe(any(), any());
    assertEquals(DelegationStatus.ACCEPTED, delegation.getStatus());
    assertEquals("Other Users/alice", delegation.getRemoteRoot());
    assertEquals("lrswit", delegation.getRights());
    assertEquals(DelegationPreset.EDITOR, delegation.getPreset());
    ArgumentCaptor<EmailFolder> folder = ArgumentCaptor.forClass(EmailFolder.class);
    verify(emailFolderStorage).createFolder(folder.capture());
    assertEquals(GRANTEE, folder.getValue().getUserId());
    assertEquals("Other Users/alice/INBOX", folder.getValue().getRemoteName());
    assertEquals(MailFolderView.TYPE_DELEGATED_INBOX, folder.getValue().getType());
    assertEquals(100L, folder.getValue().getDelegationId());
    // And opted IN, which is what makes accepting a share actually mirror anything: the
    // storage writes every new folder opted out (right for a folder DISCOVERED in your
    // own mailbox, wrong for one you just asked for), so the opt-in is a write of its
    // own. The INBOX alone -- every other folder of the shared mailbox stays out.
    verify(emailFolderStorage).updateSyncEnabled(eq(GRANTEE), eq(77L), eq(true), any());
  }

  /**
   * On a server with a real acceptance step (BlueMind: the share was invisible to the
   * delegate until accepted), accept performs the engine's subscription FIRST, as the
   * grantee, and only then looks for the mailbox -- the order the observation imposes
   * (plan, sections 5.2 and 13.B.14). The preset is the engine's reading of the letters,
   * not the exact IMAP one.
   */
  @Test
  void acceptSubscribesFirstWhereTheServerHasAnAcceptanceStep() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER));
    when(engine.probe(any())).thenReturn(SUBSCRIBING);
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(new SharedMailbox("alice",
                                                                                          "Autres utilisateurs/alice",
                                                                                          "Autres utilisateurs/alice/INBOX",
                                                                                          "/"));
    when(engine.myRights(any(), eq("Autres utilisateurs/alice/INBOX"))).thenReturn(MailboxRights.of("lrp"));
    when(engine.presetOf(MailboxRights.of("lrp"))).thenReturn(DelegationPreset.READER);

    EmailDelegation delegation = service.accept(GRANTEE, 100L);

    InOrder order = inOrder(engine);
    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    order.verify(engine).subscribe(session.capture(), eq(OWNER_MAILBOX));
    order.verify(engine).findSharedMailbox(any(), eq(OWNER_MAILBOX));
    assertEquals(GRANTEE, session.getValue().username(), "the subscription runs as the grantee");
    assertEquals(DelegationStatus.ACCEPTED, delegation.getStatus());
    assertEquals(DelegationPreset.READER, delegation.getPreset(), "lrp is a Reader on the engine that says so");
    assertEquals("lrp", delegation.getRights());
  }

  /**
   * A refused subscription is a failed accept with the server's code, not a
   * revocation: the row is left as it was and nothing is registered.
   */
  @Test
  void acceptLeavesTheRowWhenTheSubscriptionIsRefused() throws Exception {
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(pending);
    when(engine.probe(any())).thenReturn(SUBSCRIBING);
    doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "403")).when(engine).subscribe(any(), eq(OWNER_MAILBOX));

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.accept(GRANTEE, 100L));

    assertEquals(MailboxAclException.SERVER_REFUSED, thrown.getCode());
    assertEquals(DelegationStatus.PENDING, pending.getStatus());
    verify(engine, never()).findSharedMailbox(any(), any());
    verify(emailDelegationStorage, never()).update(any());
    verify(emailFolderStorage, never()).createFolder(any());
  }

  /**
   * The grantee's own discovery walk may have registered the shared INBOX as a custom
   * folder already (a server that lists Other Users to a plain LIST "*"): the row is
   * adopted, not duplicated.
   */
  @Test
  void acceptAdoptsAFolderTheDiscoveryWalkAlreadyRegistered() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.AVAILABLE, DelegationOrigin.SERVER));
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(new SharedMailbox("alice", "Other Users/alice", "Other Users/alice/INBOX", "/"));
    when(engine.myRights(any(), eq("Other Users/alice/INBOX"))).thenReturn(MailboxRights.of("lrs"));
    EmailFolder existing = new EmailFolder();
    existing.setId(99L);
    existing.setType(MailFolderView.TYPE_CUSTOM);
    when(emailFolderStorage.getFolderByRemoteName(GRANTEE, "Other Users/alice/INBOX")).thenReturn(existing);

    EmailDelegation delegation = service.accept(GRANTEE, 100L);

    verify(emailFolderStorage).adoptAsDelegated(GRANTEE, 99L, 100L, MailFolderView.TYPE_DELEGATED_INBOX);
    verify(emailFolderStorage).updateSyncEnabled(eq(GRANTEE), eq(99L), eq(true), any());
    verify(emailFolderStorage, never()).createFolder(any());
    assertEquals(DelegationPreset.READER, delegation.getPreset(), "a server share's preset is what its letters say");
  }

  /**
   * The owner revoked in between: the namespace no longer lists the mailbox, the row
   * goes REVOKED and the grantee is told with the 410 code.
   */
  @Test
  void acceptMarksRevokedWhenTheServerNoLongerListsTheMailbox() throws Exception {
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(pending);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(null);

    DelegationRevokedException thrown = assertThrows(DelegationRevokedException.class, () -> service.accept(GRANTEE, 100L));

    assertEquals(DelegationRevokedException.REVOKED, thrown.getMessage());
    assertEquals(DelegationStatus.REVOKED, pending.getStatus());
    verify(emailDelegationStorage).update(pending);
    verify(emailFolderStorage, never()).createFolder(any());
  }

  /**
   * Listed but without {@code r}: no read access, same outcome.
   */
  @Test
  void acceptMarksRevokedWhenMyRightsGrantsNoRead() throws Exception {
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(pending);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(new SharedMailbox("alice", "Other Users/alice", "Other Users/alice/INBOX", "/"));
    when(engine.myRights(any(), eq("Other Users/alice/INBOX"))).thenReturn(MailboxRights.of("l"));

    assertThrows(DelegationRevokedException.class, () -> service.accept(GRANTEE, 100L));

    assertEquals(DelegationStatus.REVOKED, pending.getStatus());
  }

  /**
   * The per-grantee cap, from its property.
   */
  @Test
  void acceptRefusesBeyondTheCap() throws Exception {
    System.setProperty(EmailDelegationService.MAX_PER_USER_PROPERTY, "2");
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));
    when(emailDelegationStorage.count(GRANTEE, DelegationStatus.ACCEPTED)).thenReturn(2L);

    assertEquals(EmailDelegationService.TOO_MANY_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.accept(GRANTEE, 100L)).getMessage());
    verify(engine, never()).probe(any());
  }

  /**
   * The cap's property: unset or unparseable falls back to the default, never to zero.
   */
  @Test
  void theCapFallsBackToItsDefault() {
    assertEquals(EmailDelegationService.DEFAULT_MAX_PER_USER, service.getMaxPerUser());
    System.setProperty(EmailDelegationService.MAX_PER_USER_PROPERTY, "abc");
    assertEquals(EmailDelegationService.DEFAULT_MAX_PER_USER, service.getMaxPerUser());
    System.setProperty(EmailDelegationService.MAX_PER_USER_PROPERTY, "0");
    assertEquals(EmailDelegationService.DEFAULT_MAX_PER_USER, service.getMaxPerUser());
    System.setProperty(EmailDelegationService.MAX_PER_USER_PROPERTY, " 3 ");
    assertEquals(3, service.getMaxPerUser());
  }

  /**
   * A row that is not the caller's, as grantee, is "not found" -- never "forbidden", so
   * ids enumerate nothing; and a revoked row cannot be accepted.
   */
  @Test
  void acceptScopesTheRowToTheGrantee() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.accept(GRANTEE, 100L));
    verify(engine, never()).probe(any());

    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.REVOKED, DelegationOrigin.EXO));
    assertEquals(EmailDelegationService.NOT_ACCEPTABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.accept(GRANTEE, 100L)).getMessage());
  }

  /**
   * A grantee connected on another preset than the share's cannot reach that server.
   */
  @Test
  void acceptRefusesAGranteeConnectedOnAnotherPreset() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));
    EmailConnector other = new EmailConnector();
    other.setId(8L);
    when(emailConnectorService.getEmailConnector(8L)).thenReturn(other);
    when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(setting(8L, GRANTEE_MAILBOX));

    assertThrows(IllegalAccessException.class, () -> service.accept(GRANTEE, 100L));
    verify(engine, never()).probe(any());
  }

  // ---------------------------------------------------------------------------------
  // Revoke
  // ---------------------------------------------------------------------------------

  /**
   * Revoke: DELETEACL on the OWNER's own session, for the identifier kept on the row
   * (so it works after the grantee disconnected), then REVOKED and the grantee's
   * folders of the mailbox dropped.
   */
  @Test
  void revokeDeletesTheStoredIdentifiersEntryOnTheOwnersSession() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted);
    when(engine.probe(any())).thenReturn(SUPPORTED);

    service.revoke(OWNER, 100L);

    verify(userEmailSettingService, never()).getUserEmailSetting(GRANTEE);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).revoke(session.capture(), eq(INBOX), eq(GRANTEE_MAILBOX));
    assertEquals(OWNER, session.getValue().username(), "the owner's own session");
    assertEquals(OWNER_MAILBOX, session.getValue().mailboxIdentifier());
    assertEquals(DelegationStatus.REVOKED, accepted.getStatus());
    assertTrue(accepted.getRevokedDate() != null);
    verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 100L);
    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.REVOKED, event.getValue().type());
  }

  /**
   * A row that is not the caller's, as owner, is "not found".
   */
  @Test
  void revokeScopesTheRowToTheOwner() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.revoke(OWNER, 100L));
    verify(engine, never()).probe(any());
    verify(engine, never()).revoke(any(), any(), any());
  }

  /**
   * A refusal of DELETEACL leaves the row as it was: the server is the truth.
   */
  @Test
  void revokeLeavesTheRowWhenTheServerRefuses() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).revoke(any(), eq(INBOX), eq(GRANTEE_MAILBOX));

    assertThrows(MailboxAclException.class, () -> service.revoke(OWNER, 100L));

    assertEquals(DelegationStatus.ACCEPTED, accepted.getStatus());
    verify(emailDelegationStorage, never()).update(any());
  }

  // ---------------------------------------------------------------------------------
  // Reading the server's shares
  // ---------------------------------------------------------------------------------

  /**
   * The owner's list is the server's ACL: the owner's own entry and {@code anyone} are
   * skipped; an entry with a row is that row (rights and native form refreshed); an
   * entry naming a connected user without a row gets an AVAILABLE/SERVER row carrying
   * the engine's preset and native form; an identifier nobody holds is listed raw; and
   * a row the ACL no longer carries is REVOKED.
   */
  @Test
  void getGrantedMergesTheServersAclWithTheRows() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea")),
                                                              MailboxAce.ofLetters("anyone", MailboxRights.of("p")),
                                                              MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit")),
                                                              new MailboxAce("carol@acme.com",
                                                                             MailboxRights.of("lrp"),
                                                                             "Read, Freebusy, Invitation",
                                                                             DelegationPreset.READER),
                                                              MailboxAce.ofLetters("dave@other.org", MailboxRights.of("lr"))));
    EmailDelegation bobRow = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    bobRow.setRights("lrs");
    EmailDelegation erinRow = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    erinRow.setId(101L);
    erinRow.setGranteeId("erin");
    erinRow.setGranteeMailbox("erin@acme.com");
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(bobRow, erinRow));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of(OWNER, GRANTEE, "carol", "erin"));
    when(userEmailSettingService.getUserEmailSetting("carol")).thenReturn(setting(CONNECTOR_ID, "Carol@Acme.com"));
    when(userEmailSettingService.getUserEmailSetting("erin")).thenReturn(setting(CONNECTOR_ID, "erin@acme.com"));

    GrantedDelegations granted = service.getGrantedDelegations(OWNER);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).listAcl(session.capture(), eq(INBOX));
    assertEquals(OWNER, session.getValue().username());
    assertTrue(granted.capabilities().supported());
    assertEquals(OWNER_MAILBOX, granted.ownerMailbox());
    List<DelegationGrantee> grantees = granted.grantees();
    assertEquals(4, grantees.size(), "bob, carol, dave, then erin -- never the owner nor anyone");

    DelegationGrantee bob = grantees.get(0);
    assertEquals(GRANTEE, bob.granteeId());
    assertEquals("lrswit", bob.rights(), "the server's letters");
    assertEquals(DelegationPreset.EDITOR, bob.preset());
    assertEquals("lrswit", bob.delegation().getRights(), "and the row refreshed to them");
    assertEquals("lrswit", bob.delegation().getNativeRights());
    assertEquals(DelegationStatus.PENDING, bob.delegation().getStatus());

    DelegationGrantee carol = grantees.get(1);
    assertEquals("carol", carol.granteeId(), "mapped case-insensitively through her connected setting");
    assertEquals(DelegationStatus.AVAILABLE, carol.delegation().getStatus());
    assertEquals(DelegationOrigin.SERVER, carol.delegation().getOrigin());
    assertEquals(OWNER, carol.delegation().getOwnerId());
    assertEquals("carol@acme.com", carol.delegation().getGranteeMailbox());
    assertEquals(DelegationPreset.READER, carol.preset(), "the engine's reading, not the exact IMAP one");
    assertEquals("Read, Freebusy, Invitation", carol.nativeRights(), "the server's own words, kept");
    assertEquals("Read, Freebusy, Invitation", carol.delegation().getNativeRights());

    DelegationGrantee dave = grantees.get(2);
    assertNull(dave.granteeId(), "not an eXo user on this preset");
    assertNull(dave.delegation());
    assertEquals("dave@other.org", dave.identifier());

    DelegationGrantee erin = grantees.get(3);
    assertEquals(DelegationStatus.REVOKED, erin.delegation().getStatus(), "the server no longer carries her entry");
    verify(emailFolderStorage).deleteDelegatedFolders("erin", 101L);
  }

  /**
   * On a server without ACLs the owner still sees eXo's own rows, and the reason.
   */
  @Test
  void getGrantedOnAnUnsupportedServerListsTheRowsOnly() throws Exception {
    when(engine.probe(any())).thenReturn(MailboxAclCapabilities.unsupported(MailboxAclException.UNSUPPORTED_PROVIDER));
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(row(DelegationStatus.PENDING, DelegationOrigin.EXO)));

    GrantedDelegations granted = service.getGrantedDelegations(OWNER);

    assertFalse(granted.capabilities().supported());
    assertEquals(MailboxAclException.UNSUPPORTED_PROVIDER, granted.capabilities().reasonCode());
    assertEquals(1, granted.grantees().size());
    assertEquals(GRANTEE, granted.grantees().get(0).granteeId());
    verify(engine, never()).listAcl(any(), any());
  }

  /**
   * The grantee's discovery: a mailbox the namespace lists with no row gets an
   * AVAILABLE/SERVER row, owner mapped through the connected settings; an accepted
   * row whose mailbox is no longer listed goes GONE. Proposed, never subscribed. And
   * -- the phase-0 point (plan, section 13.C) -- discovery is NOT gated on NAMESPACE
   * being advertised: the probe here says it was not, and the walk runs all the same.
   */
  @Test
  void getReceivedDiscoversSharesAndMarksVanishedOnesGone() throws Exception {
    when(engine.probe(any())).thenReturn(MailboxAclCapabilities.imap(false, false));
    when(engine.listSharedMailboxes(any())).thenReturn(List.of(new SharedMailbox("carol", "Other Users/carol", "Other Users/carol/INBOX", "/")));
    when(engine.myRights(any(), eq("Other Users/carol/INBOX"))).thenReturn(MailboxRights.of("lrs"));
    EmailDelegation aliceRow = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    aliceRow.setRemoteRoot("Other Users/alice");
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(aliceRow));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of("carol"));
    when(userEmailSettingService.getUserEmailSetting("carol")).thenReturn(setting(CONNECTOR_ID, "carol@acme.com"));

    service.getReceivedDelegations(GRANTEE, true);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).listSharedMailboxes(session.capture());
    assertEquals(GRANTEE, session.getValue().username(), "the grantee's own session, NAMESPACE advertised or not");
    ArgumentCaptor<EmailDelegation> created = ArgumentCaptor.forClass(EmailDelegation.class);
    verify(emailDelegationStorage).create(created.capture());
    assertEquals(DelegationStatus.AVAILABLE, created.getValue().getStatus());
    assertEquals(DelegationOrigin.SERVER, created.getValue().getOrigin());
    assertEquals("carol", created.getValue().getOwnerId(), "mapped by local part");
    assertEquals("carol", created.getValue().getOwnerMailbox());
    assertEquals("Other Users/carol", created.getValue().getRemoteRoot());
    assertEquals(DelegationPreset.READER, created.getValue().getPreset());
    assertEquals("lrs", created.getValue().getNativeRights());
    assertEquals(DelegationStatus.GONE, aliceRow.getStatus());
    verify(emailFolderStorage, never()).createFolder(any());
    verify(engine, never()).subscribe(any(), any());
  }

  /**
   * Discovery is best-effort: an unreachable server leaves the rows as they are.
   */
  @Test
  void getReceivedToleratesAnUnreachableServer() throws Exception {
    when(userEmailSettingService.connect(anyString(), anyString())).thenThrow(new MessagingException("down"));
    when(engine.probe(any())).thenAnswer(invocation -> {
      invocation.getArgument(0, MailboxAclSession.class).store();
      return SUPPORTED;
    });
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(pending));

    List<EmailDelegation> received = service.getReceivedDelegations(GRANTEE, true);

    assertEquals(List.of(pending), received);
    assertEquals(List.of(pending), service.getReceivedDelegations(GRANTEE, false));
    verify(engine, never()).listSharedMailboxes(any());
  }

  /**
   * The toggles, a null leaving one as it is; scoped to the grantee.
   */
  @Test
  void updatePreferencesSetsOnlyWhatWasGiven() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    accepted.setNotifyNewMail(true);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(accepted);

    EmailDelegation stored = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.updatePreferences(GRANTEE, 100L, true, true)).thenReturn(stored);

    EmailDelegation updated = service.updatePreferences(GRANTEE, 100L, true, null);

    // #432-2: the two toggles alone, never the row read before -- a null keeps the
    // stored toggle, and the rest of the row is not written at all.
    assertSame(stored, updated);
    verify(emailDelegationStorage).updatePreferences(GRANTEE, 100L, true, true);
    verify(emailDelegationStorage, never()).update(any());
    when(emailDelegationStorage.getAsGrantee(OWNER, 100L)).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> service.updatePreferences(OWNER, 100L, true, true));
  }

  // ---------------------------------------------------------------------------------
  // The sync tier, and the rights guard the write paths ask (EXO-90499)
  // ---------------------------------------------------------------------------------

  /**
   * A folder key of the caller's OWN mailbox passes every guard untouched, and costs
   * not one query: the guard is "is this key delegated, and if so may I", never "is
   * this key mine", which is what lets it sit in front of every write path without
   * changing a thing for the mailbox the user owns.
   */
  @Test
  void anOwnFolderPassesEveryGuardWithoutAQuery() throws Exception {
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "INBOX", MailboxRights.DELETE_MESSAGES));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "SENT", MailboxRights.KEEP_SEEN));
    assertNull(service.delegationOf(GRANTEE, "INBOX"));
    assertNull(service.rightsOn(GRANTEE, "TRASH"));
    verify(emailDelegationStorage, never()).getAsGrantee(anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  /**
   * A custom key naming a folder of the caller's own mailbox (no delegation on the row)
   * is an own folder: one registry read, no delegation read, and nothing refused.
   */
  @Test
  void aCustomFolderOfTheOwnMailboxIsNotDelegated() throws Exception {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(ownFolder(5L));

    assertNull(service.delegationOf(GRANTEE, "CUSTOM:5"));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.DELETE_MESSAGES));
    verify(emailDelegationStorage, never()).getAsGrantee(anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  /**
   * <b>The guard.</b> A delegated folder whose rights lack the letter refuses, with the
   * letter in the message code so the interface can say which right is missing rather
   * than denying blankly. One case per letter the write paths ask for: {@code s} for
   * mark read/unread, {@code w} for the star, {@code t} for delete and move-out,
   * {@code i} for move-in.
   */
  @Test
  void aDelegatedFolderRefusesEveryLetterItsRightsLack() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(delegatedFolder(5L));
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(accepted("lr"));

    for (char letter : new char[] { MailboxRights.KEEP_SEEN, MailboxRights.WRITE, MailboxRights.DELETE_MESSAGES,
        MailboxRights.INSERT }) {
      MailboxRightMissingException refused =
                                           assertThrows(MailboxRightMissingException.class,
                                                        () -> service.checkRight(GRANTEE, "CUSTOM:5", letter),
                                                        "a reader holding lr may not " + letter);
      assertEquals(letter, refused.getRight());
      assertEquals(MailboxRightMissingException.CODE_PREFIX + letter, refused.getMessage());
    }
  }

  /**
   * The same folder, once the server grants the letters, allows them -- so the refusal
   * above is the rights talking and not the delegation itself.
   */
  @Test
  void aDelegatedFolderAllowsTheLettersItsRightsCarry() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(delegatedFolder(5L));
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(accepted("lrswit"));

    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.KEEP_SEEN));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.WRITE));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.DELETE_MESSAGES));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.INSERT));
    assertEquals("lrswit", service.rightsOn(GRANTEE, "CUSTOM:5").letters());
  }

  /**
   * An editor's letters on a share that is no longer ACCEPTED answer the OTHER
   * exception, and the difference matters to the interface: "this mailbox is gone,
   * leave it" is not "you may not do that here".
   */
  @Test
  void aShareThatIsNoLongerAcceptedIsGoneRatherThanRefused() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(delegatedFolder(5L));
    EmailDelegation revoked = accepted("lrswit");
    revoked.setStatus(DelegationStatus.REVOKED);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(revoked);

    DelegationRevokedException gone = assertThrows(DelegationRevokedException.class,
                                                   () -> service.checkRight(GRANTEE, "CUSTOM:5", MailboxRights.DELETE_MESSAGES));
    assertEquals(DelegationRevokedException.REVOKED, gone.getMessage());
  }

  /**
   * A folder row that is not the caller's, or a delegation row that is not theirs,
   * resolves to nothing -- ids enumerate nothing here either.
   */
  @Test
  void aKeyThatIsNotTheCallersResolvesToNothing() throws Exception {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(null);
    assertNull(service.delegationOf(GRANTEE, "CUSTOM:5"));

    when(emailFolderStorage.getFolder(GRANTEE, 6L)).thenReturn(delegatedFolder(6L));
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(null);
    assertNull(service.delegationOf(GRANTEE, "CUSTOM:6"));
    assertDoesNotThrow(() -> service.checkRight(GRANTEE, "CUSTOM:6", MailboxRights.DELETE_MESSAGES));
  }

  /**
   * Creating, renaming, deleting a folder and toggling its opt-in are the mailbox
   * owner's, addressed by registry id: a delegated row is refused outright rather than
   * by a letter, because a server-made share that happened to carry {@code x} would
   * otherwise let a delegate rename or destroy somebody else's folder from the screen
   * that manages their own.
   */
  @Test
  void folderManagementRefusesADelegatedRowWhateverItsLetters() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(delegatedFolder(5L));

    MailboxRightMissingException refused = assertThrows(MailboxRightMissingException.class,
                                                        () -> service.checkOwnFolder(GRANTEE, 5L));
    assertEquals(MailboxRights.DELETE_MAILBOX, refused.getRight());

    when(emailFolderStorage.getFolder(GRANTEE, 6L)).thenReturn(ownFolder(6L));
    assertDoesNotThrow(() -> service.checkOwnFolder(GRANTEE, 6L));
  }

  /**
   * The sync tier: only the accepted shares the delegate has opened recently are handed
   * to the sync, and only the folders they opted in and the server still lists. A share
   * accepted and never opened costs nothing at all -- no slow tier, nothing.
   */
  @Test
  void onlyTheSharesTheDelegateIsInAndTheFoldersTheyOptedInAreSynced() {
    Date activeSince = new Date(1_000L);
    when(emailDelegationStorage.getActive(GRANTEE, activeSince)).thenReturn(List.of(accepted("lrswit")));
    EmailFolder optedIn = delegatedFolder(5L);
    optedIn.setSyncEnabled(true);
    EmailFolder optedOut = delegatedFolder(6L);
    EmailFolder gone = delegatedFolder(7L);
    gone.setSyncEnabled(true);
    gone.setMissing(true);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(optedIn, optedOut, gone));

    assertEquals(1, service.getActiveDelegations(GRANTEE, activeSince).size());
    List<EmailFolder> syncable = service.getSyncableFolders(GRANTEE, 100L);
    assertEquals(1, syncable.size(), "the opted-in, present folder and nothing else");
    assertEquals(5L, syncable.get(0).getId());

    assertTrue(service.getActiveDelegations(GRANTEE, null).isEmpty());
    assertTrue(service.getActiveDelegations(null, activeSince).isEmpty());
  }

  /**
   * Listing a delegated folder stamps that share and nothing else; listing an own
   * folder writes nothing at all. The stamp carries the same throttle threshold the
   * mailbox's own activity stamp uses, applied in SQL so it holds across nodes.
   */
  @Test
  void listingADelegatedFolderStampsThatShareAndAnOwnFolderStampsNothing() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenReturn(delegatedFolder(5L));
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(accepted("lrswit"));

    service.touchActivity(GRANTEE, "CUSTOM:5");
    ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
    ArgumentCaptor<Date> throttle = ArgumentCaptor.forClass(Date.class);
    verify(emailDelegationStorage).touchActivity(eq(GRANTEE), eq(100L), now.capture(), throttle.capture());
    assertTrue(throttle.getValue().before(now.getValue()), "the throttle threshold is in the past");

    service.touchActivity(GRANTEE, "INBOX");
    verify(emailDelegationStorage, org.mockito.Mockito.times(1)).touchActivity(anyString(),
                                                                              org.mockito.ArgumentMatchers.anyLong(),
                                                                              any(),
                                                                              any());
  }

  /**
   * A stamp that fails is swallowed: a listing must never fail because a stamp did, and
   * the cost of a lost one is a sync period of a shared mailbox, not the mail.
   */
  @Test
  void aFailedStampNeverFailsTheListing() {
    when(emailFolderStorage.getFolder(GRANTEE, 5L)).thenThrow(new IllegalStateException("the database is away"));

    assertDoesNotThrow(() -> service.touchActivity(GRANTEE, "CUSTOM:5"));
  }

  /**
   * A folder row of the caller's own mailbox.
   *
   * @param id the registry id
   * @return the row
   */
  private EmailFolder ownFolder(long id) {
    EmailFolder folder = new EmailFolder();
    folder.setId(id);
    folder.setUserId(GRANTEE);
    folder.setType(MailFolderView.TYPE_CUSTOM);
    folder.setRemoteName("Customers");
    return folder;
  }

  /**
   * A folder row of the shared mailbox of delegation 100.
   *
   * @param id the registry id
   * @return the row
   */
  private EmailFolder delegatedFolder(long id) {
    EmailFolder folder = new EmailFolder();
    folder.setId(id);
    folder.setUserId(GRANTEE);
    folder.setType(MailFolderView.TYPE_DELEGATED_INBOX);
    folder.setRemoteName("Other Users/alice/INBOX");
    folder.setDelegationId(100L);
    return folder;
  }

  /**
   * An accepted share of Alice's mailbox with the given letters.
   *
   * @param letters what the server grants Bob there
   * @return the row
   */
  private EmailDelegation accepted(String letters) {
    EmailDelegation delegation = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    delegation.setRights(letters);
    return delegation;
  }

  /**
   * A connected setting.
   *
   * @param connectorId the preset
   * @param address the login
   * @return the setting
   */
  private UserEmailSetting setting(long connectorId, String address) {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(connectorId));
    setting.setEmailAddress(address);
    setting.setEmailPassword("secret");
    return setting;
  }

  /**
   * Bob's row on Alice's mailbox, in a state.
   *
   * @param status the state
   * @param origin who wrote the grant
   * @return the row, id 100
   */
  private EmailDelegation row(DelegationStatus status, DelegationOrigin origin) {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(100L);
    delegation.setGranteeId(GRANTEE);
    delegation.setOwnerId(OWNER);
    delegation.setOwnerMailbox(OWNER_MAILBOX);
    delegation.setGranteeMailbox(GRANTEE_MAILBOX);
    delegation.setConnectorId(CONNECTOR_ID);
    delegation.setPreset(DelegationPreset.EDITOR);
    delegation.setRights("lrswit");
    delegation.setStatus(status);
    delegation.setOrigin(origin);
    return delegation;
  }
}
