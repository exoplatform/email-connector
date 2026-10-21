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

import java.util.List;
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
 * Tells the owner of a mail filter that it matched new mail (EXO-90654): one
 * notification per filter per pass, the filter's name and the count -- or, from the
 * assistant's glue, one line of the assistant's about one mail. Only a filter with the
 * "Notify me" action notifies; a server rule never does, eXo never sees what it did.
 * <p>
 * The payload is the filter's name, the count and the line, never the mail's content;
 * the sentence is built in the receiver's language here and read as it is by the web
 * and the push renderings. It also names the one mail a click opens -- the most recent
 * of the pass -- by its inbox UID while it is still there, or the built-in folder the
 * filter filed it into; the link carries the same, as the deep link the mailbox reads.
 */
public class EmailFilterNotificationPlugin extends BaseNotificationPlugin {

  /** Who the notification goes to: the filter's owner. */
  public static final ArgumentLiteral<String> RECEIVER    = new ArgumentLiteral<>(String.class, "receiver");

  /** The filter's name. */
  public static final ArgumentLiteral<String> FILTER_NAME = new ArgumentLiteral<>(String.class, "filterName");

  /** How many mails it matched. */
  public static final ArgumentLiteral<String> COUNT       = new ArgumentLiteral<>(String.class, "count");

  /** The assistant's one line about the mail, or blank. */
  public static final ArgumentLiteral<String> LINE        = new ArgumentLiteral<>(String.class, "line");

  /** The inbox UID of the mail a click opens, or blank when it left the inbox. */
  public static final ArgumentLiteral<String> MAIL_REMOTE_ID = new ArgumentLiteral<>(String.class, "mailRemoteId");

  /** The built-in folder the filter filed that mail into, or blank. */
  public static final ArgumentLiteral<String> MAIL_FOLDER = new ArgumentLiteral<>(String.class, "mailFolder");

  private static final String                 TITLE_KEY   = "emailFilter.notification.title";

  private static final String                 ONE_KEY     = "emailFilter.notification.content.one";

  private static final String                 MANY_KEY    = "emailFilter.notification.content.many";

  /** The folders the mailbox's deep link opens on, a filter may file into. */
  private static final List<String>           OPENABLE_FOLDERS = List.of("JUNK", "TRASH");

  /**
   * @param initParams the kernel's plugin parameters
   */
  public EmailFilterNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * The plugin's id.
   *
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_FILTER_NOTIFICATION_PLUGIN;
  }

  /**
   * A notification needs somebody to go to and a filter to name.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNotBlank(ctx.value(FILTER_NAME));
  }

  /**
   * Builds the notification in the receiver's language: a title, a sentence naming the
   * filter and the count, or the assistant's line, and the link that opens the mail it
   * names -- or the folder it was filed into, or the mailbox when it names neither.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String filterName = ctx.value(FILTER_NAME);
    String line = ctx.value(LINE);
    int count = parseCount(ctx.value(COUNT));
    Locale locale = Locale.of(NotificationPluginUtils.getLanguage(receiver));
    ResourceBundleService bundles = CommonsUtils.getService(ResourceBundleService.class);
    String title = bundles.getSharedString(TITLE_KEY, locale);
    // Escaped: the sentence is HTML in the mail channel's template, and a filter's name
    // and an assistant's line are text somebody typed or generated.
    String sentence = StringUtils.defaultString(bundles.getSharedString(count == 1 ? ONE_KEY : MANY_KEY, locale))
                                 .replace("{0}", HtmlUtils.htmlEscape(filterName))
                                 .replace("{1}", String.valueOf(count));
    String content = StringUtils.isBlank(line) ? sentence : sentence + " " + HtmlUtils.htmlEscape(line);
    String mailRemoteId = digits(ctx.value(MAIL_REMOTE_ID));
    String mailFolder = mailRemoteId == null ? builtInFolder(ctx.value(MAIL_FOLDER)) : null;
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title)
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.FILTER_NAME, filterName)
                           .with(NotificationConstants.FILTER_COUNT, String.valueOf(count))
                           .with(NotificationConstants.MAIL_REMOTE_ID, StringUtils.defaultString(mailRemoteId))
                           .with(NotificationConstants.MAIL_FOLDER, StringUtils.defaultString(mailFolder))
                           .with(NotificationConstants.LINK, link(receiver, mailRemoteId, mailFolder))
                           .key(getKey())
                           .end();
  }

  /**
   * The link a click follows: the mailbox's deep link, opening the mail by its inbox UID,
   * or the folder the mail was filed into, or the mailbox alone.
   *
   * @param receiver the owner
   * @param mailRemoteId the mail's inbox UID, or null
   * @param mailFolder the built-in folder, or null
   * @return the link
   */
  private static String link(String receiver, String mailRemoteId, String mailFolder) {
    String link = EmailConnectorUtils.getEmailsLink(receiver);
    if (mailRemoteId != null) {
      return link + "&mailRemoteId=" + mailRemoteId;
    }
    return mailFolder == null ? link : link + "&folder=" + mailFolder;
  }

  /**
   * A UID as the link may carry it: digits only.
   *
   * @param value the value
   * @return the digits, or null when the value is not a UID
   */
  private static String digits(String value) {
    String trimmed = StringUtils.trimToNull(value);
    return trimmed != null && StringUtils.isNumeric(trimmed) ? trimmed : null;
  }

  /**
   * A folder as the link may carry it: one of the built-in folders the mailbox opens on,
   * never a folder key of the user's own, which the deep link does not open.
   *
   * @param value the value
   * @return the folder, or null
   */
  private static String builtInFolder(String value) {
    String folder = StringUtils.trimToNull(value);
    return folder != null && OPENABLE_FOLDERS.contains(folder) ? folder : null;
  }

  /**
   * The count, one when unreadable.
   *
   * @param value the value
   * @return the count
   */
  private static int parseCount(String value) {
    try {
      return Math.max(1, Integer.parseInt(StringUtils.trimToEmpty(value)));
    } catch (NumberFormatException e) {
      return 1;
    }
  }
}
