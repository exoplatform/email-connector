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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import org.exoplatform.container.PortalContainer;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

@ExtendWith(MockitoExtension.class)
public class EmailAclPluginTest {

  private static final String TEST_USER  = "testuser";

  private static final String OTHER_USER = "otheruser";

  private static final long   EMAIL_ID   = 5L;

  @Mock
  private PortalContainer     container;

  @Mock
  private EmailBoxService     emailBoxService;

  @Mock
  private UserACL             userAcl;

  @InjectMocks
  private EmailAclPlugin      emailAclPlugin;

  /**
   * Registration must go to the kernel {@link UserACL} singleton resolved from
   * the container, so favorites' generic permission check can find the plugin.
   */
  @Test
  void init() {
    when(container.getComponentInstanceOfType(UserACL.class)).thenReturn(userAcl);
    emailAclPlugin.init();
    verify(userAcl).addAclPlugin(emailAclPlugin);
  }

  /**
   * The object type must stay "email" — it is the key the favorites REST and
   * MCP tools use to route permission checks to this plugin.
   */
  @Test
  void getObjectType() {
    assertEquals("email", emailAclPlugin.getObjectType());
  }

  /**
   * A valid numeric id resolving to an email owned by the asking identity is
   * the one case that answers true.
   */
  @Test
  void hasPermissionForOwnEmail() {
    Email email = new Email();
    email.setUserId(TEST_USER);
    when(emailBoxService.getEmailById(EMAIL_ID, null)).thenReturn(email);
    assertTrue(emailAclPlugin.hasPermission(String.valueOf(EMAIL_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * An email cached for another mailbox owner must answer false — emails are
   * strictly owner-private, whatever the requested permission type.
   */
  @Test
  void hasPermissionForSomeoneElsesEmail() {
    Email email = new Email();
    email.setUserId(OTHER_USER);
    when(emailBoxService.getEmailById(EMAIL_ID, null)).thenReturn(email);
    assertFalse(emailAclPlugin.hasPermission(String.valueOf(EMAIL_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * The MCP favorites tools forward arbitrary identifier strings for object
   * type "email"; a non-numeric one used to blow up in
   * {@code Long.parseLong} — it must now quietly answer false, without even
   * hitting the service.
   */
  @Test
  void hasPermissionForNonNumericId() {
    assertFalse(assertDoesNotThrow(() -> emailAclPlugin.hasPermission("not-a-number", "read", new Identity(TEST_USER))));
    // out-of-range numerics are equally unresolvable and take the same path
    assertFalse(assertDoesNotThrow(() -> emailAclPlugin.hasPermission("99999999999999999999", "read", new Identity(TEST_USER))));
    verifyNoInteractions(emailBoxService);
  }

  /**
   * A null or blank id used to throw {@code NumberFormatException}
   * ({@code Long.parseLong(null)} throws NFE, not NPE) — both must answer
   * false without reaching the service.
   */
  @Test
  void hasPermissionForNullOrBlankId() {
    assertFalse(assertDoesNotThrow(() -> emailAclPlugin.hasPermission(null, "read", new Identity(TEST_USER))));
    assertFalse(assertDoesNotThrow(() -> emailAclPlugin.hasPermission("  ", "read", new Identity(TEST_USER))));
    verifyNoInteractions(emailBoxService);
  }

  /**
   * A well-formed numeric id that matches no cached email resolves to null in
   * the service and must answer false.
   */
  @Test
  void hasPermissionForUnknownNumericId() {
    when(emailBoxService.getEmailById(EMAIL_ID, null)).thenReturn(null);
    assertFalse(emailAclPlugin.hasPermission(String.valueOf(EMAIL_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * A missing identity (or one carrying no user id) can never own an email —
   * answer false before touching the service.
   */
  @Test
  void hasPermissionForMissingIdentity() {
    assertFalse(emailAclPlugin.hasPermission(String.valueOf(EMAIL_ID), "read", null));
    assertFalse(emailAclPlugin.hasPermission(String.valueOf(EMAIL_ID), "read", new Identity(null)));
    verifyNoInteractions(emailBoxService);
  }
}
