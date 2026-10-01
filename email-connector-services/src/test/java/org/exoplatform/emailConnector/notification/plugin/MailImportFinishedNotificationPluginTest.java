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
 */package org.exoplatform.emailConnector.notification.plugin;

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
 * The end-of-import notification (EXO-90846): the folder and the three counts in the
 * receiver's language, a folder of the user's own named as they wrote it and escaped,
 * "stopped" for a run that broke or hit a limit, and only digits for counts.
 */
class MailImportFinishedNotificationPluginTest {

  private final MailImportFinishedNotificationPlugin plugin = new MailImportFinishedNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>                 commonsUtils;

  private MockedStatic<NotificationPluginUtils>      pluginUtils;

  private MockedStatic<EmailConnectorUtils>          connectorUtils;

  /**
   * States the bundle, the receiver's language and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(), any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
    case "mailImportFinished.notification.title" -> "Your mail import ended";
    case "mailImportFinished.notification.content" -> "Import into {0} done: {1} added, {2} already there, {3} refused.";
    case "mailImportFinished.notification.contentCutShort" -> "Import into {0} stopped at a limit: {1} added, {2} already there, {3} refused.";
    case "mailImportFinished.notification.contentFailed" -> "Import into {0} stopped: {1} added, {2} already there, {3} refused.";
    case "mailImportFinished.notification.folder.INBOX" -> "Inbox";
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

  /** A built-in folder is named in the receiver's language, with the three counts. */
  @Test
  void aDoneImportGivesTheCounts() {
    NotificationInfo info = plugin.buildNotification(context("INBOX", "", "SUCCESS", "", "12"));
    assertEquals("alice", info.getTo());
    assertEquals("Import into Inbox done: 12 added, 2 already there, 1 refused.", info.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("INBOX", info.getValueOwnerParameter(MailImportFinishedNotificationPlugin.FOLDER_PARAM));
    assertEquals("12", info.getValueOwnerParameter(MailImportFinishedNotificationPlugin.ADDED_PARAM));
    assertEquals("/portal/dw/emails", info.getValueOwnerParameter(NotificationConstants.LINK));
  }

  /** A folder of the user's own is named as they wrote it, escaped; a count that is not one is zero. */
  @Test
  void aCustomFolderIsEscapedAndCountsAreDigits() {
    NotificationInfo info = plugin.buildNotification(context("CUSTOM:3", "<b>Q3</b>", "SUCCESS", "", "<img>"));
    assertEquals("Import into &lt;b&gt;Q3&lt;/b&gt; done: 0 added, 2 already there, 1 refused.",
                 info.getValueOwnerParameter(NotificationConstants.CONTENT));
  }

  /** A run cut short at a limit, and a run that broke, say they stopped. */
  @Test
  void aStoppedImportSaysSo() {
    assertEquals("Import into Inbox stopped at a limit: 1 added, 2 already there, 1 refused.",
                 plugin.buildNotification(context("INBOX", "", "SUCCESS", "emailConnector.import.tooManyMails", "1"))
                       .getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("Import into Inbox stopped: 1 added, 2 already there, 1 refused.",
                 plugin.buildNotification(context("INBOX", "", "FAILURE", "emailConnector.import.failed", "1"))
                       .getValueOwnerParameter(NotificationConstants.CONTENT));
  }

  /** No receiver, or no status, is no notification. */
  @Test
  void aContextWithoutReceiverOrStatusIsNotValid() {
    assertTrue(plugin.isValid(context("INBOX", "", "SUCCESS", "", "1")));
    assertFalse(plugin.isValid(NotificationContextImpl.cloneInstance().append(MailImportFinishedNotificationPlugin.STATUS, "SUCCESS")));
    assertFalse(plugin.isValid(NotificationContextImpl.cloneInstance().append(MailImportFinishedNotificationPlugin.RECEIVER, "alice")));
  }

  /**
   * A context for alice.
   *
   * @param folder the folder key
   * @param folderName the folder's own name
   * @param status the status
   * @param messageCode the message code
   * @param added the added count
   * @return the context
   */
  private static NotificationContext context(String folder, String folderName, String status, String messageCode, String added) {
    return NotificationContextImpl.cloneInstance()
                                  .append(MailImportFinishedNotificationPlugin.RECEIVER, "alice")
                                  .append(MailImportFinishedNotificationPlugin.FOLDER, folder)
                                  .append(MailImportFinishedNotificationPlugin.FOLDER_NAME, folderName)
                                  .append(MailImportFinishedNotificationPlugin.STATUS, status)
                                  .append(MailImportFinishedNotificationPlugin.MESSAGE_CODE, messageCode)
                                  .append(MailImportFinishedNotificationPlugin.ADDED, added)
                                  .append(MailImportFinishedNotificationPlugin.SKIPPED, "2")
                                  .append(MailImportFinishedNotificationPlugin.REFUSED, "1");
  }
}
