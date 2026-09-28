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
package org.exoplatform.emailConnector.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.permlink.model.PermanentLinkObject;
import io.meeds.portal.permlink.service.PermanentLinkService;

@ExtendWith(MockitoExtension.class)
class EmailPermanentLinkPluginTest {

  @Mock
  private UserPortalConfigService  portalConfigService;

  @Mock
  private PermanentLinkService     permanentLinkService;

  @Mock
  private UserACL                  userAcl;

  @Mock
  private EmailBoxService          emailBoxService;

  @InjectMocks
  private EmailPermanentLinkPlugin plugin;

  /**
   * The plugin registers itself for the {@code email} type.
   */
  @Test
  void initRegistersThePlugin() {
    plugin.init();
    verify(permanentLinkService).addPlugin(plugin);
    assertEquals("email", plugin.getObjectType());
  }

  /**
   * Access is the email ACL's decision, taken for the mail's id and the caller;
   * a missing object is refused without asking.
   */
  @Test
  void canAccessIsTheEmailAclDecision() {
    Identity owner = new Identity("owner");
    Identity other = new Identity("other");
    when(userAcl.hasAccessPermission("email", "3", owner)).thenReturn(true);
    when(userAcl.hasAccessPermission("email", "3", other)).thenReturn(false);
    assertTrue(plugin.canAccess(new PermanentLinkObject("email", "3"), owner));
    assertFalse(plugin.canAccess(new PermanentLinkObject("email", "3"), other));
    assertFalse(plugin.canAccess(null, owner));
  }

  /**
   * The link opens the mail it names in the mailbox's reader: its UID and the key of
   * its folder, read from the cache when the link is followed.
   */
  @Test
  void directAccessUrlOpensThatMail() {
    when(portalConfigService.getMetaPortal()).thenReturn("dw");
    when(emailBoxService.getEmailById(3L, null)).thenReturn(email(42L, "INBOX"));
    assertEquals("/portal/dw?openEmailBox=true&mailRemoteId=42&folder=INBOX",
                 plugin.getDirectAccessUrl(new PermanentLinkObject("email", "3")));
  }

  /**
   * A custom folder's key is encoded in the URL, since it carries a colon.
   */
  @Test
  void directAccessUrlEncodesACustomFolderKey() {
    when(portalConfigService.getMetaPortal()).thenReturn("dw");
    when(emailBoxService.getEmailById(3L, null)).thenReturn(email(7L, "CUSTOM:12"));
    assertEquals("/portal/dw?openEmailBox=true&mailRemoteId=7&folder=CUSTOM%3A12",
                 plugin.getDirectAccessUrl(new PermanentLinkObject("email", "3")));
  }

  /**
   * A mail the reader cannot open from outside -- gone from the cache, a draft, a
   * folder key this add-on never wrote, no UID -- opens the mailbox alone.
   */
  @Test
  void directAccessUrlFallsBackToTheMailbox() {
    when(portalConfigService.getMetaPortal()).thenReturn("dw");
    String mailbox = "/portal/dw?openEmailBox=true";
    when(emailBoxService.getEmailById(3L, null)).thenReturn(null);
    assertEquals(mailbox, plugin.getDirectAccessUrl(new PermanentLinkObject("email", "3")));
    when(emailBoxService.getEmailById(4L, null)).thenReturn(email(42L, "DRAFTS"));
    assertEquals(mailbox, plugin.getDirectAccessUrl(new PermanentLinkObject("email", "4")));
    when(emailBoxService.getEmailById(5L, null)).thenReturn(email(42L, "CUSTOM:x"));
    assertEquals(mailbox, plugin.getDirectAccessUrl(new PermanentLinkObject("email", "5")));
    when(emailBoxService.getEmailById(6L, null)).thenReturn(email(null, "INBOX"));
    assertEquals(mailbox, plugin.getDirectAccessUrl(new PermanentLinkObject("email", "6")));
  }

  /**
   * An object id that is not a number looks nothing up.
   */
  @Test
  void directAccessUrlOfANonNumericIdIsTheMailbox() {
    when(portalConfigService.getMetaPortal()).thenReturn("dw");
    assertEquals("/portal/dw?openEmailBox=true", plugin.getDirectAccessUrl(new PermanentLinkObject("email", "3&x=1")));
    verifyNoInteractions(emailBoxService);
  }

  /**
   * A cached mail.
   *
   * @param mailRemoteId its IMAP UID
   * @param folder its folder key
   * @return the mail
   */
  private static Email email(Long mailRemoteId, String folder) {
    Email email = new Email();
    email.setMailRemoteId(mailRemoteId);
    email.setFolder(folder);
    return email;
  }

}
