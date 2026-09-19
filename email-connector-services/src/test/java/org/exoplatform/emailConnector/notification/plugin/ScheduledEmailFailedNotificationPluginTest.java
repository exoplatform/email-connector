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

/**
 * The failed-scheduled-mail notification: it carries the subject and the reason code,
 * says the reason in the receiver's language, escapes the subject in the sentence, and
 * refuses a context without a receiver or a reason.
 */
public class ScheduledEmailFailedNotificationPluginTest {

  private final ScheduledEmailFailedNotificationPlugin plugin = new ScheduledEmailFailedNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>                   commonsUtils;

  private MockedStatic<NotificationPluginUtils>        pluginUtils;

  private MockedStatic<EmailConnectorUtils>            connectorUtils;

  /**
   * States the bundle, the receiver's language and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(), any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
    case "scheduledEmailFailed.notification.title" -> "A scheduled email needs your attention";
    case "scheduledEmailFailed.notification.content" -> "Your scheduled email \"{0}\": {1}";
    case "scheduledEmailFailed.notification.reason.RECIPIENT_REFUSED" -> "it was not sent, the mail server refused a recipient.";
    default -> null;
    });
    commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(ResourceBundleService.class)).thenReturn(bundles);
    pluginUtils = mockStatic(NotificationPluginUtils.class);
    pluginUtils.when(() -> NotificationPluginUtils.getLanguage("alice")).thenReturn("en");
    connectorUtils = mockStatic(EmailConnectorUtils.class);
    connectorUtils.when(() -> EmailConnectorUtils.getEmailsLink("alice")).thenReturn("/portal/dw/emails");
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
   * The notification carries the subject and the code, and says them in the receiver's
   * language, the subject escaped.
   */
  @Test
  void theNotificationCarriesTheSubjectAndTheReasonCode() {
    NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                     .append(ScheduledEmailFailedNotificationPlugin.RECEIVER, "alice")
                                                     .append(ScheduledEmailFailedNotificationPlugin.SUBJECT, "<b>Q3</b> plan")
                                                     .append(ScheduledEmailFailedNotificationPlugin.REASON, "RECIPIENT_REFUSED");
    assertTrue(plugin.isValid(ctx));
    NotificationInfo info = plugin.buildNotification(ctx);
    assertEquals("alice", info.getTo());
    assertEquals("RECIPIENT_REFUSED", info.getValueOwnerParameter(NotificationConstants.REASON));
    assertEquals("<b>Q3</b> plan", info.getValueOwnerParameter(NotificationConstants.SUBJECT));
    assertEquals("Your scheduled email \"&lt;b&gt;Q3&lt;/b&gt; plan\": it was not sent, the mail server refused a recipient.",
                 info.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("/portal/dw/emails", info.getValueOwnerParameter(NotificationConstants.LINK));
  }

  /**
   * No receiver, or no reason, is no notification.
   */
  @Test
  void aContextWithoutReceiverOrReasonIsNotValid() {
    assertFalse(plugin.isValid(NotificationContextImpl.cloneInstance()
                                                      .append(ScheduledEmailFailedNotificationPlugin.REASON, "NETWORK")));
    assertFalse(plugin.isValid(NotificationContextImpl.cloneInstance()
                                                      .append(ScheduledEmailFailedNotificationPlugin.RECEIVER, "alice")));
  }
}
