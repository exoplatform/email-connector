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
 * The notification of a mail filter opens the mail it names: by its inbox UID, by the
 * built-in folder it was filed into, or the mailbox alone.
 */
public class EmailFilterNotificationPluginTest {

  private static final String                   MAILBOX = "/portal/dw?openEmailBox=true";

  private final EmailFilterNotificationPlugin   plugin  = new EmailFilterNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>            commonsUtils;

  private MockedStatic<NotificationPluginUtils> pluginUtils;

  private MockedStatic<EmailConnectorUtils>     connectorUtils;

  /**
   * States the bundle, the receiver's language and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(),
                                 any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                                 case "emailFilter.notification.title" -> "Mail filter";
                                 case "emailFilter.notification.content.one" -> "{0} matched a new mail";
                                 case "emailFilter.notification.content.many" -> "{0} matched {1} new mails";
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
   * A mail still in the inbox: the notification carries its UID, and the link opens it.
   */
  @Test
  void aMailStillInTheInboxIsOpenedByItsUid() {
    NotificationInfo info = plugin.buildNotification(context("42", ""));

    assertEquals("42", info.getValueOwnerParameter(NotificationConstants.MAIL_REMOTE_ID));
    assertEquals("", info.getValueOwnerParameter(NotificationConstants.MAIL_FOLDER));
    assertEquals(MAILBOX + "&mailRemoteId=42", info.getValueOwnerParameter(NotificationConstants.LINK));
    assertEquals("Acme matched 3 new mails", info.getValueOwnerParameter(NotificationConstants.CONTENT));
  }

  /**
   * A mail filed into Junk: the link opens Junk.
   */
  @Test
  void aMailFiledIntoABuiltInFolderOpensThatFolder() {
    NotificationInfo info = plugin.buildNotification(context("", "JUNK"));

    assertEquals("", info.getValueOwnerParameter(NotificationConstants.MAIL_REMOTE_ID));
    assertEquals("JUNK", info.getValueOwnerParameter(NotificationConstants.MAIL_FOLDER));
    assertEquals(MAILBOX + "&folder=JUNK", info.getValueOwnerParameter(NotificationConstants.LINK));
  }

  /**
   * A mail moved into a folder of the user's, which the deep link does not open, or a
   * value that is no UID: the mailbox alone.
   */
  @Test
  void anythingElseOpensTheMailbox() {
    assertEquals(MAILBOX, plugin.buildNotification(context("", "CUSTOM:3")).getValueOwnerParameter(NotificationConstants.LINK));
    assertEquals(MAILBOX, plugin.buildNotification(context("4&x=1", "")).getValueOwnerParameter(NotificationConstants.LINK));
    assertEquals(MAILBOX, plugin.buildNotification(context("", "")).getValueOwnerParameter(NotificationConstants.LINK));
  }

  /**
   * A notification of the filter "Acme" for three mails.
   *
   * @param mailRemoteId the UID to name
   * @param mailFolder the folder to name
   * @return the context
   */
  private NotificationContext context(String mailRemoteId, String mailFolder) {
    return NotificationContextImpl.cloneInstance()
                                  .append(EmailFilterNotificationPlugin.RECEIVER, "ben")
                                  .append(EmailFilterNotificationPlugin.FILTER_NAME, "Acme")
                                  .append(EmailFilterNotificationPlugin.COUNT, "3")
                                  .append(EmailFilterNotificationPlugin.LINE, "")
                                  .append(EmailFilterNotificationPlugin.MAIL_REMOTE_ID, mailRemoteId)
                                  .append(EmailFilterNotificationPlugin.MAIL_FOLDER, mailFolder);
  }
}
