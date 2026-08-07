/**
 * Copyright (C) 2025 eXo Platform SAS
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
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

@ExtendWith(MockitoExtension.class)
public class EmailContactAclPluginTest {

  private static final String   TEST_USER  = "testuser";

  private static final String   OTHER_USER = "otheruser";

  private static final long     CONTACT_ID = 7L;

  @Mock
  private PortalContainer       container;

  @Mock
  private EmailContactStorage   emailContactStorage;

  @Mock
  private UserACL               userAcl;

  @InjectMocks
  private EmailContactAclPlugin emailContactAclPlugin;

  /**
   * Registration must go to the kernel {@link UserACL} singleton resolved from
   * the container, so favorites' generic permission check can find the plugin.
   */
  @Test
  void init() {
    when(container.getComponentInstanceOfType(UserACL.class)).thenReturn(userAcl);
    emailContactAclPlugin.init();
    verify(userAcl).addAclPlugin(emailContactAclPlugin);
  }

  /**
   * The object type must stay "contact" — it is the key the favorites REST uses
   * to route permission checks to this plugin, and the type the front-end
   * registers in the Favorites drawer.
   */
  @Test
  void getObjectType() {
    assertEquals("contact", emailContactAclPlugin.getObjectType());
  }

  /**
   * A valid numeric id resolving to a visible contact owned by the asking
   * identity is the one case that answers true.
   */
  @Test
  void hasPermissionForOwnContact() {
    when(emailContactStorage.getContactById(CONTACT_ID)).thenReturn(contact(TEST_USER, false));
    assertTrue(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * A contact stored for another user must answer false — contact stores are
   * strictly owner-private, whatever the requested permission type.
   */
  @Test
  void hasPermissionForSomeoneElsesContact() {
    when(emailContactStorage.getContactById(CONTACT_ID)).thenReturn(contact(OTHER_USER, false));
    assertFalse(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * A suppressed row is one the user deleted: every service read already
   * answers 404 for it, so favoriting it would create a drawer entry that opens
   * onto nothing. The ACL must say no.
   */
  @Test
  void hasPermissionForSuppressedContact() {
    when(emailContactStorage.getContactById(CONTACT_ID)).thenReturn(contact(TEST_USER, true));
    assertFalse(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * The favorites REST forwards arbitrary identifier strings for object type
   * "contact"; a non-numeric or out-of-range one must quietly answer false,
   * without even hitting the storage.
   */
  @Test
  void hasPermissionForNonNumericId() {
    assertFalse(assertDoesNotThrow(() -> emailContactAclPlugin.hasPermission("not-a-number", "read", new Identity(TEST_USER))));
    // out-of-range numerics are equally unresolvable and take the same path
    assertFalse(assertDoesNotThrow(() -> emailContactAclPlugin.hasPermission("99999999999999999999",
                                                                             "read",
                                                                             new Identity(TEST_USER))));
    verifyNoInteractions(emailContactStorage);
  }

  /**
   * A null or blank id must answer false without reaching the storage
   * ({@code Long.parseLong(null)} throws NFE, so the blank guard matters).
   */
  @Test
  void hasPermissionForNullOrBlankId() {
    assertFalse(assertDoesNotThrow(() -> emailContactAclPlugin.hasPermission(null, "read", new Identity(TEST_USER))));
    assertFalse(assertDoesNotThrow(() -> emailContactAclPlugin.hasPermission("  ", "read", new Identity(TEST_USER))));
    verifyNoInteractions(emailContactStorage);
  }

  /**
   * A well-formed numeric id that matches no row resolves to null in the
   * storage and must answer false.
   */
  @Test
  void hasPermissionForUnknownNumericId() {
    when(emailContactStorage.getContactById(CONTACT_ID)).thenReturn(null);
    assertFalse(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", new Identity(TEST_USER)));
  }

  /**
   * A missing identity (or one carrying no user id) can never own a contact —
   * answer false before touching the storage.
   */
  @Test
  void hasPermissionForMissingIdentity() {
    assertFalse(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", null));
    assertFalse(emailContactAclPlugin.hasPermission(String.valueOf(CONTACT_ID), "read", new Identity(null)));
    verifyNoInteractions(emailContactStorage);
  }

  /**
   * A stored contact row in the shape the checks look at.
   *
   * @param userId the store owner
   * @param suppressed whether the row is a tombstone
   * @return the row
   */
  private EmailContact contact(String userId, boolean suppressed) {
    EmailContact contact = new EmailContact();
    contact.setId(CONTACT_ID);
    contact.setUserId(userId);
    contact.setSuppressed(suppressed);
    return contact;
  }
}
