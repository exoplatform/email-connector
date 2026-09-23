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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.exoplatform.emailConnector.event.DelegatedFoldersDroppedEvent;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderMessageCounts;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.SharedMailboxFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
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
  private EmailBoxStorage                     emailBoxStorage;

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
    // By default the ACL cannot be read back after a grant, so a grant keeps what it
    // wrote; the tests of the read-back (EXO-90548) stub it.
    lenient().when(engine.listAcl(any(), eq(INBOX))).thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "GETACL"));
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
    System.clearProperty(EmailDelegationService.MAX_FOLDERS_PROPERTY);
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
   * An owner whose MYRIGHTS carries no {@code a} is <b>not</b> refused: the grant is
   * attempted and the server decides. This replaces a precondition that was wrong on
   * the first real server it met -- Stalwart 0.11.8 answers {@code rliteswkxp} to the
   * owner of that very mailbox, with no {@code a} anywhere, and then accepts her
   * SETACL. The refusal told the owner of a mailbox that she could not share her own
   * mailbox, on a server that was perfectly willing.
   * <p>
   * It is the capability probe's lesson in a second place: an advertisement is a
   * positive signal, never a precondition, and the command is the test. A server that
   * genuinely refuses answers the SETACL, and that refusal is what the user reads.
   */
  @Test
  void inviteAttemptsTheGrantEvenWhenTheOwnerHoldsNoAdministerRight() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("rliteswkxp"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any()))
                                                                                                 .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                  MailboxRights.of("lrs")));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.READER);

    verify(engine).grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any());
    assertEquals("lrs", delegation.getRights(), "what the server wrote, on a server that never claimed the owner could");
  }

  /**
   * And when the server does refuse the SETACL, that refusal is what surfaces -- with
   * the server's own code, not a guess made before asking.
   */
  @Test
  void inviteReportsTheServersOwnRefusalOfTheGrant() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("rliteswkxp"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any()))
                                                                          .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                             "SETACL refused"));

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.READER));

    assertEquals(MailboxAclException.SERVER_REFUSED, thrown.getCode());
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
   * Stack review #441-1 -- a share left, revoked or found withdrawn stops counting in the
   * badge for good: taken up again, it must be chosen again, as a new one is.
   */
  @Test
  void aShareThatEndsNoLongerCountsInTheBadge() throws Exception {
    EmailDelegation left = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    left.setBadgeIncluded(true);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(left);
    service.leave(GRANTEE, 100L);
    assertFalse(left.isBadgeIncluded(), "leave");

    EmailDelegation revoked = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    revoked.setBadgeIncluded(true);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(revoked);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    service.revoke(OWNER, 100L);
    assertFalse(revoked.isBadgeIncluded(), "revoke");

    EmailDelegation withdrawn = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    withdrawn.setBadgeIncluded(true);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(withdrawn);
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(null);
    assertThrows(DelegationRevokedException.class, () -> service.accept(GRANTEE, 100L));
    assertFalse(withdrawn.isBadgeIncluded(), "found withdrawn");
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
   * Stack review #437-1 -- every way a share's folders are dropped takes the mail
   * mirrored under them too: revoke, leave, a disconnect, and a withdrawal found on the
   * server (here at accept). The keys are read before the rows go, and handed to the
   * mailbox cache on the event.
   */
  @Test
  void everyDropOfASharesFoldersPurgesTheirMail() throws Exception {
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(delegatedFolder(12L), delegatedFolder(13L)));
    List<String> keys = List.of("CUSTOM:12", "CUSTOM:13");

    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));
    when(engine.probe(any())).thenReturn(SUPPORTED);
    service.revoke(OWNER, 100L);
    assertDropped(keys);

    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));
    service.leave(GRANTEE, 100L);
    assertDropped(keys);

    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO)));
    service.endReceivedShares(GRANTEE);
    assertDropped(keys);

    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.PENDING, DelegationOrigin.EXO));
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(null);
    assertThrows(DelegationRevokedException.class, () -> service.accept(GRANTEE, 100L));
    assertDropped(keys);
  }

  /**
   * The last drop published the dropped keys, read before the folder rows were deleted.
   *
   * @param keys the keys expected
   */
  private void assertDropped(List<String> keys) {
    InOrder order = inOrder(emailFolderStorage, eventPublisher);
    order.verify(emailFolderStorage).getDelegatedFolders(GRANTEE, 100L);
    order.verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 100L);
    ArgumentCaptor<DelegatedFoldersDroppedEvent> dropped = ArgumentCaptor.forClass(DelegatedFoldersDroppedEvent.class);
    order.verify(eventPublisher).publishEvent(dropped.capture());
    assertEquals(new DelegatedFoldersDroppedEvent(GRANTEE, keys), dropped.getValue());
    clearInvocations(emailFolderStorage, eventPublisher);
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
  // A shared mailbox's folders (EXO-90548)
  // ---------------------------------------------------------------------------------

  /**
   * On a per-folder server the grant covers, beside INBOX, the owner's Sent, Archive,
   * Trash and Spam as the owner's session names them, each granted with its role; a
   * folder the server refuses does not undo the share, and the row says what was shared
   * and what could not be.
   */
  @Test
  void inviteSharesTheOwnersRoleFoldersBesideInbox() throws Exception {
    givenAGrantableInbox(DelegationPreset.EDITOR);
    when(engine.findRoleFolders(any())).thenReturn(ownerRoleFolders());
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswipkxtea"));
    lenient().when(engine.grant(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.TRASH)))
                                                                                                                             .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                                                "NO"));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR);

    verify(engine).grant(any(), eq("Sent"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.SENT));
    verify(engine).grant(any(), eq("Archive"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.ARCHIVE));
    verify(engine).grant(any(), eq("Spam"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.JUNK));
    verify(engine, never()).grant(any(), eq("Drafts"), any(), any(), any(), any());
    assertEquals("INBOX,SENT,ARCHIVE,JUNK", delegation.getGrantedRoles());
    assertEquals(List.of(FolderRole.TRASH), delegation.getRolesNotShared());
    assertEquals(ownerRoleFolders(), delegation.getOwnerRoleFolders());
    assertFalse(delegation.isInboxOnly());
  }

  /**
   * On a server that grants a whole mailbox at once (BlueMind), the one grant covers
   * every folder: no folder is looked for, none granted again.
   */
  @Test
  void inviteOnAPerMailboxServerGrantsOnce() throws Exception {
    givenAGrantableInbox(DelegationPreset.READER);
    when(engine.probe(any())).thenReturn(new MailboxAclCapabilities(true, true, true, GrantGranularity.MAILBOX, false, false, null));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.READER);

    assertEquals(EmailDelegation.GRANTED_WHOLE_MAILBOX, delegation.getGrantedRoles());
    assertTrue(delegation.grantsWholeMailbox());
    assertEquals(List.of(), delegation.getRolesNotShared());
    verify(engine, never()).findRoleFolders(any());
    verify(engine, never()).grant(any(), any(), any(), any(), any(), any());
  }

  /**
   * The grant is read back: the letters recorded are the server's; a server that
   * accepted the SETACL and does not name the grantee afterwards shared nothing, and the
   * owner is told -- no row, nothing else granted.
   */
  @Test
  void inviteRecordsWhatTheServerHoldsAndRefusesAGrantItDidNotRecord() throws Exception {
    givenAGrantableInbox(DelegationPreset.EDITOR);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX.toUpperCase(Locale.ROOT), MailboxRights.of("lrswite")),
                                                              MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea"))));

    EmailDelegation delegation = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR);
    assertEquals("lrswite", delegation.getRights(), "the server's letters, read back");
    assertEquals(DelegationPreset.EDITOR, delegation.getPreset());

    when(emailDelegationStorage.getByKey(GRANTEE, CONNECTOR_ID, OWNER_MAILBOX)).thenReturn(null);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea"))));
    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR));
    assertEquals(MailboxAclException.NOT_RECORDED, thrown.getCode());
    verify(emailDelegationStorage, times(1)).create(any());
    verify(engine, times(1)).findRoleFolders(any());
  }

  /**
   * Narrowing to Reader follows on every shared folder; a folder that refuses the
   * narrower letters loses the access instead of keeping the wider one.
   */
  @Test
  void changePresetNarrowsEverySharedFolderOrRemovesIt() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    givenTheOwnersRowToChange(accepted);
    lenient().when(engine.grant(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.TRASH)))
                                                                                                                             .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                                                "NO"));

    EmailDelegation changed = service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine).grant(any(), eq("Sent"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.SENT));
    verify(engine).revoke(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX));
    assertEquals("INBOX,SENT", changed.getGrantedRoles(), "Trash no longer shared rather than shared wider");
  }

  /**
   * A folder that refuses both the narrower letters and the removal keeps the wider
   * access: the owner is told, after the rest is recorded.
   */
  @Test
  void changePresetSaysSoWhenAFolderKeepsTheWiderAccess() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    givenTheOwnersRowToChange(accepted);
    lenient().when(engine.grant(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.TRASH)))
                                                                                                                             .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                                                "NO"));
    lenient().doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).revoke(any(), eq("Corbeille"), any());

    MailboxAclException thrown = assertThrows(MailboxAclException.class, () -> service.changePreset(OWNER, 100L, DelegationPreset.READER));

    assertEquals(MailboxAclException.NOT_NARROWED, thrown.getCode());
    verify(emailDelegationStorage, never()).update(any());
    verify(emailDelegationStorage).updateGrantedRights(eq(OWNER), eq(100L), any(), any(), any(), any(), any(), eq("INBOX,SENT,TRASH"), any());
    assertEquals("INBOX,SENT,TRASH", accepted.getGrantedRoles(), "Trash still shared, and recorded as such");
  }

  /**
   * "Extend access" does not revive a share the owner removed in another mail
   * application: the INBOX ACL no longer naming the grantee, nothing is written.
   */
  @Test
  void extendDoesNotReviveAShareRemovedElsewhere() throws Exception {
    EmailDelegation inboxOnly = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(inboxOnly);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea"))));

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.extend(OWNER, 100L)).getMessage());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * A server that accepts the INBOX grant and does not record it (the Dovecot login
   * shape) stops the extension there: nothing is shared beside it.
   */
  @Test
  void extendRefusesWhenTheServerDoesNotRecordTheInboxGrant() throws Exception {
    EmailDelegation inboxOnly = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(inboxOnly);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), any(), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite")));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit"))),
                                                      List.of(MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea"))));

    assertEquals(MailboxAclException.NOT_RECORDED,
                 assertThrows(MailboxAclException.class, () -> service.extend(OWNER, 100L)).getCode());
    verify(engine, never()).grant(any(), any(), any(), any(), any(), any());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * When a role now names another folder while the one eXo granted still exists (the
   * server made "Deleted Items" the Trash), narrowing also narrows the folder eXo
   * granted -- it would otherwise keep the Editor entry, with nobody told.
   */
  @Test
  void changePresetAlsoNarrowsTheFolderARoleMovedAwayFrom() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    givenTheOwnersRowToChange(accepted);
    Map<FolderRole, String> moved = new EnumMap<>(ownerRoleFolders());
    moved.put(FolderRole.TRASH, "Deleted Items");
    when(engine.findRoleFolders(any())).thenReturn(moved);
    when(engine.listAcl(any(), eq("Corbeille"))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit"))));

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine).grant(any(), eq("Deleted Items"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.TRASH));
    verify(engine).grant(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.TRASH));
  }

  /**
   * A role folder eXo's grant never covered (refused at the grant, or Drafts) is not
   * touched when the role moves: whatever was decided for it elsewhere stays.
   */
  @Test
  void changePresetLeavesAFolderEXoNeverSharedAlone() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    accepted.setGrantedRoles("INBOX,SENT");
    givenTheOwnersRowToChange(accepted);
    Map<FolderRole, String> moved = new EnumMap<>(ownerRoleFolders());
    moved.put(FolderRole.TRASH, "Deleted Items");
    moved.put(FolderRole.DRAFTS, "Brouillons");
    when(engine.findRoleFolders(any())).thenReturn(moved);

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine, never()).listAcl(any(), eq("Corbeille"));
    verify(engine, never()).listAcl(any(), eq("Drafts"));
    verify(engine, never()).grant(any(), eq("Corbeille"), any(), any(), any(), any());
    verify(engine, never()).revoke(any(), eq("Corbeille"), any());
  }

  /**
   * And when the folder eXo granted refuses both the narrower letters and the removal,
   * the owner is told.
   */
  @Test
  void changePresetSaysSoWhenTheFolderARoleMovedAwayFromStaysWider() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    givenTheOwnersRowToChange(accepted);
    Map<FolderRole, String> moved = new EnumMap<>(ownerRoleFolders());
    moved.put(FolderRole.TRASH, "Deleted Items");
    when(engine.findRoleFolders(any())).thenReturn(moved);
    when(engine.listAcl(any(), eq("Corbeille"))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit"))));
    lenient().when(engine.grant(any(), eq("Corbeille"), any(), any(), any(), any()))
             .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO"));
    lenient().doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).revoke(any(), eq("Corbeille"), any());

    assertEquals(MailboxAclException.NOT_NARROWED,
                 assertThrows(MailboxAclException.class, () -> service.changePreset(OWNER, 100L, DelegationPreset.READER)).getCode());
  }

  /**
   * Narrowing writes the owner's folders as they are named now: a Trash renamed since the
   * grant is narrowed under its new name, and the row keeps that name.
   */
  @Test
  void changePresetNarrowsAFolderUnderItsCurrentName() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    givenTheOwnersRowToChange(accepted);
    Map<FolderRole, String> renamed = new EnumMap<>(ownerRoleFolders());
    renamed.put(FolderRole.TRASH, "Deleted Items");
    when(engine.findRoleFolders(any())).thenReturn(renamed);

    EmailDelegation changed = service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine).grant(any(), eq("Deleted Items"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.TRASH));
    verify(engine, never()).grant(any(), eq("Corbeille"), any(), any(), any(), any());
    assertEquals("Deleted Items", changed.getOwnerRoleFolders().get(FolderRole.TRASH));
  }

  /**
   * "Remove access" removes every entry of the grantee on the owner's folders -- the
   * ones the server lists, one written in another application included, and the ones the
   * grant recorded -- INBOX once; a folder that refuses does not stop it.
   */
  @Test
  void revokeRemovesTheGranteeFromEveryFolderOfTheOwner() throws Exception {
    EmailDelegation accepted = aRowSharingRoleFolders();
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.foldersHolding(any(), eq(GRANTEE_MAILBOX))).thenReturn(List.of("INBOX", "Sent", "Projects"));
    lenient().doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).revoke(any(), eq("Projects"), any());

    service.revoke(OWNER, 100L);

    verify(engine, times(1)).revoke(any(), eq(INBOX), eq(GRANTEE_MAILBOX));
    verify(engine).revoke(any(), eq("Sent"), eq(GRANTEE_MAILBOX));
    verify(engine).revoke(any(), eq("Projects"), eq(GRANTEE_MAILBOX));
    verify(engine).revoke(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX));
    assertEquals(DelegationStatus.REVOKED, accepted.getStatus());
  }

  /**
   * "Extend access": a share written before roles were recorded gets the owner's role
   * folders with its own preset; only the owner's, only a share eXo wrote, only on a
   * per-folder server.
   */
  @Test
  void extendSharesTheRoleFoldersAPhaseOneShareLeftOut() throws Exception {
    EmailDelegation inboxOnly = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(inboxOnly);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.findRoleFolders(any())).thenReturn(ownerRoleFolders());
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any()))
                                                                                                 .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                  MailboxRights.of("lrswite")));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite"))));
    assertTrue(inboxOnly.isInboxOnly());
    answerTheRightsAndRolesWriteOn(inboxOnly);

    EmailDelegation extended = service.extend(OWNER, 100L);

    // INBOX to today's Editor letters first: a phase-1 Editor held no e there.
    verify(engine).grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any());
    assertEquals("lrswite", extended.getRights());
    for (FolderRole role : FolderRole.GRANTED) {
      verify(engine).grant(any(), eq(ownerRoleFolders().get(role)), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(role));
    }
    assertEquals("INBOX,SENT,ARCHIVE,TRASH,JUNK", extended.getGrantedRoles());
    verify(emailDelegationStorage, never()).update(any());

    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.SERVER));
    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.extend(OWNER, 100L)).getMessage(),
                 "a share made in the server's own interface is never rewritten");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));
    when(engine.probe(any())).thenReturn(new MailboxAclCapabilities(true, true, true, GrantGranularity.MAILBOX, false, false, null));
    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.extend(OWNER, 100L)).getMessage(),
                 "a per-mailbox grant already covers every folder");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> service.extend(OWNER, 100L));
  }

  /**
   * Stack review N-1 on "Extend access": the grantee leaves while the owner's grants are
   * on the wire. Only what the grants wrote is recorded, so the leave stands -- the
   * answer is the row as it now is, never the pre-grant read written back over it.
   */
  @Test
  void aLeaveDuringAnExtendStandsAfterIt() throws Exception {
    EmailDelegation inboxOnly = anExtendableShare();
    EmailDelegation left = row(DelegationStatus.DECLINED, DelegationOrigin.EXO);
    left.setBadgeIncluded(false);
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER),
                                                    eq(100L),
                                                    eq(DelegationPreset.EDITOR),
                                                    eq("lrswite"),
                                                    any(),
                                                    eq(GRANTEE_MAILBOX),
                                                    any(),
                                                    eq("INBOX,SENT,ARCHIVE,TRASH,JUNK"),
                                                    any())).thenReturn(left);

    EmailDelegation extended = service.extend(OWNER, 100L);

    assertEquals(DelegationStatus.DECLINED, extended.getStatus(), "the leave stands");
    assertFalse(extended.isBadgeIncluded());
    assertEquals(DelegationStatus.ACCEPTED, inboxOnly.getStatus(), "the stale read is not what was written");
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * A share revoked or gone while the owner's grants were on the wire is not written,
   * and the owner is told it is not changeable.
   */
  @Test
  void aShareThatEndedDuringAnExtendIsRefused() throws Exception {
    anExtendableShare();
    when(emailDelegationStorage.updateGrantedRights(any(), anyLong(), any(), any(), any(), any(), any(), anyString(), any()))
                                                                                                                         .thenReturn(null);

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.extend(OWNER, 100L)).getMessage());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * An accepted phase-1 Editor share of INBOX only, on a per-folder server that grants
   * every folder and still names the grantee on INBOX.
   *
   * @return the row the owner extends
   */
  private EmailDelegation anExtendableShare() throws Exception {
    EmailDelegation inboxOnly = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(inboxOnly);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.findRoleFolders(any())).thenReturn(ownerRoleFolders());
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any()))
                                                                                                 .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                  MailboxRights.of("lrswite")));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite"))));
    return inboxOnly;
  }

  /**
   * The owner's folders by role, as the owner's session names them.
   *
   * @return the map
   */
  private Map<FolderRole, String> ownerRoleFolders() {
    Map<FolderRole, String> roles = new EnumMap<>(FolderRole.class);
    roles.put(FolderRole.SENT, "Sent");
    roles.put(FolderRole.ARCHIVE, "Archive");
    roles.put(FolderRole.TRASH, "Corbeille");
    roles.put(FolderRole.JUNK, "Spam");
    roles.put(FolderRole.DRAFTS, "Drafts");
    return roles;
  }

  /**
   * An accepted Editor share whose grant covered INBOX, Sent and Trash.
   *
   * @return the row
   */
  private EmailDelegation aRowSharingRoleFolders() {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    accepted.setGrantedRoles("INBOX,SENT,TRASH");
    accepted.setOwnerRoleFolders(ownerRoleFolders());
    return accepted;
  }

  /**
   * The owner's row to change, on a per-folder server that answers every grant.
   *
   * @param row the row
   */
  private void givenTheOwnersRowToChange(EmailDelegation row) {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(row);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                           MailboxRights.of("lrs")));
    answerTheRightsAndRolesWriteOn(row);
  }

  /**
   * The targeted rights-and-roles write (stack review N-1), answered as the storage
   * does: the written columns on the row, the rest of it as it stands.
   *
   * @param row the row the write lands on
   */
  private void answerTheRightsAndRolesWriteOn(EmailDelegation row) {
    lenient().when(emailDelegationStorage.updateGrantedRights(eq(OWNER),
                                                              eq(100L),
                                                              any(),
                                                              any(),
                                                              any(),
                                                              any(),
                                                              any(),
                                                              anyString(),
                                                              any()))
             .thenAnswer(invocation -> {
               row.setPreset(invocation.getArgument(2));
               row.setRights(invocation.getArgument(3));
               row.setNativeRights(invocation.getArgument(4));
               row.setGranteeMailbox(invocation.getArgument(5));
               row.setGrantedRoles(invocation.getArgument(7));
               row.setOwnerRoleFolders(invocation.getArgument(8));
               return row;
             });
  }

  /**
   * An owner's INBOX the engine grants a preset on.
   *
   * @param preset the preset asked
   */
  private void givenAGrantableInbox(DelegationPreset preset) {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    lenient().when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(preset), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                preset.rights()));
  }

  // ---------------------------------------------------------------------------------
  // The delegate's discovery of a shared mailbox's folders (EXO-90548)
  // ---------------------------------------------------------------------------------

  /**
   * Discovery registers every folder listed under the share's root, with its role and
   * the delegate's own letters: the role from the owner's map first -- the delegate's
   * Dovecot listing shows no special-use -- then the attribute shown, then the usual name
   * of a direct child only; a folder listed without {@code r} is registered but never
   * synced; the root's INBOX is not registered again and is stamped.
   */
  @Test
  void discoveryRegistersEachFolderWithItsRoleAndItsOwnLetters() {
    EmailDelegation share = aDovecotShare();
    EmailFolder inbox = sharedInbox(share);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox));
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenReturn(List.of(listed(ROOT),
                                                                               listed(ROOT + "/Corbeille"),
                                                                               listed(ROOT + "/Sent"),
                                                                               listed(ROOT + "/Old", "\\Archive"),
                                                                               listed(ROOT + "/Projects/Spam"),
                                                                               listed(ROOT + "/Private")));
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrswite"));
    when(engine.myRights(any(), eq(ROOT + "/Corbeille"))).thenReturn(MailboxRights.of("lrswit"));
    when(engine.myRights(any(), eq(ROOT + "/Private"))).thenReturn(MailboxRights.of("l"));
    Map<String, EmailFolder> created = givenCreatedFolders();

    List<EmailFolder> purged = service.discoverDelegatedFolders(GRANTEE, share, engine, session(), true);

    assertEquals(Set.of(ROOT + "/Corbeille", ROOT + "/Sent", ROOT + "/Old", ROOT + "/Projects/Spam", ROOT + "/Private"), created.keySet(),
                 "the root is the INBOX, registered already");
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(id(created, "Corbeille")), eq(100L), eq(FolderRole.TRASH), eq("lrswit"), any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(id(created, "Sent")), eq(100L), eq(FolderRole.SENT), eq("lrswite"), any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(id(created, "Old")), eq(100L), eq(FolderRole.ARCHIVE), any(), any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(id(created, "Projects/Spam")), eq(100L), isNull(), any(), any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(id(created, "Private")), eq(100L), isNull(), eq("l"), any());
    verify(emailFolderStorage, never()).updateSyncEnabled(eq(GRANTEE), eq(id(created, "Private")), eq(true), any());
    verify(emailFolderStorage).updateSyncEnabled(eq(GRANTEE), eq(id(created, "Sent")), eq(true), any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(inbox.getId()), eq(100L), isNull(), eq("lrswite"), any());
    assertEquals(List.of(), purged);
  }

  /**
   * A folder the owner no longer shares is marked missing at the first discovery that
   * does not list it and dropped at the second -- its mirror with it; a folder whose
   * letters lost {@code r} stops syncing and loses its mirror; one the delegate's own
   * walk registered before shared trees were kept out of it is adopted, not duplicated.
   */
  @Test
  void discoveryRetiresAFolderAfterOneGraceWalkAndAdoptsAMisregisteredOne() {
    EmailDelegation share = aDovecotShare();
    EmailFolder inbox = sharedInbox(share);
    EmailFolder goneOnce = delegated(21L, ROOT + "/Archive", false);
    EmailFolder goneTwice = delegated(22L, ROOT + "/Old", false);
    goneTwice.setMissing(true);
    EmailFolder unreadable = delegated(23L, ROOT + "/Sent", true);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox, goneOnce, goneTwice, unreadable));
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenReturn(List.of(listed(ROOT + "/Sent"), listed(ROOT + "/Trash")));
    when(engine.myRights(any(), eq(ROOT + "/Sent"))).thenReturn(MailboxRights.of("l"));
    when(engine.myRights(any(), eq(ROOT + "/Trash"))).thenReturn(MailboxRights.of("lrs"));
    EmailFolder ownRow = new EmailFolder();
    ownRow.setId(30L);
    ownRow.setRemoteName(ROOT + "/Trash");
    when(emailFolderStorage.getFolderByRemoteName(GRANTEE, ROOT + "/Trash")).thenReturn(ownRow);
    EmailFolder adopted = delegated(30L, ROOT + "/Trash", false);
    when(emailFolderStorage.getFolder(GRANTEE, 30L)).thenReturn(adopted);

    List<EmailFolder> purged = service.discoverDelegatedFolders(GRANTEE, share, engine, session(), true);

    verify(emailFolderStorage).markMissing(GRANTEE, 21L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 21L);
    verify(emailFolderStorage).deleteFolder(GRANTEE, 22L);
    verify(emailFolderStorage).updateSyncEnabled(eq(GRANTEE), eq(23L), eq(false), any());
    verify(emailFolderStorage).adoptAsDelegated(GRANTEE, 30L, 100L, MailFolderView.TYPE_DELEGATED);
    verify(emailFolderStorage, never()).createFolder(any());
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(30L), eq(100L), eq(FolderRole.TRASH), eq("lrs"), any());
    assertEquals(Set.of(22L, 23L), purged.stream().map(EmailFolder::getId).collect(Collectors.toSet()));
  }

  /**
   * The periodic pass discovers at most every quarter-hour per share, and a lost
   * connection changes nothing.
   */
  @Test
  void discoveryIsThrottledAndALostConnectionChangesNothing() {
    EmailDelegation share = aDovecotShare();
    EmailFolder inbox = sharedInbox(share);
    inbox.setRightsCheckDate(new Date(System.currentTimeMillis() - 60_000L));
    EmailFolder sent = delegated(21L, ROOT + "/Sent", true);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox, sent));

    assertEquals(List.of(), service.discoverDelegatedFolders(GRANTEE, share, engine, session(), false));
    verify(engine, never()).listFoldersUnder(any(), any(), any());

    inbox.setRightsCheckDate(new Date(System.currentTimeMillis() - EmailDelegationService.DISCOVERY_INTERVAL_MS - 1));
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenReturn(List.of(listed(ROOT + "/Sent")));
    when(engine.myRights(any(), anyString())).thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE, "down"));
    assertThrows(MailboxAclException.class, () -> service.discoverDelegatedFolders(GRANTEE, share, engine, session(), false));
    verify(emailFolderStorage, never()).markMissing(any(), anyLong());
    verify(emailFolderStorage, never()).updateDelegatedRights(any(), anyLong(), anyLong(), any(), any(), any());
  }

  /**
   * The cap keeps the roles first, then the rest by name.
   */
  @Test
  void discoveryKeepsTheRolesFirstUnderTheCap() {
    System.setProperty(EmailDelegationService.MAX_FOLDERS_PROPERTY, "2");
    EmailDelegation share = aDovecotShare();
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(sharedInbox(share)));
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenReturn(List.of(listed(ROOT + "/Aardvark"),
                                                                               listed(ROOT + "/Corbeille"),
                                                                               listed(ROOT + "/Sent")));
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrs"));
    Map<String, EmailFolder> created = givenCreatedFolders();

    service.discoverDelegatedFolders(GRANTEE, share, engine, session(), true);

    assertEquals(Set.of(ROOT + "/Corbeille", ROOT + "/Sent"), created.keySet());
  }

  /**
   * The guard reads THAT folder's letters: an Editor may delete from the shared INBOX
   * ({@code e}) and not expunge the owner's Trash; a Reader's Trash is read-only.
   */
  @Test
  void theGuardReadsTheFoldersOwnLetters() throws Exception {
    EmailDelegation share = aDovecotShare();
    share.setRights("lrswite");
    EmailFolder inbox = sharedInbox(share);
    EmailFolder trash = delegated(21L, ROOT + "/Corbeille", true);
    trash.setRole(FolderRole.TRASH);
    trash.setRights("lrswit");
    trash.setRightsCheckDate(new Date());
    when(emailFolderStorage.getFolder(GRANTEE, inbox.getId())).thenReturn(inbox);
    when(emailFolderStorage.getFolder(GRANTEE, 21L)).thenReturn(trash);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(share);

    service.checkRight(GRANTEE, inbox.getKey(), 'e');
    service.checkRight(GRANTEE, trash.getKey(), 't');
    assertThrows(MailboxRightMissingException.class, () -> service.checkRight(GRANTEE, trash.getKey(), 'e'));
    assertEquals("lrswit", service.rightsOn(GRANTEE, trash.getKey()).letters());
    assertEquals("lrswite", service.rightsOn(GRANTEE, inbox.getKey()).letters());
  }

  /**
   * A folder discovery read with no letter at all holds no right -- on Oracle the empty
   * letters come back null, and must not read as the share's own; a folder discovery has
   * not read yet knows no better than the share.
   */
  @Test
  void aFolderReadWithNoLetterHoldsNoRightOnEveryDatabase() {
    EmailDelegation share = aDovecotShare();
    EmailFolder read = delegated(21L, ROOT + "/Private", false);
    read.setRights(null);
    read.setRightsCheckDate(new Date());
    EmailFolder unread = delegated(22L, ROOT + "/New", false);
    when(emailFolderStorage.getFolder(GRANTEE, 21L)).thenReturn(read);
    when(emailFolderStorage.getFolder(GRANTEE, 22L)).thenReturn(unread);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(share);

    assertEquals("", service.rightsOn(GRANTEE, read.getKey()).letters());
    assertThrows(MailboxRightMissingException.class, () -> service.checkRight(GRANTEE, read.getKey(), 'r'));
    assertEquals("lrswite", service.rightsOn(GRANTEE, unread.getKey()).letters());
  }

  /**
   * An adopted row is listed right now: seen, whatever the delegate's own walk marked.
   * And a server that refuses the listing is asked again in a quarter-hour, not every
   * pass; one that cannot be reached is asked again next pass.
   */
  @Test
  void anAdoptedRowIsSeenAndARefusedListingHoldsTheThrottle() {
    EmailDelegation share = aDovecotShare();
    EmailFolder inbox = sharedInbox(share);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox));
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenReturn(List.of(listed(ROOT + "/Trash")));
    when(engine.myRights(any(), anyString())).thenReturn(MailboxRights.of("lrs"));
    EmailFolder ownRow = new EmailFolder();
    ownRow.setId(30L);
    when(emailFolderStorage.getFolderByRemoteName(GRANTEE, ROOT + "/Trash")).thenReturn(ownRow);
    when(emailFolderStorage.getFolder(GRANTEE, 30L)).thenReturn(delegated(30L, ROOT + "/Trash", false));

    service.discoverDelegatedFolders(GRANTEE, share, engine, session(), true);
    verify(emailFolderStorage).markSeen(eq(GRANTEE), eq(30L), eq("Trash"), eq("/"), any());

    when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO"));
    clearInvocations(emailFolderStorage);
    assertEquals(List.of(), service.discoverDelegatedFoldersIfDue(GRANTEE, share, store));
    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(inbox.getId()), eq(100L), isNull(), eq("lrswite"), any());

    clearInvocations(emailFolderStorage);
    when(engine.listFoldersUnder(any(), eq(ROOT), eq("/"))).thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE, "down"));
    service.discoverDelegatedFoldersIfDue(GRANTEE, share, store);
    verify(emailFolderStorage, never()).updateDelegatedRights(any(), anyLong(), anyLong(), any(), any(), any());
  }

  /**
   * The periodic pass mirrors the shared INBOX only; the other folders, opted in so that
   * opening one refreshes it, cost nothing until then. The switcher lists them, roles
   * first, missing ones left out, each with its own controls.
   */
  @Test
  void onlyTheInboxIsPeriodicAndTheSwitcherListsTheOthers() {
    EmailDelegation share = aDovecotShare();
    share.setRights("lrs");
    EmailFolder inbox = sharedInbox(share);
    inbox.setSyncEnabled(true);
    EmailFolder custom = delegated(20L, ROOT + "/Aardvark", true);
    custom.setDisplayName("Aardvark");
    EmailFolder trash = delegated(21L, ROOT + "/Corbeille", true);
    trash.setDisplayName("Corbeille");
    trash.setRole(FolderRole.TRASH);
    trash.setRights("lrs");
    trash.setRightsCheckDate(new Date());
    EmailFolder missing = delegated(22L, ROOT + "/Old", true);
    missing.setMissing(true);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(custom, inbox, trash, missing));
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(share));

    assertEquals(List.of(inbox), service.getSyncableFolders(GRANTEE, 100L));
    List<SharedMailboxFolder> folders = service.getSharedMailboxes(GRANTEE).get(0).folders();
    assertEquals(List.of(trash.getKey(), custom.getKey()), folders.stream().map(SharedMailboxFolder::key).toList());
    assertEquals(FolderRole.TRASH, folders.get(0).role());
    assertFalse(folders.get(0).affordances().get("delete"), "a Reader deletes nothing from the owner's Trash");
  }

  /**
   * EXO-90548 -- where a shared mailbox's delete, archive and spam file: that share's
   * folder of the role, never a missing one; its INBOX for a restore; a folder's role.
   */
  @Test
  void theSharesRoleFoldersAreFoundInTheShareOnly() {
    EmailDelegation share = aDovecotShare();
    EmailFolder inbox = sharedInbox(share);
    EmailFolder oldTrash = delegated(21L, ROOT + "/Old", true);
    oldTrash.setRole(FolderRole.TRASH);
    oldTrash.setMissing(true);
    EmailFolder trash = delegated(22L, ROOT + "/Corbeille", true);
    trash.setRole(FolderRole.TRASH);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox, oldTrash, trash));
    when(emailFolderStorage.getFolder(GRANTEE, 22L)).thenReturn(trash);

    assertEquals(trash.getKey(), service.roleFolderKey(GRANTEE, 100L, FolderRole.TRASH));
    assertNull(service.roleFolderKey(GRANTEE, 100L, FolderRole.ARCHIVE));
    assertEquals(inbox.getKey(), service.inboxFolderKey(GRANTEE, 100L));
    assertEquals(FolderRole.TRASH, service.roleOf(GRANTEE, trash.getKey()));
  }

  /**
   * EXO-90548 -- after a write the server acknowledged and did not do, the folder's
   * letters are re-read on the delegate's own store; the shared INBOX goes through the
   * share's own re-read.
   */
  @Test
  void aFolderWhoseWriteWasIgnoredHasItsLettersReRead() {
    EmailDelegation share = aDovecotShare();
    EmailFolder trash = delegated(22L, ROOT + "/Corbeille", true);
    trash.setRole(FolderRole.TRASH);
    EmailFolder inbox = sharedInbox(share);
    when(emailFolderStorage.getFolder(GRANTEE, 22L)).thenReturn(trash);
    when(emailFolderStorage.getFolder(GRANTEE, inbox.getId())).thenReturn(inbox);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(share);
    when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    when(engine.myRights(any(), eq(ROOT + "/Corbeille"))).thenReturn(MailboxRights.of("lrswit"));

    service.refreshFolderRights(GRANTEE, trash.getKey(), store);

    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(22L), eq(100L), eq(FolderRole.TRASH), eq("lrswit"), any());

    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(inbox));
    when(engine.myRights(any(), eq(ROOT))).thenReturn(MailboxRights.of("lrswit"));
    when(emailDelegationStorage.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service.refreshFolderRights(GRANTEE, inbox.getKey(), store);
    verify(engine).myRights(any(), eq(ROOT));
  }

  /** The share's root on the delegate's Dovecot session: the owner's INBOX itself. */
  private static final String ROOT = "shared/alice@acme.com";

  /**
   * An accepted Editor share on Dovecot, whose owner map names the owner's Trash
   * "Corbeille" and Sent "Sent".
   *
   * @return the row
   */
  private EmailDelegation aDovecotShare() {
    EmailDelegation share = accepted("lrswite");
    share.setRemoteRoot(ROOT);
    share.setGrantedRoles("INBOX,SENT,TRASH");
    Map<FolderRole, String> owner = new EnumMap<>(FolderRole.class);
    owner.put(FolderRole.TRASH, "Corbeille");
    owner.put(FolderRole.SENT, "INBOX/Sent");
    share.setOwnerRoleFolders(owner);
    return share;
  }

  /**
   * The share's registered INBOX: the root itself.
   *
   * @param share the share
   * @return the row
   */
  private EmailFolder sharedInbox(EmailDelegation share) {
    EmailFolder inbox = delegatedFolder(12L);
    inbox.setRemoteName(ROOT);
    inbox.setDelimiter("/");
    inbox.setDelegationId(share.getId());
    return inbox;
  }

  /**
   * A registered folder of the share, besides INBOX.
   *
   * @param id the row id
   * @param remoteName its full name
   * @param syncEnabled whether it is opted in
   * @return the row
   */
  private EmailFolder delegated(long id, String remoteName, boolean syncEnabled) {
    EmailFolder folder = delegatedFolder(id);
    folder.setType(MailFolderView.TYPE_DELEGATED);
    folder.setRemoteName(remoteName);
    folder.setSyncEnabled(syncEnabled);
    return folder;
  }

  /**
   * One folder as the delegate's listing shows it, holding mail.
   *
   * @param fullName the full name
   * @param attributes its LIST attributes
   * @return the folder
   */
  private static DiscoveredFolder listed(String fullName, String... attributes) {
    return new DiscoveredFolder(fullName, fullName.substring(fullName.lastIndexOf('/') + 1), "/", Set.of(attributes), false, true);
  }

  /**
   * Every registration made from here on, by remote name, each given the next id.
   *
   * @return the live map
   */
  private Map<String, EmailFolder> givenCreatedFolders() {
    Map<String, EmailFolder> created = new LinkedHashMap<>();
    when(emailFolderStorage.createFolder(any())).thenAnswer(invocation -> {
      EmailFolder folder = invocation.getArgument(0);
      folder.setId(40L + created.size());
      created.put(folder.getRemoteName(), folder);
      return folder;
    });
    return created;
  }

  /**
   * The id a registration was given.
   *
   * @param created the registrations
   * @param relative the name under the root
   * @return the id
   */
  private static long id(Map<String, EmailFolder> created, String relative) {
    return created.get(ROOT + "/" + relative).getId();
  }

  /**
   * A session over the mocked store.
   *
   * @return the session
   */
  private MailboxAclSession session() {
    return new MailboxAclSession(connector, GRANTEE, GRANTEE_MAILBOX, () -> store, null);
  }

  // ---------------------------------------------------------------------------------
  // Change access (the owner's "Change access")
  // ---------------------------------------------------------------------------------

  /**
   * Changing the access writes the preset through the engine's own grant -- which
   * replaces the grantee's entry -- on the OWNER's session, capped by the owner's rights,
   * and records what the engine says it wrote. The share keeps its status.
   */
  @Test
  void changePresetRewritesTheGranteesEntryThroughTheEngine() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.SERVER);
    accepted.setPreset(null);
    accepted.setRights("lrsw");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    MailboxRights ownerRights = MailboxRights.of("lrswipkxtea");
    when(engine.myRights(any(), eq(INBOX))).thenReturn(ownerRights);
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), eq(ownerRights)))
                                                                                                          .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                           MailboxRights.of("lrs")));

    EmailDelegation written = row(DelegationStatus.ACCEPTED, DelegationOrigin.SERVER);
    written.setPreset(DelegationPreset.READER);
    written.setRights("lrs");
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER),
                                                    eq(100L),
                                                    eq(DelegationPreset.READER),
                                                    eq("lrs"),
                                                    any(),
                                                    eq(GRANTEE_MAILBOX),
                                                    any())).thenReturn(written);

    EmailDelegation changed = service.changePreset(OWNER, 100L, DelegationPreset.READER);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).grant(session.capture(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), eq(ownerRights));
    assertEquals(OWNER, session.getValue().username(), "the owner's own session");
    assertEquals(DelegationPreset.READER, changed.getPreset());
    assertEquals("lrs", changed.getRights(), "what the server holds, not what was asked");
    assertEquals(DelegationStatus.ACCEPTED, changed.getStatus(), "the share stays accepted");
    verify(emailDelegationStorage, never()).update(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * Stack review N-1 -- the grantee leaves while the owner's SETACL is on the wire. The
   * change of access writes only what the server was told, so the leave stands: the
   * answer is the row as it now is (declined, badge off), never the pre-SETACL read
   * written back over it.
   */
  @Test
  void aLeaveDuringAChangeOfAccessStandsAfterIt() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    accepted.setBadgeIncluded(true);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    MailboxRights ownerRights = MailboxRights.of("lrswipkxtea");
    when(engine.myRights(any(), eq(INBOX))).thenReturn(ownerRights);
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(ownerRights)))
                                                                                                          .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                           MailboxRights.of("lrswit")));
    EmailDelegation left = row(DelegationStatus.DECLINED, DelegationOrigin.EXO);
    left.setBadgeIncluded(false);
    left.setRights("lrswit");
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), eq("lrswit"), any(), any(), any())).thenReturn(left);

    EmailDelegation changed = service.changePreset(OWNER, 100L, DelegationPreset.EDITOR);

    assertEquals(DelegationStatus.DECLINED, changed.getStatus(), "the leave stands");
    assertFalse(changed.isBadgeIncluded());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * A share revoked or gone while the server was asked is not written, and the owner is
   * told it is not changeable.
   */
  @Test
  void aShareThatEndedDuringAChangeOfAccessIsRefused() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO));
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), any(), any(), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrs")));
    when(emailDelegationStorage.updateGrantedRights(any(), anyLong(), any(), any(), any(), any(), any())).thenReturn(null);

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.changePreset(OWNER, 100L, DelegationPreset.READER)).getMessage());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * Only the owner of the mailbox, and only on their own rows: anybody else -- the
   * delegate included -- is told there is no such delegation, and nothing is written.
   */
  @Test
  void changePresetIsTheOwnersAlone() throws Exception {
    when(emailDelegationStorage.getAsOwner(GRANTEE, 100L)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.changePreset(GRANTEE, 100L, DelegationPreset.EDITOR));
    verify(engine, never()).grant(any(), any(), any(), any(), any());
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * A preset eXo does not grant, and a share no longer on the server, are refused with
   * their codes before anything reaches the server.
   */
  @Test
  void changePresetRefusesWhatCannotBeWritten() throws Exception {
    assertEquals(EmailDelegationService.PRESET_INVALID_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.changePreset(OWNER, 100L, DelegationPreset.CUSTOM)).getMessage());
    EmailDelegation revoked = row(DelegationStatus.REVOKED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(revoked);
    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.changePreset(OWNER, 100L, DelegationPreset.EDITOR)).getMessage());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
  }

  /**
   * A row of a mailbox the owner is no longer connected to -- they reconnected eXo to
   * another account -- is not written: writing it would grant the grantee access to the
   * mailbox connected NOW, which the row never covered.
   */
  @Test
  void changePresetRefusesARowOfAnotherMailbox() throws Exception {
    EmailDelegation elsewhere = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    elsewhere.setOwnerMailbox("alice@previous.org");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(elsewhere);
    // A server that would answer, so that only the guard can stop the write.
    lenient().when(engine.probe(any())).thenReturn(SUPPORTED);
    lenient().when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswipkxtea"));
    lenient().when(engine.grant(any(), any(), any(), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit")));

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.changePreset(OWNER, 100L, DelegationPreset.EDITOR)).getMessage());
    verify(engine, never()).grant(any(), any(), any(), any(), any());
  }

  /**
   * EXO-90546 -- a change that moves the right to keep read state is announced, since
   * the grantee's badge may count this inbox only while it is held.
   */
  @Test
  void changePresetAnnouncesAMoveOfKeepSeen() throws Exception {
    EmailDelegation readOnly = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    readOnly.setRights("lrp");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(readOnly);
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.myRights(any(), eq(INBOX))).thenReturn(MailboxRights.of("lrswipkxtea"));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any()))
                                                                                                .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                 MailboxRights.of("lrs")));
    // The row as the targeted rights write leaves it (stack review N-1).
    EmailDelegation reRead = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    reRead.setRights("lrs");
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), eq("lrs"), any(), any(), any())).thenReturn(reRead);

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.RIGHTS_CHANGED, event.getValue().type());
  }

  /**
   * EXO-90546 -- an accepted share the owner's ACL no longer carries is revoked by the
   * listing, with no notification; its grantee's badge is still told.
   */
  @Test
  void anAcceptedShareFoundGoneIsAnnounced() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(OWNER_MAILBOX, MailboxRights.of("lrswipkxtea"))));
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    accepted.setBadgeIncluded(true);
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted));
    lenient().when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of(OWNER));

    service.getGrantedDelegations(OWNER);

    assertEquals(DelegationStatus.REVOKED, accepted.getStatus());
    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.RIGHTS_CHANGED, event.getValue().type());
  }

  // ---------------------------------------------------------------------------------
  // EXO-90557: the delegate's rights re-read, stale rows, and the owner's list
  // ---------------------------------------------------------------------------------

  /**
   * At the start of a delegated pass the grantee's own MYRIGHTS on the shared INBOX is
   * re-read on the sync's store: changed letters are recorded with the preset they read
   * as, and a move of the right to keep read state is announced.
   */
  @Test
  void theGranteesRightsAreReReadAndRecorded() throws Exception {
    EmailDelegation accepted = accepted("lrp");
    // The row as it stands now: the grantee switched the badge on during the pass.
    EmailDelegation current = accepted("lrp");
    current.setBadgeIncluded(true);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(current);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(delegatedFolder(12L)));
    when(engine.myRights(any(), eq("Other Users/alice/INBOX"))).thenReturn(MailboxRights.of("rlitesw"));
    when(engine.presetOf(any())).thenReturn(DelegationPreset.EDITOR);

    EmailDelegation refreshed = service.refreshGranteeRights(GRANTEE, accepted, store);

    ArgumentCaptor<MailboxAclSession> session = ArgumentCaptor.forClass(MailboxAclSession.class);
    verify(engine).myRights(session.capture(), eq("Other Users/alice/INBOX"));
    assertSame(store, session.getValue().store(), "the sync's own store, borrowed");
    assertEquals(GRANTEE, session.getValue().username());
    assertEquals("lrswite", refreshed.getRights(), "the letters, in their canonical order");
    assertEquals(DelegationPreset.EDITOR, refreshed.getPreset());
    assertTrue(refreshed.isBadgeIncluded(), "what changed on the row since the pass began is kept");
    verify(emailDelegationStorage).update(current);
    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.RIGHTS_CHANGED, event.getValue().type());
  }

  /**
   * Unchanged rights write nothing; a share the server no longer lets the grantee read
   * -- no r, or a refusal -- is revoked and its folders dropped; a server that cannot
   * be asked leaves the share as it was.
   */
  @Test
  void theGranteesRightsDecideWhetherTheShareStands() throws Exception {
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(delegatedFolder(12L)));
    EmailDelegation same = accepted("lrs");
    when(engine.myRights(any(), any())).thenReturn(MailboxRights.of("lrs"));
    assertSame(same, service.refreshGranteeRights(GRANTEE, same, store));
    verify(emailDelegationStorage, never()).update(any());

    EmailDelegation withdrawn = accepted("lrs");
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(withdrawn);
    when(engine.myRights(any(), any())).thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO"));
    assertNull(service.refreshGranteeRights(GRANTEE, withdrawn, store));
    assertEquals(DelegationStatus.REVOKED, withdrawn.getStatus());
    verify(emailFolderStorage).deleteDelegatedFolders(GRANTEE, 100L);

    EmailDelegation revokedMeanwhile = accepted("lrs");
    clearInvocations(emailDelegationStorage);
    org.mockito.Mockito.reset(engine);
    when(engine.myRights(any(), any())).thenReturn(MailboxRights.of("lrsw"));
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(row(DelegationStatus.REVOKED, DelegationOrigin.EXO));
    assertNull(service.refreshGranteeRights(GRANTEE, revokedMeanwhile, store), "an owner's revoke made meanwhile is not undone");
    verify(emailDelegationStorage, never()).update(any());

    EmailDelegation unreachable = accepted("lrs");
    org.mockito.Mockito.reset(engine);
    when(engine.myRights(any(), any())).thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE, "down"));
    assertSame(unreachable, service.refreshGranteeRights(GRANTEE, unreachable, store));
    assertEquals(DelegationStatus.ACCEPTED, unreachable.getStatus());
  }

  /**
   * A row of a mailbox the owner is no longer connected to is not revoked on the
   * mailbox connected now: that DELETEACL would remove an entry this row never covered.
   */
  @Test
  void revokeRefusesARowOfAnotherMailbox() throws Exception {
    EmailDelegation elsewhere = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    elsewhere.setOwnerMailbox("alice@previous.org");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(elsewhere);
    lenient().when(engine.probe(any())).thenReturn(SUPPORTED);

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.revoke(OWNER, 100L)).getMessage());
    verify(engine, never()).revoke(any(), any(), any());
    assertEquals(DelegationStatus.ACCEPTED, elsewhere.getStatus());
  }

  /**
   * The owner opening their list does not overwrite a share in use with their own ACE:
   * its stored rights are the grantee's MYRIGHTS, what the grantee's controls and guards
   * read. A share not in use is still refreshed from the ACL.
   */
  @Test
  void theOwnersListLeavesAShareInUseToItsGrantee() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite"))));
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    accepted.setRights("lrswit");
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of(OWNER, GRANTEE));

    service.getGrantedDelegations(OWNER);

    assertEquals("lrswit", accepted.getRights(), "the grantee's own reading stands");
    verify(emailDelegationStorage, never()).update(accepted);
  }

  /**
   * The folder keys of every mailbox shared with the caller, across their shares.
   */
  @Test
  void theDelegatedFolderKeysSpanEveryShare() {
    EmailDelegation first = accepted("lrs");
    EmailDelegation second = accepted("lrs");
    second.setId(101L);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(first, second));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(delegatedFolder(12L)));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 101L)).thenReturn(List.of(delegatedFolder(13L)));

    assertEquals(List.of("CUSTOM:12", "CUSTOM:13"), service.getDelegatedFolderKeys(GRANTEE));
  }

  /**
   * EXO-90548 -- the roots the caller's own walk and finders leave out on one server:
   * every row's remote root on that connector, whatever its status (a declined share is
   * still listed by the server), never a blank one, and never another connector's --
   * whose path could match one of the caller's own folders on this server.
   */
  @Test
  void theSharedMailboxRootsSpanEveryRowOfTheServerWhateverItsStatus() {
    EmailDelegation accepted = accepted("lrs");
    accepted.setRemoteRoot("shared/alice@dovecot.local");
    EmailDelegation declined = accepted("lrs");
    declined.setId(101L);
    declined.setStatus(DelegationStatus.DECLINED);
    declined.setRemoteRoot("Shared Folders/anne@acme.com");
    EmailDelegation unrooted = accepted("lrs");
    unrooted.setId(102L);
    unrooted.setRemoteRoot(" ");
    EmailDelegation otherServer = accepted("lrs");
    otherServer.setId(103L);
    otherServer.setConnectorId(accepted.getConnectorId() + 1);
    otherServer.setRemoteRoot("Archive");
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(accepted, declined, unrooted, otherServer));

    assertEquals(Set.of("shared/alice@dovecot.local", "Shared Folders/anne@acme.com"),
                 service.getSharedMailboxRoots(GRANTEE, accepted.getConnectorId()));
    assertEquals(Set.of(), service.getSharedMailboxRoots(" ", accepted.getConnectorId()));
    assertEquals(Set.of(), service.getSharedMailboxRoots(GRANTEE, null));
  }

  // ---------------------------------------------------------------------------------
  // Reading the server's shares
  // ---------------------------------------------------------------------------------

  /**
   * Stack review #443-2 -- REVOKED is not terminal: a share the server lists again is
   * offered to the grantee again (AVAILABLE), from the grantee's discovery and from the
   * owner's ACL alike, with its revoke date cleared and never subscribed on its own.
   */
  @Test
  void aRevokedShareTheServerListsAgainIsOfferedAgain() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    EmailDelegation revokedAtGrantee = row(DelegationStatus.REVOKED, DelegationOrigin.EXO);
    revokedAtGrantee.setRemoteRoot("Other Users/alice");
    revokedAtGrantee.setRevokedDate(new Date(1_000L));
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(revokedAtGrantee));
    when(engine.listSharedMailboxes(any())).thenReturn(List.of(new SharedMailbox("alice@acme.com", "Other Users/alice", "Other Users/alice/INBOX", "/")));

    service.getReceivedDelegations(GRANTEE, true);

    assertEquals(DelegationStatus.AVAILABLE, revokedAtGrantee.getStatus());
    assertNull(revokedAtGrantee.getRevokedDate());
    verify(emailDelegationStorage, never()).create(any());
    verify(emailFolderStorage, never()).createFolder(any());

    EmailDelegation revokedAtOwner = row(DelegationStatus.REVOKED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(revokedAtOwner));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrs"))));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of(OWNER, GRANTEE));

    service.getGrantedDelegations(OWNER);

    assertEquals(DelegationStatus.AVAILABLE, revokedAtOwner.getStatus());
    assertEquals("lrs", revokedAtOwner.getRights());
  }

  /**
   * The owner's list is the server's ACL: the owner's own entry and {@code anyone} are
   * skipped; an entry with a row is that row (rights and native form refreshed); an
   * entry naming a connected user without a row gets an AVAILABLE/SERVER row carrying
   * the engine's preset and native form; an identifier nobody holds is listed raw; and
   * a row the ACL no longer carries is REVOKED in the database and <b>left out of the
   * list</b> -- see {@link #getGrantedDropsAGranteeTheServerNoLongerCarries}.
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
    assertEquals(3, grantees.size(), "bob, carol, dave -- never the owner, anyone, nor revoked erin");

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

    assertTrue(grantees.stream().noneMatch(g -> "erin".equals(g.granteeId())),
               "erin's entry is gone from the server, so she is not a grantee any more");
    verify(emailFolderStorage).deleteDelegatedFolders("erin", 101L);
  }

  /**
   * The pin for the defect this behaviour replaced (found on the Stalwart rig): a
   * revoke wrote DELETEACL, the row went REVOKED in the database -- and the merge put
   * it straight back into the list the owner had just removed it from, with no way to
   * remove it again. A successful revoke looked like a failed one.
   * <p>
   * The list answers "who holds access to my mailbox". Somebody the server no longer
   * carries holds none, whether the entry was removed from eXo, from the server's own
   * interface, or by an administrator.
   */
  @Test
  void getGrantedDropsAGranteeTheServerNoLongerCarries() throws Exception {
    when(engine.probe(any())).thenReturn(SUPPORTED);
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(OWNER_MAILBOX,
                                                                                   MailboxRights.of("lrswipkxtea"))));
    EmailDelegation revokedRow = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(revokedRow));
    when(userEmailSettingService.getUserEmailSettingsByEmailConnectorId(CONNECTOR_ID)).thenReturn(List.of(OWNER, GRANTEE));

    GrantedDelegations granted = service.getGrantedDelegations(OWNER);

    assertTrue(granted.grantees().isEmpty(),
               "the only entry left on the server is the owner's own, so nobody holds access");
    assertEquals(DelegationStatus.REVOKED, revokedRow.getStatus(), "and the row is closed rather than forgotten");
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

  /**
   * EXO-90546 -- flipping "count this mailbox in my unread badge" is announced, so the
   * badge is re-counted; setting it to the value it already has, or changing only the
   * notification toggle, announces nothing.
   */
  @Test
  void flippingTheBadgeSwitchIsAnnouncedAndOnlyThen() throws Exception {
    EmailDelegation accepted = row(DelegationStatus.ACCEPTED, DelegationOrigin.EXO);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, 100L)).thenReturn(accepted);

    service.updatePreferences(GRANTEE, 100L, false, true);
    verify(eventPublisher, never()).publishEvent(any());

    service.updatePreferences(GRANTEE, 100L, true, null);
    ArgumentCaptor<EmailDelegationEvent> event = ArgumentCaptor.forClass(EmailDelegationEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertEquals(EmailDelegationEvent.Type.BADGE_PREFERENCE_CHANGED, event.getValue().type());
    assertEquals(GRANTEE, event.getValue().actor());
  }

  // ---------------------------------------------------------------------------------
  // The mail drawer's switcher
  // ---------------------------------------------------------------------------------

  /**
   * The switcher offers the ACCEPTED shares only -- an invitation still pending is not a
   * mailbox anybody can open yet -- each under the key of its shared INBOX (not of
   * another folder of the same share that comes first in the registry), with that
   * INBOX's unread count and the owner's display name.
   */
  @Test
  void sharedMailboxesAreTheAcceptedSharesUnderTheirInboxKey() {
    EmailDelegation accepted = accepted("lrs");
    accepted.setPreset(DelegationPreset.READER);
    EmailDelegation pending = row(DelegationStatus.PENDING, DelegationOrigin.EXO);
    pending.setId(101L);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(pending, accepted));
    EmailFolder sentOfTheShare = delegatedFolder(11L);
    sentOfTheShare.setType(MailFolderView.TYPE_DELEGATED);
    EmailFolder inboxOfTheShare = delegatedFolder(12L);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(sentOfTheShare, inboxOfTheShare));
    // A leftover INBOX row of the pending share, so that offering a pending share
    // would show up as a second entry rather than as a missing stub.
    lenient().when(emailFolderStorage.getDelegatedFolders(GRANTEE, 101L)).thenReturn(List.of(delegatedFolder(13L)));
    when(emailBoxStorage.getFolderCounts(GRANTEE)).thenReturn(new FolderMessageCounts(Map.of("CUSTOM:12", 20, "CUSTOM:11", 40),
                                                                                      Map.of("CUSTOM:12", 3, "CUSTOM:11", 9)));
    Identity alice = new Identity("organization", OWNER);
    Profile profile = new Profile(alice);
    profile.setProperty(Profile.FULL_NAME, "Alice Martin");
    alice.setProfile(profile);
    when(identityManager.getOrCreateUserIdentity(OWNER)).thenReturn(alice);

    List<SharedMailboxEntry> entries = service.getSharedMailboxes(GRANTEE);

    assertEquals(1, entries.size(), "the pending invitation is not a mailbox to switch to");
    SharedMailboxEntry entry = entries.get(0);
    assertEquals(100L, entry.delegationId());
    assertEquals("CUSTOM:12", entry.folderKey(), "the shared INBOX, not the first folder the share has");
    assertEquals(3, entry.unreadCount());
    assertEquals("Alice Martin", entry.ownerFullName());
    assertEquals(OWNER_MAILBOX, entry.ownerMailbox());
    assertEquals(DelegationPreset.READER, entry.preset());
    assertEquals("lrs", entry.rights());
    assertTrue(entry.affordances().get("markRead"));
    assertFalse(entry.affordances().get("delete"));
    verify(emailFolderStorage, never()).getDelegatedFolders(GRANTEE, 101L);
  }

  /**
   * A share whose INBOX is not registered has nothing to open and is left out; an owner
   * no eXo profile names is shown by their address; and a caller with no accepted share
   * costs no count query at all -- the switcher is read on every drawer opening.
   */
  @Test
  void sharedMailboxesLeaveOutWhatCannotBeOpened() {
    EmailDelegation withInbox = accepted("lrs");
    withInbox.setOwnerId(null);
    EmailDelegation withoutInbox = accepted("lrs");
    withoutInbox.setId(102L);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(withInbox, withoutInbox));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(List.of(delegatedFolder(12L)));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 102L)).thenReturn(List.of());
    when(emailBoxStorage.getFolderCounts(GRANTEE)).thenReturn(new FolderMessageCounts(Map.of(), Map.of()));

    List<SharedMailboxEntry> entries = service.getSharedMailboxes(GRANTEE);

    assertEquals(1, entries.size());
    assertEquals(OWNER_MAILBOX, entries.get(0).ownerFullName(), "no eXo owner: the address names the mailbox");
    assertEquals(0, entries.get(0).unreadCount());

    withInbox.setOwnerId(OWNER);
    when(identityManager.getOrCreateUserIdentity(OWNER)).thenThrow(new RuntimeException("profile store down"));
    assertEquals(OWNER_MAILBOX,
                 service.getSharedMailboxes(GRANTEE).get(0).ownerFullName(),
                 "an unreadable profile names the mailbox by its address rather than failing the switcher");

    when(emailDelegationStorage.getReceived(OWNER)).thenReturn(List.of(row(DelegationStatus.DECLINED, DelegationOrigin.EXO)));
    assertTrue(service.getSharedMailboxes(OWNER).isEmpty());
    verify(emailBoxStorage, never()).getFolderCounts(OWNER);
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
