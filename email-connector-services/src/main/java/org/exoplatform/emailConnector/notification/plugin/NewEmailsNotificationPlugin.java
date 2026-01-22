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
package org.exoplatform.emailConnector.notification.plugin;

import java.util.Locale;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.ArgumentLiteral;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.plugin.BaseNotificationPlugin;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.ResourceBundleService;

public class NewEmailsNotificationPlugin extends BaseNotificationPlugin {

  public static final ArgumentLiteral<String> RECEIVER                  =
                                                       new ArgumentLiteral<>(String.class, "receiver");

  public static final ArgumentLiteral<String> NEW_EMAILS                = new ArgumentLiteral<>(String.class, "newEmails");

  private static final String                 DESCRIPTION_LABEL_KEY     = "newEmails.notification.description";

  private static final String                 BODY_NEW_EMAILS_LABEL_KEY = "newEmails.notification.title";

  private static final String                 BODY_NEW_EMAIL_LABEL_KEY  = "newEmail.notification.title";

  public NewEmailsNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  @Override
  public String getId() {
    return NotificationConstants.NEW_EMAILS_NOTIFICATION_PLUGIN;
  }

  @Override
  public boolean isValid(NotificationContext ctx) {
    return true;
  }

  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String newEmails = ctx.value(NEW_EMAILS);
    String language = NotificationPluginUtils.getLanguage(receiver);
    ResourceBundleService resourceBundleService = CommonsUtils.getService(ResourceBundleService.class);
    String title = resourceBundleService.getSharedString(DESCRIPTION_LABEL_KEY, Locale.of(language));
    String content = Integer.parseInt(newEmails) == 1
                                                      ? resourceBundleService.getSharedString(BODY_NEW_EMAIL_LABEL_KEY,
                                                                                              Locale.of(language))
                                                      : resourceBundleService.getSharedString(BODY_NEW_EMAILS_LABEL_KEY,
                                                                                              Locale.of(language))
                                                                             .replace("{0}", newEmails);
    String link = EmailConnectorUtils.getEmailsLink(receiver);
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title)
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.LINK, link)
                           .key(getKey())
                           .end();

  }
}
