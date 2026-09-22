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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

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
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.EmailDelegation;
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
