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
 * Tells the owner of a mailbox every time where her mail goes changes (EXO-90656): a
 * forward set, changed or removed from eXo, a rule that forwards saved or removed, and a
 * forward eXo did not set found on her mail server. The detection half of the forwarding
 * safeguards: whoever made the change, the owner hears of it on the channels she
 * follows, beside the mail eXo drops into the mailbox itself.
 * <p>
 * The payload is what changed, the destination and the rule's or script's name; the
 * sentence is built in the receiver's language here and read as it is by the web and
 * the push renderings.
 */
public class EmailForwardingNotificationPlugin extends BaseNotificationPlugin {

  /** What happened to where the mail goes. */
  public enum Change {
    /** A forward was set from eXo. */
    SET,
    /** The forward set from eXo now goes elsewhere. */
    CHANGED,
    /** The forward set from eXo was removed. */
    REMOVED,
    /** A rule that forwards was saved, or switched on. */
    RULE_SET,
    /** A rule now forwards elsewhere. */
    RULE_CHANGED,
    /** A rule that forwarded was removed, or switched off. */
    RULE_REMOVED,
    /** The mail server forwards mail to addresses eXo did not set. */
    FOREIGN,
    /** Another client's script on the mail server may forward mail. */
    FOREIGN_SCRIPT
  }

  /** Who the notification goes to: the mailbox's owner. */
  public static final ArgumentLiteral<String> RECEIVER    = new ArgumentLiteral<>(String.class, "receiver");

  /** A {@link Change} name. */
  public static final ArgumentLiteral<String> CHANGE      = new ArgumentLiteral<>(String.class, "change");

  /** Where the mail is, or was, forwarded; blank when not known. */
  public static final ArgumentLiteral<String> DESTINATION = new ArgumentLiteral<>(String.class, "destination");

  /** The rule's or the other client's script's name; blank when none. */
  public static final ArgumentLiteral<String> SOURCE      = new ArgumentLiteral<>(String.class, "source");

  /** The title's key. */
  public static final String                  TITLE_KEY   = "emailForwarding.notification.title";

  /** The sentence's key prefix, followed by the {@link Change} name. */
  public static final String                  CONTENT_KEY = "emailForwarding.notification.content.";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public EmailForwardingNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * The plugin's id.
   *
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_FORWARDING_NOTIFICATION_PLUGIN;
  }

  /**
   * A notification needs somebody to go to and a change it knows.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && change(ctx.value(CHANGE)) != null;
  }

  /**
   * Builds the notification in the receiver's language: a title, the sentence naming the
   * destination and the rule or script, and the link to the mailbox.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    Change change = change(ctx.value(CHANGE));
    String destination = StringUtils.defaultString(ctx.value(DESTINATION));
    String source = StringUtils.defaultString(ctx.value(SOURCE));
    Locale locale = Locale.of(NotificationPluginUtils.getLanguage(receiver));
    ResourceBundleService bundles = CommonsUtils.getService(ResourceBundleService.class);
    return NotificationInfo.instance()
                           .setFrom("")
                           .to(receiver)
                           .with(NotificationConstants.TITLE, bundles.getSharedString(TITLE_KEY, locale))
                           .with(NotificationConstants.CONTENT, sentence(bundles, locale, change, destination, source))
                           .with(NotificationConstants.FORWARDING_CHANGE, change.name())
                           .with(NotificationConstants.FORWARDING_DESTINATION, destination)
                           .with(NotificationConstants.FORWARDING_SOURCE, source)
                           .with(NotificationConstants.LINK, link(receiver))
                           .key(getKey())
                           .end();
  }

  /**
   * The link a click follows by mail or push: the mailbox's deep link opening the
   * forwarding drawer, where the forward is shown and managed -- not the mailbox's list.
   *
   * @param receiver the owner
   * @return the link
   */
  static String link(String receiver) {
    return EmailConnectorUtils.getEmailsLink(receiver) + "&forwarding=true";
  }

  /**
   * The sentence of a change in a language, the destination and the name escaped: the
   * sentence is HTML in the mail channel's template, and both are text somebody typed.
   *
   * @param bundles the resource bundles
   * @param locale the language
   * @param change the change
   * @param destination where the mail goes
   * @param source the rule's or script's name
   * @return the sentence, HTML
   */
  public static String sentence(ResourceBundleService bundles, Locale locale, Change change, String destination, String source) {
    // A script without a name (Stalwart's JMAP one) is said without one.
    String key = CONTENT_KEY + change.name() + (change == Change.FOREIGN_SCRIPT && StringUtils.isBlank(source) ? ".nameless" : "");
    return StringUtils.defaultString(bundles.getSharedString(key, locale))
                      .replace("{0}", HtmlUtils.htmlEscape(StringUtils.defaultString(destination)))
                      .replace("{1}", HtmlUtils.htmlEscape(StringUtils.defaultString(source)));
  }

  /**
   * A change by its name.
   *
   * @param name the name
   * @return the change, or null when unknown
   */
  private static Change change(String name) {
    try {
      return name == null ? null : Change.valueOf(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
