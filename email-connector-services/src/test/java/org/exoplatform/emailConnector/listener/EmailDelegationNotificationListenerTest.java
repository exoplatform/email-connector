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
package org.exoplatform.emailConnector.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.command.NotificationCommand;
import org.exoplatform.commons.api.notification.command.NotificationExecutor;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.notification.plugin.BaseEmailDelegationNotificationPlugin;
import org.exoplatform.emailConnector.notification.plugin.EmailDelegationInvitationPlugin;
import org.exoplatform.emailConnector.notification.plugin.EmailDelegationResponseNotificationPlugin;
import org.exoplatform.emailConnector.utils.NotificationConstants;

/**
 * Who is told about a share, and who is deliberately not (EXO-90503).
 * <p>
 * The pin this class exists for is {@link #theOwnerHearsAboutADeclineOnEveryServer}:
 * a server that e-mails the mailbox owner does so about a RIGHTS CHANGE, and none of
 * the transitions reported here is one -- accepting makes the grantee's own
 * subscription, leaving withdraws it, declining leaves the ACL exactly as the invite
 * wrote it (plan sections 5.1 step 5, 5.3, 13.B.16). Gating these would leave the owner
 * hearing about a decline from nobody at all: the very outcome the capability exists to
 * prevent, reached backwards.
 */
@ExtendWith(MockitoExtension.class)
public class EmailDelegationNotificationListenerTest {

  private static final String                 OWNER   = "anne";

  private static final String                 GRANTEE = "ben";

  @Mock
  private WebNotificationService              webNotificationService;

  @InjectMocks
  private EmailDelegationNotificationListener listener;

  private MockedStatic<NotificationContextImpl> notifications;

  private final List<NotificationContext>     dispatched = new ArrayList<>();

  /**
   * Captures every notification context the listener builds, with an executor that
   * accepts a command and does nothing with it.
   */
  @BeforeEach
  void captureTheNotifications() {
    notifications = mockStatic(NotificationContextImpl.class);
    notifications.when(NotificationContextImpl::cloneInstance).thenAnswer(invocation -> {
      NotificationContext ctx = mock(NotificationContext.class, org.mockito.Answers.RETURNS_SELF);
      NotificationExecutor executor = mock(NotificationExecutor.class, org.mockito.Answers.RETURNS_SELF);
      lenient().when(ctx.getNotificationExecutor()).thenReturn(executor);
      lenient().when(ctx.makeCommand(any())).thenReturn(mock(NotificationCommand.class));
      dispatched.add(ctx);
      return ctx;
    });
  }

  /**
   * Takes the static away again.
   */
  @AfterEach
  void forgetTheStatic() {
    notifications.close();
  }

  /**
   * An invitation goes to the grantee, naming the owner and the preset -- and it goes
   * even on a server that notifies the owner itself, because that server's mail is to
   * the OWNER and nobody has told the grantee anything.
   */
  @Test
  void anInvitationGoesToTheGranteeOnEveryServer() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.INVITED, OWNER, delegation()));

    assertEquals(1, dispatched.size(), "the grantee is invited");
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.RECEIVER, GRANTEE);
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.ACTOR, OWNER);
    verify(dispatched.get(0)).append(EmailDelegationInvitationPlugin.PRESET, "READER");
    assertEquals(NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN, dispatchedPluginId(0));
  }

  /**
   * On a server that says nothing, eXo tells the owner how the grantee answered.
   */
  @Test
  void theOwnerIsToldHowTheGranteeAnsweredWhenTheServerSaysNothing() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.ACCEPTED, GRANTEE, delegation()));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.DECLINED, GRANTEE, delegation()));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.LEFT, GRANTEE, delegation()));

    assertEquals(3, dispatched.size(), "the owner is told about each answer");
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.RECEIVER, OWNER);
    verify(dispatched.get(0)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "ACCEPTED");
    verify(dispatched.get(1)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "DECLINED");
    verify(dispatched.get(2)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "LEFT");
    assertEquals(NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN, dispatchedPluginId(0));
  }

  /**
   * The pin. Even on a server that e-mails the mailbox owner of its own accord, eXo
   * tells the owner how the grantee answered -- because that server's mail is about a
   * rights change and none of these three is one. A decline in particular touches the
   * server not at all: the ACL stays exactly as the invite wrote it (plan section 5.3),
   * so nobody but eXo can tell the owner it happened.
   * <p>
   * The first implementation of EXO-90503 gated all three on the capability and this
   * assertion is what stands against putting that back. If BlueMind is one day observed
   * to mail the owner when a delegate SUBSCRIBES, then ACCEPTED -- and only ACCEPTED --
   * joins the gated set, and this test says so rather than being quietly widened.
   */
  @Test
  void theOwnerHearsAboutADeclineOnEveryServer() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.ACCEPTED, GRANTEE, delegation()));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.DECLINED, GRANTEE, delegation()));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.LEFT, GRANTEE, delegation()));

    assertEquals(3, dispatched.size(), "no answer of the grantee's is a server rights change, so eXo reports all three");
    verify(dispatched.get(1)).append(BaseEmailDelegationNotificationPlugin.RECEIVER, OWNER);
    verify(dispatched.get(1)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "DECLINED");
  }

  /**
   * A revocation is told to the grantee, and that one is never gated: the server's own
   * mail goes to the owner, so nobody but eXo tells the grantee their access is gone.
   */
  @Test
  void theGranteeIsAlwaysToldTheirAccessWasRemoved() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.REVOKED, OWNER, delegation()));

    assertEquals(1, dispatched.size(), "the grantee is told");
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.RECEIVER, GRANTEE);
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.ACTOR, OWNER);
    verify(dispatched.get(0)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "REVOKED");
  }

  /**
   * A share whose owner eXo does not know -- one discovered on the server, granted to
   * somebody by an administrator -- has nobody to tell, and says nothing rather than
   * failing.
   */
  @Test
  void aShareWithNoKnownOwnerNotifiesNobody() {
    EmailDelegation orphan = delegation();
    orphan.setOwnerId(null);

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.INVITED, GRANTEE, orphan));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.ACCEPTED, GRANTEE, orphan));

    assertTrue(dispatched.isEmpty(), "there is nobody to name and nobody to tell");
  }

  /**
   * EXO-90582 -- the owner's consent to the grantee writing in her name is told to a
   * grantee using the share, as the consent now stands: on behalf, as, or withdrawn.
   */
  @Test
  void theGranteeIsToldTheConsentAsItNowStands() {
    EmailDelegation accepted = delegation();
    accepted.setStatus(DelegationStatus.ACCEPTED);
    accepted.setSendMode(SendMode.ON_BEHALF);
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.SEND_MODE_CHANGED, OWNER, accepted));
    accepted.setSendMode(SendMode.AS);
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.SEND_MODE_CHANGED, OWNER, accepted));
    accepted.setSendMode(null);
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.SEND_MODE_CHANGED, OWNER, accepted));

    assertEquals(3, dispatched.size(), "each change is told");
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.RECEIVER, GRANTEE);
    verify(dispatched.get(0)).append(BaseEmailDelegationNotificationPlugin.ACTOR, OWNER);
    verify(dispatched.get(0)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "SEND_MODE_ON_BEHALF");
    verify(dispatched.get(1)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "SEND_MODE_AS");
    verify(dispatched.get(2)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "SEND_MODE_NONE");
    assertEquals(NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN, dispatchedPluginId(0));
  }

  /**
   * EXO-90582, PO decision Q-B -- a grantee still invited is not told: they have the
   * invitation, and read the consent on the share when they accept it.
   */
  @Test
  void aPendingGranteeIsNotToldTheConsent() {
    EmailDelegation pending = delegation();
    pending.setStatus(DelegationStatus.PENDING);
    pending.setSendMode(SendMode.ON_BEHALF);

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.SEND_MODE_CHANGED, OWNER, pending));

    assertTrue(dispatched.isEmpty(), "the invitation is theirs to answer first");
  }

  /**
   * EXO-90830 -- once the grantee answered, their stored invitation says how: the space
   * invitation's pattern. Only the grantee's invitations to THIS share are looked up,
   * and only the status parameter is written, so the notification keeps its place and
   * its read state.
   */
  @Test
  void anAnswerMarksTheGranteesInvitationToThatShare() {
    NotificationInfo invitation = storedInvitation("42", null);
    when(webNotificationService.getNotificationInfos(any(), eq(0), anyInt())).thenReturn(List.of(invitation));

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.ACCEPTED, GRANTEE, delegation()));

    ArgumentCaptor<WebNotificationFilter> filter = ArgumentCaptor.forClass(WebNotificationFilter.class);
    verify(webNotificationService).getNotificationInfos(filter.capture(), eq(0), anyInt());
    assertEquals(GRANTEE, filter.getValue().getUserId());
    assertEquals(List.of(PluginKey.key(NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN)),
                 filter.getValue().getPluginKeys());
    assertEquals(NotificationConstants.DELEGATION_ID, filter.getValue().getParameter().getKey());
    assertEquals("7", filter.getValue().getParameter().getValue());
    verify(webNotificationService).updateNotificationParameters("42", Map.of(NotificationConstants.DELEGATION_STATUS, "ACCEPTED"));
    verify(webNotificationService, never()).save(any());
    verify(webNotificationService, never()).update(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  /**
   * An invitation already showing the outcome is not written again.
   */
  @Test
  void anInvitationAlreadyMarkedIsLeftAsItIs() {
    when(webNotificationService.getNotificationInfos(any(), eq(0), anyInt())).thenReturn(List.of(storedInvitation("42",
                                                                                                                 "DECLINED")));

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.DECLINED, GRANTEE, delegation()));

    verify(webNotificationService, never()).updateNotificationParameters(anyString(), anyMap());
  }

  /**
   * Each outcome is the transition's, not the row's status: a left share goes back to
   * DECLINED or AVAILABLE, and the invitation must say "you stopped using it", not "you
   * refused it". A revocation by the owner marks it too.
   */
  @Test
  void eachOutcomeIsTheTransitionsName() {
    EmailDelegation left = delegation();
    left.setStatus(DelegationStatus.DECLINED);
    assertEquals("ACCEPTED", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.ACCEPTED, delegation()));
    assertEquals("DECLINED", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.DECLINED, delegation()));
    assertEquals("LEFT", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.LEFT, left));
    assertEquals("REVOKED", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.REVOKED, delegation()));
  }

  /**
   * A rights change marks the invitation only when it found the share gone (revoked, or
   * its mailbox gone from the server); one that left the share in use says nothing.
   */
  @Test
  void aRightsChangeMarksTheInvitationOnlyWhenTheShareIsGone() {
    EmailDelegation revoked = delegation();
    revoked.setStatus(DelegationStatus.REVOKED);
    EmailDelegation gone = delegation();
    gone.setStatus(DelegationStatus.GONE);
    EmailDelegation accepted = delegation();
    accepted.setStatus(DelegationStatus.ACCEPTED);

    assertEquals("REVOKED", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.RIGHTS_CHANGED, revoked));
    assertEquals("REVOKED", EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.RIGHTS_CHANGED, gone));
    assertNull(EmailDelegationNotificationListener.invitationStatus(EmailDelegationEvent.Type.RIGHTS_CHANGED, accepted));
  }

  /**
   * The transitions that leave the share where it was do not even look the invitation
   * up: the invite itself, the consent to write in the owner's name, the badge toggle.
   */
  @Test
  void theTransitionsThatAnswerNothingLeaveTheInvitationAlone() {
    EmailDelegation accepted = delegation();
    accepted.setStatus(DelegationStatus.ACCEPTED);
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.INVITED, OWNER, delegation()));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.SEND_MODE_CHANGED, OWNER, accepted));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.BADGE_PREFERENCE_CHANGED, GRANTEE, accepted));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.RIGHTS_CHANGED, null, accepted));

    verifyNoInteractions(webNotificationService);
  }

  /**
   * Neither half costs the other: a notification service failing while the invitation
   * is marked leaves the owner told, and the owner's notification failing still marks
   * the invitation.
   */
  @Test
  void markingAndTellingDoNotDependOnEachOther() {
    when(webNotificationService.getNotificationInfos(any(), eq(0), anyInt())).thenThrow(new IllegalStateException("down"));

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.DECLINED, GRANTEE, delegation()));

    assertEquals(1, dispatched.size(), "the owner is told all the same");
    verify(dispatched.get(0)).append(EmailDelegationResponseNotificationPlugin.RESPONSE, "DECLINED");

    notifications.when(NotificationContextImpl::cloneInstance).thenThrow(new IllegalStateException("down"));
    org.mockito.Mockito.reset(webNotificationService);
    when(webNotificationService.getNotificationInfos(any(), eq(0), anyInt())).thenReturn(List.of(storedInvitation("42", null)));

    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.DECLINED, GRANTEE, delegation()));

    verify(webNotificationService).updateNotificationParameters("42", Map.of(NotificationConstants.DELEGATION_STATUS, "DECLINED"));
  }

  /**
   * A stored invitation, as the web notification service returns it.
   *
   * @param id its id
   * @param status the status it already shows, null for none
   * @return the notification
   */
  private NotificationInfo storedInvitation(String id, String status) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(NotificationConstants.DELEGATION_ID, "7");
    if (status != null) {
      parameters.put(NotificationConstants.DELEGATION_STATUS, status);
    }
    NotificationInfo invitation = NotificationInfo.instance();
    invitation.setId(id);
    invitation.setOwnerParameter(parameters);
    return invitation;
  }

  /**
   * The plugin id one captured context was dispatched with.
   *
   * @param index which captured context
   * @return the plugin id
   */
  private String dispatchedPluginId(int index) {
    ArgumentCaptor<PluginKey> key = ArgumentCaptor.forClass(PluginKey.class);
    verify(dispatched.get(index)).makeCommand(key.capture());
    return key.getValue().getId();
  }

  /**
   * A READER share of anne's mailbox, granted to ben.
   *
   * @return the row
   */
  private EmailDelegation delegation() {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(7L);
    delegation.setOwnerId(OWNER);
    delegation.setGranteeId(GRANTEE);
    delegation.setConnectorId(1L);
    delegation.setPreset(DelegationPreset.READER);
    return delegation;
  }
}
