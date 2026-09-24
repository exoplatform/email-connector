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
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;

/**
 * Tells a delegate that new mail arrived in a mailbox somebody shared with them, when
 * they asked for it on that share (EXO-90553, delegation plan phase 2 slice 2.5).
 * <p>
 * Not {@link NewEmailsNotificationPlugin}: that one's sentence and link are the
 * receiver's own mailbox. This one names the owner first ("Anne Dupont's mailbox: 3
 * new messages") and links to the shared mailbox itself, through the
 * {@code mailbox=<delegationId>} deep link the mail drawer opens on. Its own plugin id
 * also gives it its own line in the user's notification settings, so a delegate can
 * keep their own mail's notification and silence the shared one, or the reverse.
 */
public class DelegatedNewEmailsNotificationPlugin extends BaseEmailDelegationNotificationPlugin {

  /** The owner's mailbox address: the name used when the owner is not an eXo user. */
  public static final ArgumentLiteral<String> OWNER_MAILBOX = new ArgumentLiteral<>(String.class, "ownerMailbox");

  /** How many new unread messages, a positive number. */
  public static final ArgumentLiteral<String> NEW_EMAILS    = new ArgumentLiteral<>(String.class, "newEmails");

  private static final String                 TITLE_KEY     = "delegatedNewEmails.notification.title";

  private static final String                 CONTENT_KEY   = "delegatedNewEmails.notification.content";

  private static final String                 CONTENT_ONE_KEY = "delegatedNewEmails.notification.content.one";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public DelegatedNewEmailsNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.DELEGATED_NEW_EMAILS_NOTIFICATION_PLUGIN;
  }

  /**
   * A notification needs a receiver, the share it is about, somebody to name -- the
   * owner or at least their address -- and a positive count: without the share the link
   * opens the wrong mailbox, without a name the sentence is about nobody.
   *
   * @param ctx the notification context
   * @return whether the context carries all of it
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNumeric(ctx.value(DELEGATION_ID))
        && (StringUtils.isNotBlank(ctx.value(ACTOR)) || StringUtils.isNotBlank(ctx.value(OWNER_MAILBOX)))
        && countOf(ctx.value(NEW_EMAILS)) > 0;
  }

  /**
   * Builds the notification in the receiver's language: whose mailbox, how many new
   * messages, and the link that opens that mailbox.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String owner = StringUtils.defaultString(ctx.value(ACTOR));
    String ownerMailbox = StringUtils.defaultString(ctx.value(OWNER_MAILBOX));
    String delegationId = ctx.value(DELEGATION_ID);
    long count = countOf(ctx.value(NEW_EMAILS));
    Locale locale = localeOf(receiver);
    String ownerName = StringUtils.isNotBlank(owner) ? displayName(owner) : HtmlUtils.htmlEscape(ownerMailbox);
    String content = count == 1 ? translate(CONTENT_ONE_KEY, locale).replace("{0}", ownerName)
                                : translate(CONTENT_KEY, locale).replace("{0}", ownerName).replace("{1}", String.valueOf(count));
    return NotificationInfo.instance()
                           .setFrom(owner)
                           .to(receiver)
                           .with(NotificationConstants.TITLE, translate(TITLE_KEY, locale))
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.DELEGATION_ACTOR, owner)
                           .with(NotificationConstants.DELEGATION_ID, delegationId)
                           .with(NotificationConstants.LINK, sharedMailboxLink(receiver, delegationId))
                           .key(getKey())
                           .end();
  }

  /**
   * The link that opens the mail drawer on the shared mailbox: the mailbox link with the
   * {@code mailbox=} parameter the drawer reads.
   *
   * @param receiver the delegate
   * @param delegationId the share
   * @return the link
   */
  static String sharedMailboxLink(String receiver, String delegationId) {
    return EmailConnectorUtils.getEmailsLink(receiver) + "&mailbox=" + delegationId;
  }

  /**
   * The count the context carries, or zero when it carries none that reads as one.
   *
   * @param value the context value
   * @return the count
   */
  private static long countOf(String value) {
    try {
      return StringUtils.isBlank(value) ? 0 : Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
