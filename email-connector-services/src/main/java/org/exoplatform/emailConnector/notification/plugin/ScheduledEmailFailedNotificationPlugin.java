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
package org.exoplatform.emailConnector.notification.plugin;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

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

/**
 * Tells the owner of a scheduled mail that it was not sent, or that its sending could
 * not be confirmed (EXO-90434). A successful send notifies nobody: the mail simply
 * moves to Sent.
 * <p>
 * The payload is the mail's subject and a reason CODE -- never the mail server's own
 * words, which may carry addresses or internal host names -- and the plugin turns the
 * code into the receiver's language. The web rendering reads the same two values.
 */
public class ScheduledEmailFailedNotificationPlugin extends BaseNotificationPlugin {

  /** Who the notification goes to: the owner of the scheduled mail. */
  public static final ArgumentLiteral<String> RECEIVER          = new ArgumentLiteral<>(String.class, "receiver");

  /** The scheduled mail's subject. */
  public static final ArgumentLiteral<String> SUBJECT           = new ArgumentLiteral<>(String.class, "subject");

  /** The reason code: a {@code ScheduledSendError} name. */
  public static final ArgumentLiteral<String> REASON            = new ArgumentLiteral<>(String.class, "reason");

  private static final String                 TITLE_KEY         = "scheduledEmailFailed.notification.title";

  private static final String                 CONTENT_KEY       = "scheduledEmailFailed.notification.content";

  private static final String                 REASON_KEY_PREFIX = "scheduledEmailFailed.notification.reason.";

  private static final String                 NO_SUBJECT_KEY    = "scheduledEmailFailed.notification.noSubject";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public ScheduledEmailFailedNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.SCHEDULED_EMAIL_FAILED_NOTIFICATION_PLUGIN;
  }

  /**
   * A notification needs somebody to go to and a reason to give.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNotBlank(ctx.value(REASON));
  }

  /**
   * Builds the notification in the receiver's language: a title, a sentence naming the
   * mail by its subject and the reason, and the link to the mailbox.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String subject = ctx.value(SUBJECT);
    String reason = ctx.value(REASON);
    Locale locale = Locale.of(NotificationPluginUtils.getLanguage(receiver));
    ResourceBundleService bundles = CommonsUtils.getService(ResourceBundleService.class);
    String title = bundles.getSharedString(TITLE_KEY, locale);
    // Escaped: the sentence is HTML in the mail channel's template, and a subject is
    // text the user typed.
    String shownSubject = StringUtils.isBlank(subject) ? bundles.getSharedString(NO_SUBJECT_KEY, locale)
                                                       : HtmlUtils.htmlEscape(subject);
    String reasonText = StringUtils.defaultIfBlank(bundles.getSharedString(REASON_KEY_PREFIX + reason, locale), reason);
    String content = StringUtils.defaultString(bundles.getSharedString(CONTENT_KEY, locale))
                                .replace("{0}", shownSubject)
                                .replace("{1}", reasonText);
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title)
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.SUBJECT, StringUtils.defaultString(subject))
                           .with(NotificationConstants.REASON, reason)
                           .with(NotificationConstants.LINK, EmailConnectorUtils.getEmailsLink(receiver))
                           .key(getKey())
                           .end();
  }
}
