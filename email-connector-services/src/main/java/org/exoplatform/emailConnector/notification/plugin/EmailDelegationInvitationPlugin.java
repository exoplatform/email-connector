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

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.ArgumentLiteral;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;

/**
 * Tells someone that a colleague shared their mailbox with them, and that it is
 * waiting for an answer (EXO-90503, delegation plan section 7.2).
 * <p>
 * It goes to the <b>grantee</b>, always: the one server observed to notify by itself
 * mails the <i>owner</i> about rights changes, not the grantee (plan section 5.1 step
 * 5), so there is nothing here to double up on. The owner-facing answer is the other
 * plugin, {@link EmailDelegationResponseNotificationPlugin}, and that one is gated.
 * <p>
 * The share exists on the server the moment this is sent -- the grant is written at
 * invite, not at accept -- so the invitation is an invitation to <i>use</i> access
 * already granted, not a request for permission. The wording says so, and says that
 * declining leaves the access in place, because it does (plan section 5.3).
 */
public class EmailDelegationInvitationPlugin extends BaseEmailDelegationNotificationPlugin {

  /** The preset the share carries: a {@code DelegationPreset} name. */
  public static final ArgumentLiteral<String> PRESET            = new ArgumentLiteral<>(String.class, "preset");

  private static final String                 TITLE_KEY         = "emailDelegationInvitation.notification.title";

  private static final String                 CONTENT_KEY       = "emailDelegationInvitation.notification.content";

  private static final String                 PRESET_KEY_PREFIX = "emailDelegationInvitation.notification.preset.";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public EmailDelegationInvitationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_DELEGATION_INVITATION_NOTIFICATION_PLUGIN;
  }

  /**
   * An invitation needs somebody to go to and somebody it is from; without the owner
   * the sentence names nobody, which is worse than no notification.
   *
   * @param ctx the notification context
   * @return whether the context carries both
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNotBlank(ctx.value(ACTOR));
  }

  /**
   * Builds the invitation in the receiver's language: who shared, with which preset,
   * and the link to the mailbox where the settings screen answers it.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String owner = ctx.value(ACTOR);
    String preset = StringUtils.defaultString(ctx.value(PRESET));
    String delegationId = StringUtils.defaultString(ctx.value(DELEGATION_ID));
    Locale locale = localeOf(receiver);
    String presetText = translate(PRESET_KEY_PREFIX + preset, locale);
    String content = translate(CONTENT_KEY, locale).replace("{0}", displayName(owner)).replace("{1}", presetText);
    return NotificationInfo.instance()
                           .setFrom(StringUtils.defaultString(owner))
                           .to(receiver)
                           .with(NotificationConstants.TITLE, translate(TITLE_KEY, locale))
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.DELEGATION_ACTOR, StringUtils.defaultString(owner))
                           .with(NotificationConstants.DELEGATION_PRESET, preset)
                           .with(NotificationConstants.DELEGATION_ID, delegationId)
                           .with(NotificationConstants.LINK, EmailConnectorUtils.getEmailsLink(receiver))
                           .key(getKey())
                           .end();
  }
}
