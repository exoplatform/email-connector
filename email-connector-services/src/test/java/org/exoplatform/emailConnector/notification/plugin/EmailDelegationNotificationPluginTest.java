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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.plugin.NotificationPluginUtils;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The two mailbox-delegation notifications (EXO-90503): each names the other party as a
 * person, writes its sentence in the receiver's language from a code rather than from a
 * sentence built at the raise site, escapes the name into the HTML the mail channel
 * renders, and refuses a context that names nobody.
 */
public class EmailDelegationNotificationPluginTest {

  private final EmailDelegationInvitationPlugin           invitation =
                                                                     new EmailDelegationInvitationPlugin(new InitParams());

  private final EmailDelegationResponseNotificationPlugin response   =
                                                                    new EmailDelegationResponseNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>                      commonsUtils;

  private MockedStatic<NotificationPluginUtils>           pluginUtils;

  private MockedStatic<EmailConnectorUtils>               connectorUtils;

  /**
   * States the bundle, the receiver's language, the directory and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(),
                                 any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                                 case "emailDelegationInvitation.notification.title" -> "A mailbox was shared with you";
                                 case "emailDelegationInvitation.notification.content" ->
                                   "{0} gave you access to their mailbox {1}.";
                                 case "emailDelegationInvitation.notification.preset.READER" -> "as a reader";
                                 case "emailDelegationResponse.notification.title.DECLINED" ->
                                   "Your shared mailbox was declined";
                                 case "emailDelegationResponse.notification.content.DECLINED" ->
                                   "{0} declined the access you gave them. They still have it.";
                                 default -> null;
                                 });
    Profile profile = new Profile(null);
    profile.setProperty(Profile.FULL_NAME, "Anne <Dupont>");
    Identity identity = mock(Identity.class);
    when(identity.getProfile()).thenReturn(profile);
    IdentityManager identityManager = mock(IdentityManager.class);
    when(identityManager.getOrCreateUserIdentity("anne")).thenReturn(identity);
    commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(ResourceBundleService.class)).thenReturn(bundles);
    commonsUtils.when(() -> CommonsUtils.getService(IdentityManager.class)).thenReturn(identityManager);
    pluginUtils = mockStatic(NotificationPluginUtils.class);
    pluginUtils.when(() -> NotificationPluginUtils.getLanguage(anyString())).thenReturn("en");
    connectorUtils = mockStatic(EmailConnectorUtils.class);
    connectorUtils.when(() -> EmailConnectorUtils.getEmailsLink(anyString())).thenReturn("/portal/dw?openEmailBox=true");
  }

  /**
   * Takes the statics away again.
   */
  @AfterEach
  void forgetTheStatics() {
    commonsUtils.close();
    pluginUtils.close();
    connectorUtils.close();
  }

  /**
   * The invitation names the owner as a person, says which preset, and carries the row
   * id so a click can open the share it is about. The name is escaped: the sentence is
   * HTML in the mail channel's template and a display name is text somebody typed.
   */
  @Test
  void theInvitationNamesTheOwnerAndThePreset() {
    NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                     .append(BaseEmailDelegationNotificationPlugin.RECEIVER, "ben")
                                                     .append(BaseEmailDelegationNotificationPlugin.ACTOR, "anne")
                                                     .append(BaseEmailDelegationNotificationPlugin.DELEGATION_ID, "7")
                                                     .append(EmailDelegationInvitationPlugin.PRESET, "READER");
    assertTrue(invitation.isValid(ctx));
    NotificationInfo info = invitation.buildNotification(ctx);
    assertEquals("ben", info.getTo());
    assertEquals("A mailbox was shared with you", info.getValueOwnerParameter(NotificationConstants.TITLE));
    assertEquals("Anne &lt;Dupont&gt; gave you access to their mailbox as a reader.",
                 info.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("anne", info.getValueOwnerParameter(NotificationConstants.DELEGATION_ACTOR));
    assertEquals("READER", info.getValueOwnerParameter(NotificationConstants.DELEGATION_PRESET));
    assertEquals("7", info.getValueOwnerParameter(NotificationConstants.DELEGATION_ID));
    assertEquals("/portal/dw?openEmailBox=true", info.getValueOwnerParameter(NotificationConstants.LINK));
  }

  /**
   * The answer picks its title and its sentence from the transition code, and the
   * declined one says the access stays -- the wording is a correctness requirement,
   * not a style one: declining does not revoke (plan section 5.3).
   */
  @Test
  void theAnswerSaysWhichTransitionItWas() {
    NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                     .append(BaseEmailDelegationNotificationPlugin.RECEIVER, "anne")
                                                     .append(BaseEmailDelegationNotificationPlugin.ACTOR, "anne")
                                                     .append(BaseEmailDelegationNotificationPlugin.DELEGATION_ID, "7")
                                                     .append(EmailDelegationResponseNotificationPlugin.RESPONSE, "DECLINED");
    assertTrue(response.isValid(ctx));
    NotificationInfo info = response.buildNotification(ctx);
    assertEquals("Your shared mailbox was declined", info.getValueOwnerParameter(NotificationConstants.TITLE));
    assertEquals("Anne &lt;Dupont&gt; declined the access you gave them. They still have it.",
                 info.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("DECLINED", info.getValueOwnerParameter(NotificationConstants.DELEGATION_RESPONSE));
  }

  /**
   * A context naming nobody is no notification: a sentence with a blank where the
   * person should be is worse than silence.
   */
  @Test
  void aContextNamingNobodyIsNotValid() {
    assertFalse(invitation.isValid(NotificationContextImpl.cloneInstance()
                                                          .append(BaseEmailDelegationNotificationPlugin.RECEIVER, "ben")));
    assertFalse(invitation.isValid(NotificationContextImpl.cloneInstance()
                                                          .append(BaseEmailDelegationNotificationPlugin.ACTOR, "anne")));
    assertFalse(response.isValid(NotificationContextImpl.cloneInstance()
                                                        .append(BaseEmailDelegationNotificationPlugin.RECEIVER, "anne")
                                                        .append(BaseEmailDelegationNotificationPlugin.ACTOR, "ben")));
  }
}
