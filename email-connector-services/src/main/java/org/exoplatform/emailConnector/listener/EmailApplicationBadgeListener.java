/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.exoplatform.emailConnector.listener;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.plugin.EmailApplicationBadgePlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;

import io.meeds.appcenter.plugin.ApplicationBadgePlugin;
import io.meeds.appcenter.service.ApplicationBadgeService;

import jakarta.annotation.PostConstruct;

/**
 * Refreshes the E-mail badge when a user's unread count changes — after a
 * mailbox synchronisation, or right after they mark a mail read or unread.
 * <p>
 * Pure glue: it holds no counting logic, it only reports staleness.
 */
@Component
@Asynchronous
@ConditionalOnClass(ApplicationBadgePlugin.class)
public class EmailApplicationBadgeListener extends Listener<String, Object> {

  @Autowired
  private ApplicationBadgeService applicationBadgeService;

  @Autowired
  private ListenerService         listenerService;

  @PostConstruct
  public void init() {
    listenerService.addListener(EmailConnectorUtils.UNREAD_EMAILS_CHANGED, this);
  }

  @Override
  public void onEvent(Event<String, Object> event) throws Exception {
    String username = event.getSource();
    if (StringUtils.isNotBlank(username)) {
      applicationBadgeService.updateBadge(EmailApplicationBadgePlugin.BADGE_NAME, username);
    }
  }

}
