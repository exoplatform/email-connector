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
 * Tells one party of a share what the other party did with it (EXO-90503): the owner
 * that the grantee accepted, declined or left, and the grantee that the owner took the
 * access away -- or set, changed or withdrew her consent to them writing mail in her
 * name (EXO-90582, {@code SEND_MODE_ON_BEHALF|AS|NONE}).
 * <p>
 * One plugin and one notification preference for every change in where a share stands
 * -- four transitions and the owner's consent to writing in her name -- because to the
 * person reading it they are one kind of news, and splitting them would make a user
 * switch off "declined" and lose "revoked" with it. {@link #RESPONSE} carries which news
 * it is, and the sentence is chosen from it in the receiver's language.
 * <p>
 * <b>The owner-facing ones are gated, and the gate is not here.</b> A server that
 * e-mails the owner on every rights change of its own accord -- BlueMind does, four
 * such mails were observed over two phase-0 rounds -- would leave that owner with two
 * messages for one act, so the lifecycle glue asks the engine before dispatching
 * (plan sections 5.1 and 13.F). This plugin renders whatever reaches it; the decision
 * of whether anything should is {@code EmailDelegationNotificationListener}'s, which
 * is the layer that knows the connector.
 */
public class EmailDelegationResponseNotificationPlugin extends BaseEmailDelegationNotificationPlugin {

  /**
   * Which news: an {@code EmailDelegationEvent.Type} name, or {@code SEND_MODE_} and the
   * consent to writing in the owner's name as it now stands.
   */
  public static final ArgumentLiteral<String> RESPONSE            = new ArgumentLiteral<>(String.class, "response");

  private static final String                 TITLE_KEY_PREFIX    = "emailDelegationResponse.notification.title.";

  private static final String                 CONTENT_KEY_PREFIX  = "emailDelegationResponse.notification.content.";

  private static final String                 FALLBACK_TITLE_KEY  = "emailDelegationResponse.notification.title";

  /**
   * @param initParams the kernel's plugin parameters
   */
  public EmailDelegationResponseNotificationPlugin(InitParams initParams) {
    super(initParams);
  }

  /**
   * @return the plugin id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_DELEGATION_RESPONSE_NOTIFICATION_PLUGIN;
  }

  /**
   * The news needs somebody to go to, somebody it is about, and which news it is --
   * without the transition there is no sentence to write.
   *
   * @param ctx the notification context
   * @return whether the context carries all three
   */
  @Override
  public boolean isValid(NotificationContext ctx) {
    return StringUtils.isNotBlank(ctx.value(RECEIVER)) && StringUtils.isNotBlank(ctx.value(ACTOR))
        && StringUtils.isNotBlank(ctx.value(RESPONSE));
  }

  /**
   * Builds the news in the receiver's language: who did what, and the link to the
   * mailbox whose settings screen shows the share.
   *
   * @param ctx the notification context
   * @return the notification
   */
  @Override
  protected NotificationInfo makeNotification(NotificationContext ctx) {
    String receiver = ctx.value(RECEIVER);
    String actor = ctx.value(ACTOR);
    String response = StringUtils.defaultString(ctx.value(RESPONSE));
    String delegationId = StringUtils.defaultString(ctx.value(DELEGATION_ID));
    Locale locale = localeOf(receiver);
    String title = StringUtils.defaultIfBlank(translate(TITLE_KEY_PREFIX + response, locale),
                                              translate(FALLBACK_TITLE_KEY, locale));
    String content = translate(CONTENT_KEY_PREFIX + response, locale).replace("{0}", displayName(actor));
    return NotificationInfo.instance()
                           .setFrom(StringUtils.defaultString(actor))
                           .to(receiver)
                           .with(NotificationConstants.TITLE, title)
                           .with(NotificationConstants.CONTENT, content)
                           .with(NotificationConstants.DELEGATION_ACTOR, StringUtils.defaultString(actor))
                           .with(NotificationConstants.DELEGATION_RESPONSE, response)
                           .with(NotificationConstants.DELEGATION_ID, delegationId)
                           .with(NotificationConstants.LINK, EmailConnectorUtils.getEmailsLink(receiver))
                           .key(getKey())
                           .end();
  }
}
