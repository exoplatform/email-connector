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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Set;

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

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
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
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * EXO-90582 -- the owner's consent to a delegate writing mail in her name: who may set
 * it and on which share, what is checked before and on the owner's session, what is
 * written and what is never written, how it goes with the share on every exit, and what
 * the delegate's list and the owner's list say of it.
 */
@ExtendWith(MockitoExtension.class)
class EmailDelegationSendModeTest {

  private static final String                 OWNER           = "alice";

  private static final String                 OWNER_MAILBOX   = "alice@acme.com";

  private static final String                 GRANTEE         = "bob";

  private static final String                 GRANTEE_MAILBOX = "bob@acme.com";

  private static final long                   CONNECTOR_ID    = 7L;

  private static final long                   ID              = 100L;

  private static final String                 INBOX           = "INBOX";

  private static final String                 ROOT            = "Other Users/alice";

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

  /**
   * Alice and Bob connected on connector 7, which declares both shapes; the engine
   * answers an IMAP server naming Bob on Alice's INBOX; the storage answers its writes
   * as it would.
   */
  @BeforeEach
  void setUp() throws Exception {
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "as");
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(aclEngineRegistry.engineFor(connector)).thenReturn(engine);
    lenient().when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(setting(OWNER_MAILBOX));
    lenient().when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(setting(GRANTEE_MAILBOX));
    lenient().when(userEmailSettingService.connect(anyString(), anyString())).thenReturn(store);
    lenient().when(engine.probe(any())).thenAnswer(invocation -> MailboxAclCapabilities.imap(true, true, SendMode.declaredFor(CONNECTOR_ID)));
    lenient().when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite"))));
    lenient().when(emailDelegationStorage.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(emailDelegationStorage.updateSendMode(eq(OWNER), eq(ID), any())).thenAnswer(invocation -> {
      EmailDelegation written = share(DelegationStatus.ACCEPTED);
      SendMode mode = invocation.getArgument(2);
      written.setSendMode(mode == SendMode.NONE ? null : mode);
      written.setSendModeDate(mode == SendMode.NONE ? null : new Date());
      return written;
    });
  }

  /**
   * Puts the declarations back the way the JVM had them.
   */
  @AfterEach
  void clearTheDeclarations() {
    System.clearProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID);
    System.clearProperty(SendMode.MODES_PROPERTY);
    System.clearProperty(SendMode.ENABLED_PROPERTY);
  }

  /**
   * On behalf, on Bob's accepted share: written by the targeted write, never by a
   * whole-row one; nothing asked of the IMAP engine beyond its probe and the INBOX ACL;
   * the change announced.
   */
  @Test
  void onBehalfIsWrittenByTheOwnersTargetedWrite() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));

    EmailDelegation updated = service.setSendMode(OWNER, ID, "ON_BEHALF");

    assertEquals(SendMode.ON_BEHALF, updated.getSendMode());
    verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.ON_BEHALF);
    verify(emailDelegationStorage, never()).update(any());
    verify(engine, never()).grantSendMode(any(), anyString(), any());
    EmailDelegationEvent event = publishedEvent();
    assertEquals(EmailDelegationEvent.Type.SEND_MODE_CHANGED, event.type());
    assertEquals(OWNER, event.actor());
    assertEquals(SendMode.ON_BEHALF, event.delegation().getSendMode());
  }

  /**
   * A share that ended while the server was being asked is not given a consent: the
   * targeted write finds nothing, and the owner is told it cannot be changed.
   */
  @Test
  void aShareEndedMeanwhileIsNotChangeable() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));
    when(emailDelegationStorage.updateSendMode(OWNER, ID, SendMode.AS)).thenReturn(null);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "AS"));

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE, refused.getMessage());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * As on a connector that declares as: written. On one that declares on behalf only:
   * refused before any session is opened.
   */
  @Test
  void asNeedsTheConnectorsDeclarationBeforeAnySession() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));
    assertEquals(SendMode.AS, service.setSendMode(OWNER, ID, "AS").getSendMode());

    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "onBehalf");
    org.mockito.Mockito.clearInvocations(engine, emailDelegationStorage);
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "AS"));

    assertEquals(EmailDelegationService.SEND_MODE_UNSUPPORTED_MESSAGE, refused.getMessage());
    verify(engine, never()).probe(any());
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());
  }

  /**
   * A server whose probe does not declare the shape refuses it too, after the probe and
   * before any write.
   */
  @Test
  void theServersOwnAnswerIsCheckedToo() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));
    when(engine.probe(any())).thenReturn(MailboxAclCapabilities.imap(true, true, Set.of(SendMode.ON_BEHALF)));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "AS"));

    assertEquals(EmailDelegationService.SEND_MODE_UNSUPPORTED_MESSAGE, refused.getMessage());
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());
  }

  /**
   * Switched off: a consent is refused without a session; its withdrawal is still
   * recorded -- taking it back must always work.
   */
  @Test
  void theKillSwitchRefusesAConsentButNeverItsWithdrawal() throws Exception {
    System.setProperty(SendMode.ENABLED_PROPERTY, "false");
    EmailDelegation consented = share(DelegationStatus.ACCEPTED);
    consented.setSendMode(SendMode.ON_BEHALF);
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(consented);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "ON_BEHALF"));
    assertEquals(EmailDelegationService.SEND_MODE_DISABLED_MESSAGE, refused.getMessage());
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());

    assertNull(service.setSendMode(OWNER, ID, "NONE").getSendMode());
    verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.NONE);
  }

  /**
   * A withdrawal is recorded before the server is asked anything: eXo's record, which the
   * send path reads, never waits on a login.
   */
  @Test
  void aWithdrawalIsRecordedBeforeTheServerIsAsked() throws Exception {
    EmailDelegation consented = share(DelegationStatus.ACCEPTED);
    consented.setSendMode(SendMode.AS);
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(consented);

    service.setSendMode(OWNER, ID, "NONE");

    InOrder order = inOrder(emailDelegationStorage, engine);
    order.verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.NONE);
    order.verify(engine).probe(any());
  }

  /**
   * A withdrawal is recorded even when the owner's mailbox cannot be reached at all --
   * disconnected, or its server unsupported: eXo's record is what the send path reads.
   */
  @Test
  void aWithdrawalNeverNeedsTheServer() throws Exception {
    EmailDelegation consented = share(DelegationStatus.ACCEPTED);
    consented.setSendMode(SendMode.AS);
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(consented);
    when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(null);

    assertNull(service.setSendMode(OWNER, ID, "NONE").getSendMode());

    verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.NONE);
    assertEquals(EmailDelegationEvent.Type.SEND_MODE_CHANGED, publishedEvent().type());
  }

  /**
   * An unknown mode is a 400 with this add-on's own code, before anything is read.
   */
  @Test
  void anUnknownModeIsInvalid() {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "WHATEVER"));

    assertEquals(EmailDelegationService.SEND_MODE_INVALID_MESSAGE, refused.getMessage());
    verify(emailDelegationStorage, never()).getAsOwner(anyString(), anyLong());
  }

  /**
   * Only the owner's own row: the grantee asking, or anybody else, gets "no such
   * delegation" and nothing is written.
   */
  @Test
  void onlyTheOwnerOfTheRowMaySetIt() {
    when(emailDelegationStorage.getAsOwner(GRANTEE, ID)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.setSendMode(GRANTEE, ID, "ON_BEHALF"));

    verify(emailDelegationStorage, never()).getAsGrantee(anyString(), anyLong());
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());
  }

  /**
   * A share made in the mail server's own interface is never given a consent (PO
   * decision Q-A).
   */
  @Test
  void aShareMadeOnTheServerIsNotChangeable() {
    EmailDelegation serverMade = share(DelegationStatus.ACCEPTED);
    serverMade.setOrigin(DelegationOrigin.SERVER);
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(serverMade);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "ON_BEHALF"));

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE, refused.getMessage());
    verify(engine, never()).probe(any());
  }

  /**
   * Only a share on offer or in use: declined, available, revoked and gone ones are not
   * changeable, for a consent or a withdrawal. A pending one may carry it (PO decision
   * Q-B).
   */
  @Test
  void onlyALiveShareCarriesIt() throws Exception {
    for (DelegationStatus status : List.of(DelegationStatus.DECLINED,
                                           DelegationStatus.AVAILABLE,
                                           DelegationStatus.REVOKED,
                                           DelegationStatus.GONE)) {
      when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(status));
      for (String mode : List.of("ON_BEHALF", "NONE")) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, mode));
        assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE, refused.getMessage(), status + " " + mode);
      }
    }
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());

    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.PENDING));
    service.setSendMode(OWNER, ID, "ON_BEHALF");
    verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.ON_BEHALF);
  }

  /**
   * A row of another mailbox than the one connected now, and a share the INBOX ACL no
   * longer names, are not given a consent.
   */
  @Test
  void anotherMailboxOrAShareGoneFromInboxIsNotChangeable() {
    EmailDelegation otherMailbox = share(DelegationStatus.ACCEPTED);
    otherMailbox.setOwnerMailbox("alice.old@acme.com");
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(otherMailbox);
    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "ON_BEHALF")).getMessage());

    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters("carol@acme.com", MailboxRights.of("lrs"))));
    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.setSendMode(OWNER, ID, "ON_BEHALF")).getMessage());
    verify(emailDelegationStorage, never()).updateSendMode(anyString(), anyLong(), any());
  }

  /**
   * An engine that writes the consent on the server is asked to, with the identifier and
   * the shape; its withdrawal is taken off there too. An IMAP engine is never asked
   * (the first test).
   */
  @Test
  void anEngineThatHoldsItOnTheServerIsAskedToWriteIt() throws Exception {
    MailboxAclCapabilities onServer = new MailboxAclCapabilities(true,
                                                                 false,
                                                                 false,
                                                                 GrantGranularity.MAILBOX,
                                                                 true,
                                                                 true,
                                                                 null,
                                                                 Set.of(SendMode.ON_BEHALF, SendMode.AS),
                                                                 true);
    when(engine.probe(any())).thenReturn(onServer);
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(share(DelegationStatus.ACCEPTED));

    service.setSendMode(OWNER, ID, "AS");
    verify(engine).grantSendMode(any(), eq(GRANTEE_MAILBOX), eq(SendMode.AS));

    service.setSendMode(OWNER, ID, "NONE");
    verify(engine).revokeSendMode(any(), eq(GRANTEE_MAILBOX));
  }

  /**
   * The same mode set again is written -- a consent set again clears the server's last
   * refusal -- but not announced again.
   */
  @Test
  void theSameModeAgainIsWrittenButNotAnnounced() throws Exception {
    EmailDelegation consented = share(DelegationStatus.ACCEPTED);
    consented.setSendMode(SendMode.ON_BEHALF);
    consented.setSendRefusedDate(new Date());
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(consented);

    service.setSendMode(OWNER, ID, "ON_BEHALF");

    verify(emailDelegationStorage).updateSendMode(OWNER, ID, SendMode.ON_BEHALF);
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * Remove access takes the consent off with the share -- after the write that ends it,
   * never before -- and the announced row carries none.
   */
  @Test
  void revokeTakesTheConsentOffAfterEndingTheShare() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, ID)).thenReturn(consented(DelegationStatus.ACCEPTED));

    service.revoke(OWNER, ID);

    InOrder order = inOrder(emailDelegationStorage);
    order.verify(emailDelegationStorage).update(any());
    order.verify(emailDelegationStorage).clearSendModeIfEnded(ID);
    EmailDelegationEvent event = publishedEvent();
    assertEquals(EmailDelegationEvent.Type.REVOKED, event.type());
    assertWithoutConsent(event.delegation());
  }

  /**
   * Declining a pending share that carried a consent (PO decision Q-B) takes it off: a
   * later accept starts with none.
   */
  @Test
  void declineTakesTheConsentOff() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, ID)).thenReturn(consented(DelegationStatus.PENDING));

    assertWithoutConsent(service.decline(GRANTEE, ID));

    verify(emailDelegationStorage).clearSendModeIfEnded(ID);
  }

  /**
   * Leaving, and a disconnect that ends every share in use, take it off.
   */
  @Test
  void leaveAndADisconnectTakeTheConsentOff() throws Exception {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, ID)).thenReturn(consented(DelegationStatus.ACCEPTED));
    assertWithoutConsent(service.leave(GRANTEE, ID));
    verify(emailDelegationStorage).clearSendModeIfEnded(ID);

    org.mockito.Mockito.clearInvocations(emailDelegationStorage);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(consented(DelegationStatus.ACCEPTED)));
    service.endReceivedShares(GRANTEE);
    verify(emailDelegationStorage).clearSendModeIfEnded(ID);
  }

  /**
   * An accept that finds the share gone from the server marks it revoked, and takes the
   * consent off with it.
   */
  @Test
  void anAcceptFindingTheShareGoneTakesTheConsentOff() {
    when(emailDelegationStorage.getAsGrantee(GRANTEE, ID)).thenReturn(consented(DelegationStatus.PENDING));
    when(engine.findSharedMailbox(any(), eq(OWNER_MAILBOX))).thenReturn(null);

    assertThrows(DelegationRevokedException.class, () -> service.accept(GRANTEE, ID));

    ArgumentCaptor<EmailDelegation> written = ArgumentCaptor.forClass(EmailDelegation.class);
    verify(emailDelegationStorage).update(written.capture());
    assertEquals(DelegationStatus.REVOKED, written.getValue().getStatus());
    verify(emailDelegationStorage).clearSendModeIfEnded(ID);
  }

  /**
   * The sync's rights refresh finding the share withdrawn takes the consent off with it.
   */
  @Test
  void aShareFoundWithdrawnBySyncLosesTheConsent() {
    EmailFolder inbox = new EmailFolder();
    inbox.setId(12L);
    inbox.setType(MailFolderView.TYPE_DELEGATED_INBOX);
    inbox.setRemoteName(ROOT);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, ID)).thenReturn(List.of(inbox));
    when(engine.myRights(any(), eq(ROOT))).thenReturn(MailboxRights.NONE);
    when(emailDelegationStorage.getAsGrantee(GRANTEE, ID)).thenReturn(consented(DelegationStatus.ACCEPTED));

    assertNull(service.refreshGranteeRights(GRANTEE, consented(DelegationStatus.ACCEPTED), store));

    verify(emailDelegationStorage).clearSendModeIfEnded(ID);
  }

  /**
   * The grantee's discovery: an accepted share the server no longer lists goes GONE and
   * loses the consent; a revoked one the server lists again comes back available,
   * without one.
   */
  @Test
  void aGoneShareLosesTheConsentAndAReopenedOneNeverGetsItBack() {
    EmailDelegation inUse = consented(DelegationStatus.ACCEPTED);
    EmailDelegation revoked = consented(DelegationStatus.REVOKED);
    revoked.setId(101L);
    revoked.setOwnerMailbox("carol@acme.com");
    revoked.setRemoteRoot("Other Users/carol");
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(inUse, revoked));
    when(engine.listSharedMailboxes(any())).thenReturn(List.of(new SharedMailbox("carol@acme.com", "Other Users/carol", "Other Users/carol/INBOX", "/")));

    service.getReceivedDelegations(GRANTEE, true);

    ArgumentCaptor<EmailDelegation> written = ArgumentCaptor.forClass(EmailDelegation.class);
    verify(emailDelegationStorage, org.mockito.Mockito.times(2)).update(written.capture());
    assertEquals(DelegationStatus.AVAILABLE, written.getAllValues().get(0).getStatus(), "the reopened one");
    assertEquals(DelegationStatus.GONE, written.getAllValues().get(1).getStatus(), "the one no longer listed");
    verify(emailDelegationStorage).clearSendModeIfEnded(101L);
    verify(emailDelegationStorage).clearSendModeIfEnded(ID);
    written.getAllValues().forEach(this::assertWithoutConsent);
  }

  /**
   * The delegate's switcher lists the shapes Bob can write in now: the consent narrowed
   * to the connector's declaration, none once refused, none switched off.
   */
  @Test
  void theSwitcherListsTheUsableShapes() {
    EmailDelegation share = consented(DelegationStatus.ACCEPTED);
    share.setSendRefusedDate(null);
    EmailFolder inbox = new EmailFolder();
    inbox.setId(12L);
    inbox.setUserId(GRANTEE);
    inbox.setType(MailFolderView.TYPE_DELEGATED_INBOX);
    inbox.setRemoteName(ROOT);
    inbox.setDelegationId(ID);
    when(emailDelegationStorage.getReceived(GRANTEE)).thenReturn(List.of(share));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, ID)).thenReturn(List.of(inbox));

    assertEquals(List.of(SendMode.ON_BEHALF, SendMode.AS), service.getSharedMailboxes(GRANTEE).get(0).sendModes());
    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "onBehalf");
    assertEquals(List.of(SendMode.ON_BEHALF), service.getSharedMailboxes(GRANTEE).get(0).sendModes(), "never wider than declared");
    share.setSendRefusedDate(new Date());
    assertEquals(List.of(), service.getSharedMailboxes(GRANTEE).get(0).sendModes(), "refused since last set");
    share.setSendRefusedDate(null);
    System.setProperty(SendMode.ENABLED_PROPERTY, "false");
    assertEquals(List.of(), service.getSharedMailboxes(GRANTEE).get(0).sendModes(), "switched off");
    share.setSendMode(null);
    System.clearProperty(SendMode.ENABLED_PROPERTY);
    assertEquals(List.of(), service.getSharedMailboxes(GRANTEE).get(0).sendModes(), "no consent");
  }

  /**
   * The owner's list carries the declared shapes, and for each live share eXo made what
   * the consent to it says: the grantee's name, and whether the owner keeps a copy --
   * from the grant for a pending share, from the grantee's discovered Sent once in use,
   * never with the copy switched off; nothing on a server that accepts no shape.
   */
  @Test
  void theOwnersListSaysWhatTheConsentTextNeeds() throws Exception {
    EmailDelegation pendingEditor = share(DelegationStatus.PENDING);
    EmailDelegation pendingReader = share(DelegationStatus.PENDING);
    pendingReader.setId(101L);
    pendingReader.setGranteeId("carol");
    pendingReader.setGranteeMailbox("carol@acme.com");
    pendingReader.setPreset(DelegationPreset.READER);
    EmailDelegation inUse = share(DelegationStatus.ACCEPTED);
    inUse.setId(102L);
    inUse.setGranteeId("dave");
    inUse.setGranteeMailbox("dave@acme.com");
    EmailFolder sentWithoutInsert = new EmailFolder();
    sentWithoutInsert.setId(22L);
    sentWithoutInsert.setType(MailFolderView.TYPE_DELEGATED);
    sentWithoutInsert.setRole(org.exoplatform.emailConnector.model.FolderRole.SENT);
    sentWithoutInsert.setRightsCheckDate(new Date());
    sentWithoutInsert.setRights("lrs");
    when(emailFolderStorage.getDelegatedFolders("dave", 102L)).thenReturn(List.of(sentWithoutInsert));
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(pendingEditor, pendingReader, inUse));
    when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswite")),
                                                              MailboxAce.ofLetters("carol@acme.com", MailboxRights.of("lrs")),
                                                              MailboxAce.ofLetters("dave@acme.com", MailboxRights.of("lrswite"))));
    when(emailConnectorService.isSharedMailboxSentCopyEnabled()).thenReturn(true);

    GrantedDelegations granted = service.getGrantedDelegations(OWNER);
    assertEquals(Set.of(SendMode.ON_BEHALF, SendMode.AS), granted.capabilities().sendModes());
    assertFalse(granted.capabilities().sendModeOnServer());
    assertTrue(grantee(granted, ID).ownerSentCopy(), "an Editor share covering Sent, pending: the grant says so");
    assertEquals(GRANTEE_MAILBOX, grantee(granted, ID).granteeFullName(), "the name, the address when no profile is read");
    assertFalse(grantee(granted, 101L).ownerSentCopy(), "a Reader cannot file a copy");
    assertFalse(grantee(granted, 102L).ownerSentCopy(), "in use: the discovered Sent decides, and holds no i");

    when(emailConnectorService.isSharedMailboxSentCopyEnabled()).thenReturn(false);
    assertFalse(grantee(service.getGrantedDelegations(OWNER), ID).ownerSentCopy(), "switched off");

    System.setProperty(SendMode.MODES_PROPERTY_PREFIX + CONNECTOR_ID, "none");
    DelegationGrantee undeclared = grantee(service.getGrantedDelegations(OWNER), ID);
    assertNull(undeclared.granteeFullName(), "nothing to consent to, nothing computed");
  }

  /**
   * One grantee of the owner's list, by row id.
   *
   * @param granted the list
   * @param id the row id
   * @return the entry
   */
  private static DelegationGrantee grantee(GrantedDelegations granted, long id) {
    return granted.grantees().stream().filter(entry -> entry.delegation() != null && entry.delegation().getId() == id).findFirst().orElseThrow();
  }

  /**
   * Asserts a row carries no consent at all.
   *
   * @param delegation the row
   */
  private void assertWithoutConsent(EmailDelegation delegation) {
    assertNull(delegation.getSendMode(), "no mode");
    assertNull(delegation.getSendModeDate(), "no date");
    assertNull(delegation.getSendRefusedDate(), "no refusal");
  }

  /**
   * The one event published.
   *
   * @return it
   */
  private EmailDelegationEvent publishedEvent() {
    ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertSame(EmailDelegationEvent.class, event.getValue().getClass());
    return (EmailDelegationEvent) event.getValue();
  }

  /**
   * Bob's share of Alice's mailbox, made by eXo, carrying a consent to write as her.
   *
   * @param status its status
   * @return the row, id 100
   */
  private static EmailDelegation consented(DelegationStatus status) {
    EmailDelegation delegation = share(status);
    delegation.setSendMode(SendMode.AS);
    delegation.setSendModeDate(new Date(1_000L));
    delegation.setSendRefusedDate(new Date(2_000L));
    return delegation;
  }

  /**
   * Bob's Editor share of Alice's mailbox, made by eXo.
   *
   * @param status its status
   * @return the row, id 100
   */
  private static EmailDelegation share(DelegationStatus status) {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(ID);
    delegation.setGranteeId(GRANTEE);
    delegation.setOwnerId(OWNER);
    delegation.setOwnerMailbox(OWNER_MAILBOX);
    delegation.setGranteeMailbox(GRANTEE_MAILBOX);
    delegation.setConnectorId(CONNECTOR_ID);
    delegation.setRemoteRoot(ROOT);
    delegation.setPreset(DelegationPreset.EDITOR);
    delegation.setRights("lrswite");
    delegation.setStatus(status);
    delegation.setOrigin(DelegationOrigin.EXO);
    delegation.setGrantedRoles("INBOX,SENT");
    return delegation;
  }

  /**
   * A connected setting on connector 7.
   *
   * @param address the mailbox address
   * @return the setting
   */
  private static UserEmailSetting setting(String address) {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress(address);
    setting.setEmailPassword("secret");
    return setting;
  }
}
