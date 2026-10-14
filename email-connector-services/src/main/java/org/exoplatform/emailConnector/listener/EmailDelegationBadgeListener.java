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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * Tells the grantee's unread badge that a shared inbox started or stopped counting in it
 * (delegation plan 7.7, EXO-90546): the "Count this mailbox in my unread badge" switch
 * flipped, or a share that was counted was accepted again, left, revoked, found gone
 * by a reconciliation, or had its right to keep read state changed by its owner.
 * <p>
 * Pure glue: the count is {@link EmailBoxService#countUnreadEmails}'s, the announcement
 * its {@link EmailBoxService#broadcastUnreadCountChanged}; this only decides that the
 * number may have moved. A share that does not count moves nothing when it comes or
 * goes, and says nothing. AFTER_COMMIT with a fallback, like the notification listener
 * beside it: the badge must be re-counted from the committed rows.
 */
@Component
public class EmailDelegationBadgeListener {

  @Autowired
  private EmailBoxService emailBoxService;

  /**
   * Announces a possible move of the grantee's badge.
   *
   * @param event the delegation transition
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleDelegationChanged(EmailDelegationEvent event) {
    EmailDelegation delegation = event == null ? null : event.delegation();
    if (delegation == null || event.type() == null || delegation.getGranteeId() == null) {
      return;
    }
    if (movesTheBadge(event.type(), delegation)) {
      emailBoxService.broadcastUnreadCountChanged(delegation.getGranteeId());
    }
  }

  /**
   * Whether a transition can change what the grantee's badge counts: the switch itself,
   * always; a share arriving or leaving, only when it is one the grantee counts.
   *
   * @param type the transition
   * @param delegation the row as it now stands
   * @return true when the badge must be re-counted
   */
  private boolean movesTheBadge(EmailDelegationEvent.Type type, EmailDelegation delegation) {
    return switch (type) {
    case BADGE_PREFERENCE_CHANGED -> true;
    case ACCEPTED, LEFT, REVOKED, RIGHTS_CHANGED -> delegation.isBadgeIncluded();
    default -> false;
    };
  }
}
