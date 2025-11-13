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
package org.exoplatform.emailConnector.plugin;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.portal.application.PortalRequestContext;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.resources.LocaleConfig;
import org.exoplatform.services.resources.ResourceBundleService;

import io.meeds.pwa.model.PwaNotificationMessage;
import io.meeds.pwa.plugin.PwaNotificationPlugin;

public class NewEmailsNotificationPwaPlugin implements PwaNotificationPlugin {

  public static final String    ID                        = "NewEmailsNotificationPlugin";

  private static final String   TITLE_LABEL_KEY           = "newEmails.notification.description";

  private static final String   BODY_NEW_EMAILS_LABEL_KEY = "newEmails.notification.title";

  private static final String   BODY_NEW_EMAIL_LABEL_KEY  = "newEmail.notification.title";

  private ResourceBundleService resourceBundleService;

  public NewEmailsNotificationPwaPlugin(ResourceBundleService resourceBundleService) {
    this.resourceBundleService = resourceBundleService;
  }

  @Override
  public PwaNotificationMessage process(NotificationInfo notification, LocaleConfig localeConfig) {
    PwaNotificationMessage notificationMessage = new PwaNotificationMessage();
    String title = resourceBundleService.getSharedString(TITLE_LABEL_KEY, localeConfig.getLocale());
    notificationMessage.setTitle(title);
    String body =
                Integer.parseInt(notification.getValueOwnerParameter(NotificationConstants.NEW_EMAILS)) == 1 ? resourceBundleService.getSharedString(BODY_NEW_EMAIL_LABEL_KEY,
                                                                                                                                                     localeConfig.getLocale())
                                                                                                             : resourceBundleService.getSharedString(BODY_NEW_EMAILS_LABEL_KEY,
                                                                                                                                                     localeConfig.getLocale())
                                                                                                                                    .replace("{0}",
                                                                                                                                             notification.getValueOwnerParameter(NotificationConstants.NEW_EMAILS));
    notificationMessage.setBody(body);
    notificationMessage.setUrl("/portal/" + getDefaultPortalOwner(notification.getTo()) + "?openEmailBox=true");
    return notificationMessage;
  }

  @Override
  public String getId() {
    return ID;
  }

  private static String getDefaultPortalOwner(String username) {
    PortalRequestContext pContext = null;
    try {
      pContext = Util.getPortalRequestContext();
    } catch (NullPointerException e) {
      pContext = null;
    }
    if (pContext != null) {
      return pContext.getPortalOwner();
    } else {
      UserPortalConfigService portalConfig = CommonsUtils.getService(UserPortalConfigService.class);
      return portalConfig == null ? null : portalConfig.getDefaultSite(username).getName();
    }
  }
}
