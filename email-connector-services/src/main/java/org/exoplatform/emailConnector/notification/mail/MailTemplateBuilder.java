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

  /**
   * Builds the mail message of the "new emails" notification, for the mail
   * channel the given provider is registered on.
   *
   * @param mailTemplateProvider the provider holding the channel key and the
   *          Groovy template path of this plugin
   */
  public MailTemplateBuilder(MailTemplateProvider mailTemplateProvider) {
    this.templateProvider = mailTemplateProvider;
  }

  /**
   * Turns one notification into the mail actually sent to the user: its subject
   * and its HTML body, both resolved in the receiver's language.
   *
   * @param notificationContext the context carrying the {@link NotificationInfo}
   *          produced by the plugin
   * @return the {@link MessageInfo} the mail channel sends
   */
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
    // The subject is what processSubject RETURNS: it renders the
    // Notification.subject.NewEmailsNotificationPlugin bundle entry, substituting
    // the $VARIABLES put in the context above. Reading "SUBJECT" back out of the
    // context instead would only echo what we just put there, so the translated
    // subject would never be used - and the mail would go out with no subject at
    // all whenever that value is missing, which is exactly what happened here.
    messageInfo.subject(TemplateUtils.processSubject(templateContext));
    messageInfo.body(TemplateUtils.processGroovy(templateContext));
    notificationContext.setException(templateContext.getException());
    return messageInfo.end();
  }
}
