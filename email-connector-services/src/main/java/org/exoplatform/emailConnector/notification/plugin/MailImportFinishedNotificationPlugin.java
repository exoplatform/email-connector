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
 * Tells a user that their mail import ended (EXO-90846), with how many mails were added,
 * skipped as already there, and refused, and whether the run stopped early or broke.
 * <p>
 * The payload is the folder key, the folder's own name for a folder of the user's (their
 * text, escaped where it becomes HTML), the counts, the status and a message code --
 * never a file name or anything read from the imported files. The plugin writes the
 * sentence in the receiver's language; the web rendering rebuilds it from the same
 * values.
 */
public class MailImportFinishedNotificationPlugin extends BaseNotificationPlugin {

  /** Who the notification goes to: the user who imported. */
  public static final ArgumentLiteral<String> RECEIVER          = new ArgumentLiteral<>(String.class, "receiver");

  /** The folder key the mails went into. */
  public static final ArgumentLiteral<String> FOLDER            = new ArgumentLiteral<>(String.class, "folder");

  /** The folder's own name, for a folder that is not a built-in; empty otherwise. */
  public static final ArgumentLiteral<String> FOLDER_NAME       = new ArgumentLiteral<>(String.class, "folderName");

  /** How the run ended: SUCCESS or FAILURE. */
  public static final ArgumentLiteral<String> STATUS            = new ArgumentLiteral<>(String.class, "status");

  /** How many mails were added. */
  public static final ArgumentLiteral<String> ADDED             = new ArgumentLiteral<>(String.class, "added");

  /** How many mails were skipped as already there. */
  public static final ArgumentLiteral<String> SKIPPED           = new ArgumentLiteral<>(String.class, "skipped");

  /** How many mails were refused. */
  public static final ArgumentLiteral<String> REFUSED           = new ArgumentLiteral<>(String.class, "refused");

  /** The message code of what cut the run short or broke it; empty otherwise. */
  public static final ArgumentLiteral<String> MESSAGE_CODE      = new ArgumentLiteral<>(String.class, "messageCode");

  /** The notification parameter carrying the folder key. */
  public static final String                  FOLDER_PARAM      = "FOLDER";

  /** The notification parameter carrying the folder's own name. */
  public static final String                  FOLDER_NAME_PARAM = "FOLDER_NAME";

  /** The notification parameter carrying the status. */
  public static final String                  STATUS_PARAM      = "STATUS";

  /** The notification parameter carrying the added count. */
  public static final String                  ADDED_PARAM       = "ADDED";

  /** The notification parameter carrying the skipped count. */
  public static final String                  SKIPPED_PARAM     = "SKIPPED";

  /** The notification parameter carrying the refused count. */
  public static final String                  REFUSED_PARAM     = "REFUSED";

  /** The notification parameter carrying the message code. */
  public static final String                  MESSAGE_CODE_PARAM = "MESSAGE_CODE";

  private static final String                 KEY_PREFIX        = "mailImportFinished.notification.";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public MailImportFinishedNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.MAIL_IMPORT_FINISHED_NOTIFICATION_PLUGIN;
  }

  /**
   * A notification needs somebody to go to and a status to tell.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNotBlank(ctx.value(STATUS));
  }

  /**
   * Builds the notification in the receiver's language: a title, and a sentence naming
   * the folder and giving the three counts -- "stopped" rather than "done" for a run that
   * broke, and a word that it stopped early when a limit cut it short.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String folder = StringUtils.defaultString(ctx.value(FOLDER));
    String folderName = StringUtils.defaultString(ctx.value(FOLDER_NAME));
    String status = ctx.value(STATUS);
    String messageCode = StringUtils.defaultString(ctx.value(MESSAGE_CODE));
    Locale locale = Locale.of(NotificationPluginUtils.getLanguage(receiver));
    ResourceBundleService bundles = CommonsUtils.getService(ResourceBundleService.class);
    String title = bundles.getSharedString(KEY_PREFIX + "title", locale);
    String shownFolder = StringUtils.isNotBlank(folderName) ? HtmlUtils.htmlEscape(folderName)
                                                            : StringUtils.defaultIfBlank(bundles.getSharedString(KEY_PREFIX
                                                                + "folder." + folder, locale), folder);
    String contentKey;
    if ("FAILURE".equals(status)) {
      contentKey = "contentFailed";
    } else if (StringUtils.isNotBlank(messageCode)) {
      contentKey = "contentCutShort";
    } else {
      contentKey = "content";
    }
    String content = StringUtils.defaultString(bundles.getSharedString(KEY_PREFIX + contentKey, locale))
                                .replace("{0}", shownFolder)
                                .replace("{1}", count(ctx.value(ADDED)))
                                .replace("{2}", count(ctx.value(SKIPPED)))
                                .replace("{3}", count(ctx.value(REFUSED)));
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title)
                           .with(NotificationConstants.CONTENT, content)
                           .with(FOLDER_PARAM, folder)
                           .with(FOLDER_NAME_PARAM, folderName)
                           .with(STATUS_PARAM, status)
                           .with(ADDED_PARAM, count(ctx.value(ADDED)))
                           .with(SKIPPED_PARAM, count(ctx.value(SKIPPED)))
                           .with(REFUSED_PARAM, count(ctx.value(REFUSED)))
                           .with(MESSAGE_CODE_PARAM, messageCode)
                           .with(NotificationConstants.LINK, EmailConnectorUtils.getEmailsLink(receiver))
                           .key(getKey())
                           .end();
  }

  /**
   * A count as the payload carries it, digits only.
   *
   * @param value the value, possibly null
   * @return the count, "0" when the value is not one
   */
  private static String count(String value) {
    return StringUtils.isNumeric(value) ? value : "0";
  }
}
