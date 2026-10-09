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

import org.exoplatform.emailConnector.event.EmailConnectorAuthenticationChangedEvent;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

/**
 * Hands a change of a connector's authentication to
 * {@link UserEmailSettingService#forgetManagedRefusalsOn(long)}.
 * <p>
 * After the commit, with {@code fallbackExecution}: the refusals are forgotten once the
 * change is stored, and a connector edit may run without a Spring transaction.
 */
@Component
public class EmailManagedRefusalListener {

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleAuthenticationChanged(EmailConnectorAuthenticationChangedEvent event) {
    userEmailSettingService.forgetManagedRefusalsOn(event.getEmailConnectorId());
  }
}
