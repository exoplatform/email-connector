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
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.EmailCategoryAdded;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;

import jakarta.annotation.PostConstruct;

/**
 * Glue between a category added to inbox mail and the owner's mail filters on that
 * category, with no logic of its own: the mails go to
 * {@link EmailFilterService#applyToCategorizedMail}. Asynchronous, so whoever added the
 * category -- the AI categorizer in its batch, the user's click -- never waits for a
 * filter's actions; the kernel runs it with the container and a request life cycle.
 */
@Component
@Asynchronous
public class EmailFilterCategoryListener extends Listener<String, EmailCategoryAdded> {

  /** Where the listener registers. */
  @Autowired
  private ListenerService    listenerService;

  /** The filters it hands the mails to. */
  @Autowired
  private EmailFilterService emailFilterService;

  /**
   * Registers on {@link EmailConnectorUtils#EMAIL_CATEGORY_ADDED}.
   */
  @PostConstruct
  public void init() {
    listenerService.addListener(EmailConnectorUtils.EMAIL_CATEGORY_ADDED, this);
  }

  /**
   * Runs the owner's filters on the category on the mails that just got it.
   *
   * @param event source = the owner, data = the category and the mails
   */
  @Override
  public void onEvent(Event<String, EmailCategoryAdded> event) {
    EmailCategoryAdded added = event.getData();
    if (added != null) {
      emailFilterService.applyToCategorizedMail(event.getSource(), added.categoryId(), added.mailRemoteIds());
    }
  }
}
