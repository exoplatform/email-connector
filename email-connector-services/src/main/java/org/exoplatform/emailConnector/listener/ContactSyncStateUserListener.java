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

import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserEventListener;

import jakarta.annotation.PostConstruct;

/**
 * Forgets a deleted user's address-book sync state. The platform removes the
 * user's own settings context with the user, but this state lives in a global
 * context, out of the user's reach, so nothing else removes it.
 */
@Component
public class ContactSyncStateUserListener extends UserEventListener {

  private static final Log        LOG = ExoLogger.getLogger(ContactSyncStateUserListener.class);

  @Autowired
  private OrganizationService     organizationService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @PostConstruct
  public void init() {
    organizationService.getUserHandler().addUserEventListener(this);
  }

  @Override
  public void postDelete(User user) {
    if (user == null) {
      return;
    }
    try {
      userEmailSettingService.clearContactSyncState(user.getUserName());
    } catch (Exception e) {
      // The platform also catches a listener's failure, so the deletion goes on
      // either way; this names what was left behind: one settings row.
      LOG.warn("The address book sync state of deleted user {} could not be removed", user.getUserName(), e);
    }
  }
}
