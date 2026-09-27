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
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
   * The link opens the mailbox on the site's home page.
   */
  @Test
  void directAccessUrlOpensTheMailbox() {
    when(portalConfigService.getMetaPortal()).thenReturn("dw");
    assertEquals("/portal/dw?openEmailBox=true", plugin.getDirectAccessUrl(new PermanentLinkObject("email", "3")));
  }

}
