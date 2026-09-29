/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.notification.plugin;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.ArgumentLiteral;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.plugin.BaseNotificationPlugin;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.service.EmailFilterSuggestionDigest;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.ResourceBundleService;

/**
 * Tells the owner that suggestions of their mail filters' assistant wait for them
 * (EXO-90668): one digest, "N suggestions waiting", never one notification per
 * suggestion. The digest is kept one per user by its sender
 * ({@code EmailFilterSuggestionDigest}), which updates an unread one in place and
 * replaces a read one; this plugin only builds it.
 * <p>
 * The payload is the count, never a mail's content nor a suggestion's arguments; the
 * sentence is built in the receiver's language here and read as it is by the web and
 * push renderings. A click opens the mailbox, whose list marks the mails with waiting
 * suggestions.
 */
public class EmailFilterSuggestionsNotificationPlugin extends BaseNotificationPlugin {

  /** Who the notification goes to: the rules' owner. */
  public static final ArgumentLiteral<String> RECEIVER  = new ArgumentLiteral<>(String.class, "receiver");

  /** How many suggestions wait. */
  public static final ArgumentLiteral<String> COUNT     = new ArgumentLiteral<>(String.class, "count");

  /** The heading's key. */
  static final String                         TITLE_KEY = "emailFilterSuggestions.notification.title";

  /** The sentence's key, for one suggestion. */
  static final String                         ONE_KEY   = "emailFilterSuggestions.notification.content.one";

  /** The sentence's key, for several. */
  static final String                         MANY_KEY  = "emailFilterSuggestions.notification.content.many";

  /**
   * The plugin, as the kernel declares it.
   *
   * @param initParams the kernel's plugin parameters
   */
  public EmailFilterSuggestionsNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * The plugin's id.
   *
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_FILTER_SUGGESTIONS_NOTIFICATION_PLUGIN;
  }

  /**
   * A digest needs somebody to go to and at least one suggestion.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && parseCount(ctx.value(COUNT)) > 0;
  }

  /**
   * Builds the digest in the receiver's language: a heading, "N suggestions waiting" with
   * the count the receiver's latest publish recorded, and the link to the mailbox --
   * unless the receiver already holds an unread digest,
   * which is then updated in place with the count and none is built: the platform builds
   * a digest on its own executor, after the sender looked, so a second run close to the
   * first would otherwise stack a second digest.
   *
   * @param ctx the notification context
   * @return the notification, or null when an unread digest took the count or nothing
   *         waits any more
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    int count = (int) EmailFilterSuggestionDigest.latestWaiting(CommonsUtils.getService(SettingService.class),
                                                                receiver,
                                                                parseCount(ctx.value(COUNT)));
    if (count <= 0) {
      return null;
    }
    if (EmailFilterSuggestionDigest.updateUnread(CommonsUtils.getService(WebNotificationService.class), receiver, count)) {
      return null;
    }
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title(receiver))
                           .with(NotificationConstants.CONTENT, content(receiver, count))
                           .with(NotificationConstants.SUGGESTION_COUNT, String.valueOf(count))
                           .with(NotificationConstants.LINK, EmailConnectorUtils.getEmailsLink(receiver))
                           .key(getKey())
                           .end();
  }

  /**
   * The digest's heading, in the receiver's language.
   *
   * @param receiver the receiver
   * @return the heading
   */
  public static String title(String receiver) {
    return StringUtils.defaultString(bundles().getSharedString(TITLE_KEY, locale(receiver)));
  }

  /**
   * The digest's sentence, in the receiver's language: "1 suggestion waiting", or
   * "N suggestions waiting".
   *
   * @param receiver the receiver
   * @param count how many wait
   * @return the sentence
   */
  public static String content(String receiver, int count) {
    return StringUtils.defaultString(bundles().getSharedString(count == 1 ? ONE_KEY : MANY_KEY, locale(receiver)))
                      .replace("{0}", String.valueOf(count));
  }

  /**
   * The count, 0 when unreadable.
   *
   * @param value the value
   * @return the count
   */
  static int parseCount(String value) {
    try {
      return Math.max(0, Integer.parseInt(StringUtils.trimToEmpty(value)));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * The receiver's locale.
   *
   * @param receiver the receiver
   * @return the locale
   */
  private static Locale locale(String receiver) {
    return Locale.of(NotificationPluginUtils.getLanguage(receiver));
  }

  /**
   * The platform's bundles.
   *
   * @return the service
   */
  private static ResourceBundleService bundles() {
    return CommonsUtils.getService(ResourceBundleService.class);
  }
}
