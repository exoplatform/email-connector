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
 * The shared mailbox's new-mail notification (EXO-90553): it names the owner first, as
 * a person, counts in the receiver's language, links to the shared mailbox rather than
 * the receiver's own, and refuses a context that names no share or no one.
 */
public class DelegatedNewEmailsNotificationPluginTest {

  private final DelegatedNewEmailsNotificationPlugin plugin = new DelegatedNewEmailsNotificationPlugin(new InitParams());

  private MockedStatic<CommonsUtils>                 commonsUtils;

  private MockedStatic<NotificationPluginUtils>      pluginUtils;

  private MockedStatic<EmailConnectorUtils>          connectorUtils;

  /**
   * States the bundle, the receiver's language, the directory and the mailbox link.
   */
  @BeforeEach
  void stateTheStatics() {
    ResourceBundleService bundles = mock(ResourceBundleService.class);
    when(bundles.getSharedString(anyString(),
                                 any(Locale.class))).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
                                 case "delegatedNewEmails.notification.title" -> "New emails in a shared mailbox";
                                 case "delegatedNewEmails.notification.content" -> "{0}'s mailbox: {1} new messages";
                                 case "delegatedNewEmails.notification.content.one" -> "{0}'s mailbox: 1 new message";
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
   * Several messages: the owner's name first, escaped for the mail channel's HTML, the
   * count, and the link that opens the shared mailbox by its delegation id.
   */
  @Test
  void itNamesTheOwnerFirstAndLinksToTheSharedMailbox() {
    NotificationContext ctx = context("anne", "anne@acme.com", "7", "3");
    assertTrue(plugin.isValid(ctx));
    NotificationInfo info = plugin.buildNotification(ctx);
    assertEquals("ben", info.getTo());
    assertEquals("New emails in a shared mailbox", info.getValueOwnerParameter(NotificationConstants.TITLE));
    assertEquals("Anne &lt;Dupont&gt;'s mailbox: 3 new messages", info.getValueOwnerParameter(NotificationConstants.CONTENT));
    assertEquals("/portal/dw?openEmailBox=true&mailbox=7", info.getValueOwnerParameter(NotificationConstants.LINK));
    assertEquals("7", info.getValueOwnerParameter(NotificationConstants.DELEGATION_ID));
    assertEquals(NotificationConstants.DELEGATED_NEW_EMAILS_NOTIFICATION_PLUGIN, info.getKey().getId());
  }

  /**
   * One message reads as one, and an owner eXo does not know is named by the address.
   */
  @Test
  void oneMessageFromAnOwnerKnownByAddressOnly() {
    NotificationContext ctx = context("", "anne@acme.com", "7", "1");
    assertTrue(plugin.isValid(ctx));
    NotificationInfo info = plugin.buildNotification(ctx);
    assertEquals("anne@acme.com's mailbox: 1 new message", info.getValueOwnerParameter(NotificationConstants.CONTENT));
  }

  /**
   * No share, no one to name, or nothing new: no notification.
   */
  @Test
  void aContextWithoutAShareANameOrACountIsNotValid() {
    assertFalse(plugin.isValid(context("anne", "anne@acme.com", "", "3")));
    assertFalse(plugin.isValid(context("", "", "7", "3")));
    assertFalse(plugin.isValid(context("anne", "anne@acme.com", "7", "0")));
    assertFalse(plugin.isValid(context("anne", "anne@acme.com", "7", "x")));
  }

  /**
   * A context as the sync raises it.
   *
   * @param owner the owner's username, blank for none
   * @param ownerMailbox the owner's address
   * @param delegationId the share
   * @param count how many new messages
   * @return the context
   */
  private NotificationContext context(String owner, String ownerMailbox, String delegationId, String count) {
    return NotificationContextImpl.cloneInstance()
                                  .append(BaseEmailDelegationNotificationPlugin.RECEIVER, "ben")
                                  .append(BaseEmailDelegationNotificationPlugin.ACTOR, owner)
                                  .append(DelegatedNewEmailsNotificationPlugin.OWNER_MAILBOX, ownerMailbox)
                                  .append(BaseEmailDelegationNotificationPlugin.DELEGATION_ID, delegationId)
                                  .append(DelegatedNewEmailsNotificationPlugin.NEW_EMAILS, count);
  }
}
