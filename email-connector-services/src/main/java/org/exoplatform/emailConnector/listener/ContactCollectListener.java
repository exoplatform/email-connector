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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.event.EmailSentEvent;
import org.exoplatform.emailConnector.event.MailboxResetEvent;
import org.exoplatform.emailConnector.service.EmailContactService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import jakarta.annotation.PostConstruct;

/**
 * Glue between the mail flows and contact collection, with no business logic of
 * its own — every event delegates to {@link EmailContactService}. Three feeds:
 * the per-group NEW_EMAILS_SYNCED broadcast (inbox senders, while the sync is
 * still running), the end-of-sync NEW_EMAILS_SYNC_COMPLETED broadcast (the
 * one-time whole-cache backfill hook), and the Spring {@link EmailSentEvent}
 * (sent-mail recipients — the strongest signal). {@code EmailBoxService}'s sync
 * is not touched at all: collection rides events it already emits.
 */
@Asynchronous
@Component
public class ContactCollectListener extends Listener<String, List<Long>> {

  private static final Log      LOG             = ExoLogger.getLogger(ContactCollectListener.class);

  private static final String[] LISTENER_EVENTS = { EmailConnectorUtils.NEW_EMAILS_SYNCED,
      EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED };

  @Autowired
  private ListenerService       listenerService;

  @Autowired
  private EmailContactService   emailContactService;

  /**
   * Self-registers on the sync broadcasts, the {@code AnalyticsEmailListener}
   * way.
   */
  @PostConstruct
  public void init() {
    for (String eventName : LISTENER_EVENTS) {
      listenerService.addListener(eventName, this);
    }
  }

  /**
   * Routes one sync broadcast: a group of new inbox messages feeds incremental
   * collection; the end-of-sync signal triggers the one-time backfill (which
   * no-ops once its per-user marker is set). Failures are logged and swallowed —
   * contact collection must never be a reason a sync looks broken.
   *
   * @param event source = the mailbox owner, data = the group's IMAP UIDs
   */
  @Override
  public void onEvent(Event<String, List<Long>> event) {
    String username = event.getSource();
    try {
      if (EmailConnectorUtils.NEW_EMAILS_SYNCED.equals(event.getEventName())) {
        emailContactService.collectFromSyncedEmails(username, event.getData());
      } else {
        emailContactService.backfillFromCacheIfNeeded(username);
      }
    } catch (Exception e) {
      LOG.warn("Contact collection failed for user {} on event {}", username, event.getEventName(), e);
    }
  }

  /**
   * Lets collection start over when the mailbox it collects from was reset.
   * <p>
   * The cache the contacts were collected from has just been thrown away, and the
   * incremental pass cannot rebuild them: it judges an inbox sender against the
   * organisations the user has written to, read from cached sent mail, which the
   * resync reads after the inbox.
   *
   * @param event the reset event
   */
  @EventListener
  public void onMailboxReset(MailboxResetEvent event) {
    try {
      emailContactService.resetCollectionBackfill(event.getUsername());
    } catch (Exception e) {
      LOG.warn("Re-arming contact collection failed for user {}", event.getUsername(), e);
    }
  }

  /**
   * Collects the recipients of a mail the user just sent. Synchronous with the
   * publish (a handful of upserts), and fenced so a store hiccup can never fail
   * a mail that was already accepted by the SMTP server.
   *
   * @param event the sent-mail event, To and Cc recipients only
   */
  @EventListener
  public void onEmailSent(EmailSentEvent event) {
    try {
      emailContactService.collectFromSentRecipients(event.getUsername(), event.getRecipients());
    } catch (Exception e) {
      LOG.warn("Contact collection from a sent email failed for user {}", event.getUsername(), e);
    }
  }
}
