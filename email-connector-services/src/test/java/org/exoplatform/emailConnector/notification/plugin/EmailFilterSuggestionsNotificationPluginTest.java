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
 * The digest of the waiting suggestions (EXO-90668) says how many wait, in the
 * receiver's language, and opens the mailbox; with nothing waiting there is none.
 */
class EmailFilterSuggestionsNotificationPluginTest {

  private static final String                     MAILBOX = "/portal/dw?openEmailBox=true";

  private final EmailFilterSuggestionsNotificationPlugin plugin = new EmailFilterSuggestionsNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>              commonsUtils;

  private MockedStatic<NotificationPluginUtils>   pluginUtils;

  private MockedStatic<EmailConnectorUtils>       connectorUtils;

  /**
   * States the bundle, the receiver's language and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(),
                                 any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                                 case "emailFilterSuggestions.notification.title" -> "Mail assistant";
                                 case "emailFilterSuggestions.notification.content.one" -> "1 suggestion waiting";
                                 case "emailFilterSuggestions.notification.content.many" -> "{0} suggestions waiting";
                                 default -> null;
                                 });
    commonsUtils = mockStatic(CommonsUtils.class);
    commonsUtils.when(() -> CommonsUtils.getService(ResourceBundleService.class)).thenReturn(bundles);
    pluginUtils = mockStatic(NotificationPluginUtils.class);
    pluginUtils.when(() -> NotificationPluginUtils.getLanguage(anyString())).thenReturn("en");
    connectorUtils = mockStatic(EmailConnectorUtils.class);
    connectorUtils.when(() -> EmailConnectorUtils.getEmailsLink(anyString())).thenReturn(MAILBOX);
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
   * The digest says the count, one or many, and opens the mailbox.
   */
  @Test
  void theDigestSaysHowManyWait() {
    NotificationInfo many = plugin.buildNotification(context("4"));

    assertEquals("4 suggestions waiting", many.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("4", many.getValueOwnerParameter(NotificationConstants.SUGGESTION_COUNT));
    assertEquals("Mail assistant", many.getValueOwnerParameter(NotificationConstants.TITLE));
    assertEquals(MAILBOX, many.getValueOwnerParameter(NotificationConstants.LINK));
    assertEquals("1 suggestion waiting", plugin.buildNotification(context("1")).getValueOwnerParameter(NotificationConstants.CONTENT));
  }

  /**
   * Nothing waiting, or a count that is none: no digest.
   */
  @Test
  void noDigestWithoutASuggestion() {
    assertTrue(plugin.isValid(context("2")));
    assertFalse(plugin.isValid(context("0")));
    assertFalse(plugin.isValid(context("x")));
  }

  /**
   * A digest for "ben".
   *
   * @param count the count
   * @return the context
   */
  private NotificationContext context(String count) {
    return NotificationContextImpl.cloneInstance()
                                  .append(EmailFilterSuggestionsNotificationPlugin.RECEIVER, "ben")
                                  .append(EmailFilterSuggestionsNotificationPlugin.COUNT, count);
  }
}
