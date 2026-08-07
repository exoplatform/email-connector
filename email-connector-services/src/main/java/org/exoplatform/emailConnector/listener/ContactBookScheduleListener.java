/**
 * Copyright (C) 2025 eXo Platform SAS
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

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import jakarta.annotation.PostConstruct;

/**
 * Gives the address book a schedule by riding the mailbox's.
 * <p>
 * The mailbox sync already runs per user, already staggers users across the hour,
 * already re-registers itself at startup and unschedules on disconnect. Rather
 * than write those three again for a second Quartz job — they are the parts most
 * likely to go wrong — the end of a mailbox sync asks the address book whether it
 * is due. The service decides; this only asks, as a listener should.
 * <p>
 * A mailbox that cannot sync therefore does not carry its address book along.
 * Accepted: the two share credentials, so a mailbox failing to sign in almost
 * always means an address book that would fail too.
 */
@Asynchronous
@Component
public class ContactBookScheduleListener extends Listener<String, List<Long>> {

  private static final Log                     LOG = ExoLogger.getLogger(ContactBookScheduleListener.class);

  @Autowired
  private ListenerService                      listenerService;

  @Autowired
  private EmailContactCardDavSyncService       emailContactCardDavSyncService;

  /**
   * Self-registers on the end-of-sync broadcast, the way contact collection does.
   */
  @PostConstruct
  public void init() {
    listenerService.addListener(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED, this);
  }

  /**
   * Asks the address book whether it is due, once the mailbox has finished.
   *
   * @param event source = the mailbox owner
   */
  @Override
  public void onEvent(Event<String, List<Long>> event) {
    try {
      emailContactCardDavSyncService.syncAddressBookIfDue(event.getSource());
    } catch (Exception e) {
      // Never worth failing a mailbox sync over: the contacts are stale, the mail
      // is not.
      LOG.warn("The scheduled address book sync failed for user {}", event.getSource(), e);
    }
  }
}
