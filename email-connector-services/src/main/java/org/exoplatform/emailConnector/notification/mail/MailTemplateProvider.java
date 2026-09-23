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

import org.exoplatform.commons.api.notification.annotation.TemplateConfig;
import org.exoplatform.commons.api.notification.annotation.TemplateConfigs;
import org.exoplatform.commons.api.notification.channel.template.TemplateProvider;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.ResourceBundleService;

@TemplateConfigs(templates = {
    @TemplateConfig(pluginId = NotificationConstants.NEW_EMAILS_NOTIFICATION_PLUGIN, template = "war:/conf/email-connector/templates/notification/mail/NewEmailsNotificationPlugin.gtmpl"),
    @TemplateConfig(pluginId = NotificationConstants.SCHEDULED_EMAIL_FAILED_NOTIFICATION_PLUGIN, template = "war:/conf/email-connector/templates/notification/mail/ScheduledEmailFailedNotificationPlugin.gtmpl"),
    @TemplateConfig(pluginId = NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN, template = "war:/conf/email-connector/templates/notification/mail/EmailDelegationInvitationPlugin.gtmpl"),
    @TemplateConfig(pluginId = NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN, template = "war:/conf/email-connector/templates/notification/mail/EmailDelegationResponseNotificationPlugin.gtmpl") })
public class MailTemplateProvider extends TemplateProvider {

  public MailTemplateProvider(InitParams initParams, ResourceBundleService resourceBundleService) {
    super(initParams);
    this.templateBuilders.put(PluginKey.key(NotificationConstants.NEW_EMAILS_NOTIFICATION_PLUGIN), new MailTemplateBuilder(this));
    this.templateBuilders.put(PluginKey.key(NotificationConstants.SCHEDULED_EMAIL_FAILED_NOTIFICATION_PLUGIN),
                              new MailTemplateBuilder(this));
    this.templateBuilders.put(PluginKey.key(NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN),
                              new MailTemplateBuilder(this));
    this.templateBuilders.put(PluginKey.key(NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN),
                              new MailTemplateBuilder(this));
  }
}
