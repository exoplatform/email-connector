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
package org.exoplatform.emailConnector.plugin;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.appcenter.plugin.ApplicationBadgePlugin;

/**
 * Reports the user's unread emails on the E-mail application tile.
 * <p>
 * Counts from the <strong>locally synced mirror</strong> only, never from IMAP:
 * the badge sits on the topbar of every page, so it must stay a local read. The
 * consequence is that a mail read directly in the remote mailbox is reflected
 * at the next synchronisation rather than instantly.
 */
@Component
public class EmailApplicationBadgePlugin implements ApplicationBadgePlugin {

  private static final Log        LOG        = ExoLogger.getLogger(EmailApplicationBadgePlugin.class);

  public static final String      BADGE_NAME = "emailUnread";

  @Autowired
  private EmailBoxService         emailBoxService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  /**
   * The url of the Application Center catalog entry shipped in this addon's
   * {@code applications.json}, so that the binding resolves with no
   * administrator action.
   */
  @Value("${email.badge.drawerName:email}")
  private String                  drawerName;

  @Override
  public String getName() {
    return BADGE_NAME;
  }

  @Override
  public String getDrawerName() {
    return drawerName;
  }

  @Override
  public long countBadge(String username) {
    try {
      return emailBoxService.countUnreadEmails(username);
    } catch (Exception e) {
      LOG.warn("Error counting unread emails of user {}", username, e);
      return 0;
    }
  }

  /**
   * A user with no connected mailbox has nothing to count, and must not be
   * probed at all.
   */
  @Override
  public boolean isEnabled(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    return userEmailSetting != null && userEmailSetting.isConnected();
  }

}
