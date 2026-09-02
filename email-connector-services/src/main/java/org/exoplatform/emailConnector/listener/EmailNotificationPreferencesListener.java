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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailNotificationPreferencesChangedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * Glue only: a saved notification preference becomes an unread-count broadcast,
 * through the one funnel every other count-changing path already uses
 * ({@link EmailBoxService#broadcastUnreadCountChanged}). The badge counts by
 * that preference, so without this it would keep showing the number the old
 * preference produced until the next sync happened to move it.
 * <p>
 * A listener rather than a call from the settings service, because
 * {@code EmailBoxService} already depends on {@code UserEmailSettingService}
 * and the reverse edge would be a bean cycle.
 */
@Component
public class EmailNotificationPreferencesListener {

  @Autowired
  private EmailBoxService emailBoxService;

  /**
   * Relays the saved preference to the unread-count funnel.
   * <p>
   * AFTER_COMMIT so a badge recount reads the preference as written, and
   * {@code fallbackExecution} for the reason {@code EmailBoxCleanupListener}
   * documents: the preference save is a settings write with no transaction of
   * its own, and a transactional listener with no transaction in progress is
   * silently skipped otherwise.
   *
   * @param event the saved preference
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleNotificationPreferencesChanged(EmailNotificationPreferencesChangedEvent event) {
    emailBoxService.broadcastUnreadCountChanged(event.getUsername());
  }
}
