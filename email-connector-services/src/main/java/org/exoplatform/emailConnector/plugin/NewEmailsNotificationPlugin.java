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

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.ArgumentLiteral;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.plugin.BaseNotificationPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.NotificationConstants;

public class NewEmailsNotificationPlugin extends BaseNotificationPlugin {

  public static final String                                                      ID         = "NewEmailsNotificationPlugin";

  public static final ArgumentLiteral<NotificationConstants.NOTIFICATION_CONTEXT> CONTEXT    =
                                                                                          new ArgumentLiteral<>(NotificationConstants.NOTIFICATION_CONTEXT.class,
                                                                                                                "CONTEXT");

  public static final ArgumentLiteral<String>                                     RECEIVER   =
                                                                                           new ArgumentLiteral<>(String.class,
                                                                                                                 NotificationConstants.RECEIVER);

  public static final ArgumentLiteral<String>                                     NEW_EMAILS =
                                                                                             new ArgumentLiteral<>(String.class,
                                                                                                                   NotificationConstants.NEW_EMAILS);

  public NewEmailsNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean isValid(NotificationContext ctx) {
    return true;
  }

  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    NotificationConstants.NOTIFICATION_CONTEXT context = ctx.value(CONTEXT);
    String receiver = ctx.value(RECEIVER);
    String newEmails = ctx.value(NEW_EMAILS);
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.CONTEXT, context.getContext())
                           .with(NotificationConstants.NEW_EMAILS, newEmails)
                           .key(getKey())
                           .end();

  }
}
