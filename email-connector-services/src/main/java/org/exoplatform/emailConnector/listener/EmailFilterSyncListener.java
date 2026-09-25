/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.event.NewInboxMailEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;

/**
 * Glue between the sync and the owner's mail filters, with no logic of its own: the
 * new mail of an inbox goes to {@link EmailFilterService#applyToNewMail}, synchronously
 * -- the sync waits for the answer, the mails a filter filed away, before it announces
 * the rest. Never {@code @Async}: an answer that came after the announcement would be no
 * answer at all.
 */
@Component
public class EmailFilterSyncListener {

  @Autowired
  private EmailFilterService emailFilterService;

  /**
   * Runs the owner's filters on an inbox's new mail and hands the filed UIDs back.
   *
   * @param event the new mail
   */
  @EventListener
  public void onNewInboxMail(NewInboxMailEvent event) {
    event.getFiledUids().addAll(emailFilterService.applyToNewMail(event.getUsername(), event.getMails(), event.getContext()));
  }
}
