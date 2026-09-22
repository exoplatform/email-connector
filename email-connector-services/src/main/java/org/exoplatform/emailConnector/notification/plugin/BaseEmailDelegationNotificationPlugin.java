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

import org.exoplatform.commons.api.notification.model.ArgumentLiteral;
import org.exoplatform.commons.api.notification.plugin.BaseNotificationPlugin;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * What the two mailbox-delegation notifications have in common (EXO-90503): the other
 * party's name, the receiver's language, and the escaping the mail channel's HTML
 * template needs.
 * <p>
 * The payload of both is a <b>username</b> and a <b>code</b>, never a sentence built
 * where the event was raised: the notification is rendered in the receiver's language,
 * which is not the actor's, and the web renderer re-resolves the same two values for
 * itself.
 */
public abstract class BaseEmailDelegationNotificationPlugin extends BaseNotificationPlugin {

  private static final Log                    LOG      = ExoLogger.getLogger(BaseEmailDelegationNotificationPlugin.class);

  /** Who the notification goes to: the party that did not act. */
  public static final ArgumentLiteral<String> RECEIVER = new ArgumentLiteral<>(String.class, "receiver");

  /** The other party's eXo username -- the one whose act is being told about. */
  public static final ArgumentLiteral<String> ACTOR    = new ArgumentLiteral<>(String.class, "actor");

  /** The delegation row's id, so a click can open the share the notification is about. */
  public static final ArgumentLiteral<String> DELEGATION_ID = new ArgumentLiteral<>(String.class, "delegationId");

  /**
   * @param initParams the kernel's plugin parameters
   */
  protected BaseEmailDelegationNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * The receiver's language, as the platform holds it.
   *
   * @param receiver the receiver's username
   * @return the locale
   */
  protected Locale localeOf(String receiver) {
    return Locale.of(NotificationPluginUtils.getLanguage(receiver));
  }

  /**
   * A bundle entry in the receiver's language.
   *
   * @param key the bundle key
   * @param locale the receiver's locale
   * @return the words, or an empty string when the bundle lacks the key
   */
  protected String translate(String key, Locale locale) {
    ResourceBundleService bundles = CommonsUtils.getService(ResourceBundleService.class);
    return bundles == null ? "" : StringUtils.defaultString(bundles.getSharedString(key, locale));
  }

  /**
   * The other party as a person rather than a login, escaped for the HTML the mail
   * channel's template renders. A name nobody can resolve falls back to the username:
   * a notification naming a login is poor, one naming nobody is a defect.
   *
   * @param username the eXo username, possibly null
   * @return the display name, escaped, never null
   */
  protected String displayName(String username) {
    if (StringUtils.isBlank(username)) {
      return "";
    }
    String fullName = username;
    try {
      IdentityManager identityManager = CommonsUtils.getService(IdentityManager.class);
      Identity identity = identityManager == null ? null : identityManager.getOrCreateUserIdentity(username);
      Profile profile = identity == null ? null : identity.getProfile();
      if (profile != null && StringUtils.isNotBlank(profile.getFullName())) {
        fullName = profile.getFullName();
      }
    } catch (RuntimeException e) {
      LOG.debug("The display name of user {} could not be resolved for a delegation notification", username, e);
    }
    return HtmlUtils.htmlEscape(fullName);
  }
}
