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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * EXO-90546 -- which delegation transitions re-count the grantee's badge: the switch
 * itself always, a share arriving or leaving only when it is one the grantee counts.
 */
@ExtendWith(MockitoExtension.class)
class EmailDelegationBadgeListenerTest {

  @Mock
  private EmailBoxService              emailBoxService;

  @InjectMocks
  private EmailDelegationBadgeListener listener;

  /**
   * Flipping the switch re-counts the grantee's badge -- the grantee's, not the owner's.
   */
  @Test
  void theSwitchReCountsTheGranteesBadge() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.BADGE_PREFERENCE_CHANGED,
                                                              "bob",
                                                              share(false, DelegationStatus.ACCEPTED)));

    verify(emailBoxService).broadcastUnreadCountChanged("bob");
  }

  /**
   * Leaving or losing a share that was counted re-counts the badge -- a reconciliation
   * finding it gone, or its owner moving its right to keep read state, included.
   */
  @Test
  void aCountedShareLeftOrRevokedReCountsTheBadge() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.LEFT,
                                                              "bob",
                                                              share(true, DelegationStatus.DECLINED)));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.REVOKED,
                                                              "alice",
                                                              share(true, DelegationStatus.REVOKED)));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.RIGHTS_CHANGED,
                                                              null,
                                                              share(true, DelegationStatus.GONE)));

    verify(emailBoxService, org.mockito.Mockito.times(3)).broadcastUnreadCountChanged("bob");
  }

  /**
   * A share that was not counted moves nothing when it goes, and an invitation never
   * does: nothing is said.
   */
  @Test
  void aShareNotCountedSaysNothing() {
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.LEFT,
                                                              "bob",
                                                              share(false, DelegationStatus.DECLINED)));
    listener.handleDelegationChanged(new EmailDelegationEvent(EmailDelegationEvent.Type.INVITED,
                                                              "alice",
                                                              share(true, DelegationStatus.PENDING)));

    verify(emailBoxService, never()).broadcastUnreadCountChanged(anyString());
  }

  /**
   * Bob's share of alice's mailbox.
   *
   * @param counted whether bob counts it in his badge
   * @param status its state
   * @return the row
   */
  private EmailDelegation share(boolean counted, DelegationStatus status) {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(5L);
    delegation.setGranteeId("bob");
    delegation.setOwnerId("alice");
    delegation.setStatus(status);
    delegation.setBadgeIncluded(counted);
    return delegation;
  }
}
