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

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.notification.plugin.BaseEmailDelegationNotificationPlugin;
import org.exoplatform.emailConnector.notification.plugin.EmailDelegationInvitationPlugin;
import org.exoplatform.emailConnector.notification.plugin.EmailDelegationResponseNotificationPlugin;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Tells the party that did not act what happened to a share (EXO-90503, delegation
 * plan sections 5.1 to 5.4, 7.2).
 * <p>
 * Glue, as every listener in this package is: it decides <b>who</b> is told and
 * <b>whether</b> they are told at all, and the sentence is the plugin's. There is one
 * piece of judgement here and it belongs nowhere else, because nowhere else knows both
 * the recipient and the server:
 * <p>
 * <b>An owner-facing notification is not sent when the mail server already sent one.</b>
 * BlueMind e-mails the mailbox owner on every rights change of its own accord -- four
 * such mails were observed on one account over two phase-0 rounds -- so eXo adding its
 * own would reach that owner twice for one act, on a screen they did not ask twice
 * about (plan sections 5.1 step 5 and 13.F). The engine answers the question without a
 * connection: it is a trait of the server product, and the act that raises an
 * owner-facing notification here is the <i>grantee's</i>, on the grantee's thread,
 * where the owner's mailbox is nobody's to open.
 * <p>
 * The gate is deliberately per <b>recipient</b> and not per transition. An accept or a
 * decline is not literally a rights change, and it is possible that a BlueMind owner
 * therefore hears about a grant twice and about a decline not at all. That is the
 * conservative half of the trade the plan asks for -- "eXo's owner-side notifications
 * are gated on it being false" -- and the owner sees the answer on the sharing screen
 * either way, where the row carries its own status. A per-transition gate would need a
 * second fact about every server that nobody has observed for any of them.
 * <p>
 * The grantee-facing ones are never gated: the server notifies the <i>owner</i>, and
 * on the servers observed the grantee is told nothing by anybody but eXo.
 * <p>
 * AFTER_COMMIT with a fallback, like this package's other outward-facing listener: a
 * notification about a state change that then rolled back is a message about something
 * that never happened, and it cannot be taken back. The fallback covers the ordinary
 * case here, where the lifecycle's own transaction has already closed by the time the
 * event is published.
 */
@Component
public class EmailDelegationNotificationListener {

  private static final Log       LOG = ExoLogger.getLogger(EmailDelegationNotificationListener.class);

  /**
   * Routes one delegation transition to the person it concerns.
   *
   * @param event the transition
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleDelegationChanged(EmailDelegationEvent event) {
    EmailDelegation delegation = event == null ? null : event.delegation();
    if (delegation == null || event.type() == null) {
      return;
    }
    try {
      switch (event.type()) {
      case INVITED -> notifyGrantee(delegation);
      case ACCEPTED, DECLINED, LEFT -> notifyOwner(delegation, event.type());
      case REVOKED -> notifyRevokedGrantee(delegation);
      // A transition added to the enum without a line here would otherwise notify
      // nobody in silence; this says so in the log rather than in nothing at all.
      default -> LOG.debug("Delegation {} went {}, which nothing here is written to tell anybody about",
                           delegation.getId(),
                           event.type());
      }
    } catch (RuntimeException | LinkageError e) {
      // A notification is never worth failing the act it reports: the share is granted,
      // accepted or revoked on the server whatever the notification service answers.
      LOG.warn("The mailbox delegation {} of {} could not be notified", event.type(), delegation.getId(), e);
    }
  }

  /**
   * Invites the grantee to use a share that already exists on the server.
   *
   * @param delegation the row
   */
  private void notifyGrantee(EmailDelegation delegation) {
    if (StringUtils.isBlank(delegation.getGranteeId()) || StringUtils.isBlank(delegation.getOwnerId())) {
      return;
    }
    NotificationContext ctx =
                            NotificationContextImpl.cloneInstance()
                                                   .append(BaseEmailDelegationNotificationPlugin.RECEIVER,
                                                           delegation.getGranteeId())
                                                   .append(BaseEmailDelegationNotificationPlugin.ACTOR,
                                                           delegation.getOwnerId())
                                                   .append(BaseEmailDelegationNotificationPlugin.DELEGATION_ID,
                                                           String.valueOf(delegation.getId()))
                                                   .append(EmailDelegationInvitationPlugin.PRESET,
                                                           delegation.getPreset() == null ? ""
                                                                                          : delegation.getPreset().name());
    dispatch(ctx, NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN);
  }

  /**
   * Tells the owner how the grantee answered. Never gated on
   * {@link org.exoplatform.emailConnector.service.EmailDelegationService#serverNotifiesOwner}
   * -- and the reason is worth keeping, because the opposite was written first and is
   * the easy mistake to repeat.
   * <p>
   * A server that notifies the owner does so about a <b>rights change</b>: BlueMind
   * mails "&lt;someone&gt; a modifie vos droits d'acces" when an ACL moves (plan
   * SS13.B.16). None of the three transitions this method reports is one. Accepting
   * makes the grantee's own <b>subscription</b>; leaving withdraws it; declining
   * touches the server not at all -- the ACL was written at invite and stays exactly
   * as it was (plan SS5.3). So there is no second message to collide with, and gating
   * these would leave the owner hearing about a decline from nobody at all, which is
   * the outcome the gate exists to prevent, reached backwards.
   * <p>
   * The gate belongs where the plan scoped it (SS5.1 step 5): an owner-facing
   * notification about <b>the owner's own rights-changing act</b> -- a grant or revoke
   * confirmation, a digest, phase 2's admin trail. eXo raises none of those today; it
   * tells the <i>grantee</i> about both invite and revoke. The capability is carried
   * and tested so that the notification which does need it can be gated in one line.
   * <p>
   * <i>Open (needs phase 1b to observe)</i>: whether BlueMind also mails the owner when
   * a delegate <b>subscribes</b>. If it does, ACCEPTED -- and only ACCEPTED -- joins the
   * gated set.
   *
   * @param delegation the row
   * @param type the transition
   */
  private void notifyOwner(EmailDelegation delegation, EmailDelegationEvent.Type type) {
    if (StringUtils.isBlank(delegation.getOwnerId()) || StringUtils.isBlank(delegation.getGranteeId())) {
      return;
    }
    dispatchResponse(delegation.getOwnerId(), delegation.getGranteeId(), delegation, type);
  }

  /**
   * Tells the grantee their access was taken away. Never gated: the server's own mail
   * goes to the owner, and on the servers observed nobody tells the grantee anything.
   *
   * @param delegation the row
   */
  private void notifyRevokedGrantee(EmailDelegation delegation) {
    if (StringUtils.isBlank(delegation.getGranteeId()) || StringUtils.isBlank(delegation.getOwnerId())) {
      return;
    }
    dispatchResponse(delegation.getGranteeId(),
                     delegation.getOwnerId(),
                     delegation,
                     EmailDelegationEvent.Type.REVOKED);
  }

  /**
   * Builds and dispatches the "where that share now stands" notification.
   *
   * @param receiver who is told
   * @param actor whose act it was
   * @param delegation the row
   * @param type the transition
   */
  private void dispatchResponse(String receiver, String actor, EmailDelegation delegation, EmailDelegationEvent.Type type) {
    NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                     .append(BaseEmailDelegationNotificationPlugin.RECEIVER, receiver)
                                                     .append(BaseEmailDelegationNotificationPlugin.ACTOR, actor)
                                                     .append(BaseEmailDelegationNotificationPlugin.DELEGATION_ID,
                                                             String.valueOf(delegation.getId()))
                                                     .append(EmailDelegationResponseNotificationPlugin.RESPONSE, type.name());
    dispatch(ctx, NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN);
  }

  /**
   * Hands a built context to the notification service.
   *
   * @param ctx the context
   * @param pluginId the plugin to render it
   */
  private void dispatch(NotificationContext ctx, String pluginId) {
    ctx.getNotificationExecutor().with(ctx.makeCommand(PluginKey.key(pluginId))).execute(ctx);
  }
}
