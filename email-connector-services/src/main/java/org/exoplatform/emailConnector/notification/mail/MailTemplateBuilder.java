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
package org.exoplatform.emailConnector.notification.mail;

import java.io.Writer;
import java.util.Calendar;
import java.util.Locale;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.channel.template.AbstractTemplateBuilder;
import org.exoplatform.commons.api.notification.channel.template.TemplateProvider;
import org.exoplatform.commons.api.notification.model.MessageInfo;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.service.template.TemplateContext;
import org.exoplatform.commons.notification.template.TemplateUtils;
import org.exoplatform.commons.utils.TimeConvertUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.social.notification.plugin.SocialNotificationUtils;

public class MailTemplateBuilder extends AbstractTemplateBuilder {

  private final TemplateProvider templateProvider;

  public MailTemplateBuilder(MailTemplateProvider mailTemplateProvider) {
    this.templateProvider = mailTemplateProvider;
  }

  @Override
  protected MessageInfo makeMessage(NotificationContext notificationContext) {
    NotificationInfo notification = notificationContext.getNotificationInfo();
    String language = getLanguage(notification);
    String pluginId = notification.getKey().getId();
    TemplateContext templateContext =
                                    TemplateContext.newChannelInstance(this.templateProvider.getChannelKey(), pluginId, language);
    SocialNotificationUtils.addFooterAndFirstName(notification.getTo(), templateContext);
    Calendar cal = Calendar.getInstance();
    cal.setTimeInMillis(notification.getLastModifiedDate());
    templateContext.put("NOTIFICATION_ID", notification.getId());
    templateContext.put("LAST_UPDATED_TIME",
                        TimeConvertUtils.convertXTimeAgoByTimeServer(cal.getTime(),
                                                                     "EE, dd yyyy",
                                                                     Locale.of(language),
                                                                     TimeConvertUtils.YEAR));
    templateContext.put("SUBJECT", notification.getValueOwnerParameter(NotificationConstants.TITLE));
    templateContext.put("EMAILS_CONTENT", notification.getValueOwnerParameter(NotificationConstants.CONTENT));
    templateContext.put("EMAILS_LINK", notification.getValueOwnerParameter(NotificationConstants.LINK));

    MessageInfo messageInfo = new MessageInfo();
    // process subject then add the result String to the template context
    TemplateUtils.processSubject(templateContext);
    messageInfo.subject((String) templateContext.get("SUBJECT"));
    messageInfo.body(TemplateUtils.processGroovy(templateContext));
    notificationContext.setException(templateContext.getException());
    return messageInfo.end();
  }

  @Override
  protected boolean makeDigest(NotificationContext notificationContext, Writer writer) {
    return false;
  }
}
