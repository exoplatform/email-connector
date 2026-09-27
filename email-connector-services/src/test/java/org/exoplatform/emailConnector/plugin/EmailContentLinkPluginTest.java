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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.social.cms.model.ContentLinkExtension;
import io.meeds.social.cms.model.ContentLinkSearchResult;
import io.meeds.social.cms.service.ContentLinkPluginService;

@ExtendWith(MockitoExtension.class)
class EmailContentLinkPluginTest {

  private static final String      OWNER      = "owner";

  private static final String      OTHER_USER = "other";

  private static final long        EMAIL_ID   = 42L;

  @Mock
  private ContentLinkPluginService contentLinkPluginService;

  @Mock
  private EmailBoxService          emailBoxService;

  @InjectMocks
  private EmailContentLinkPlugin   plugin;

  /**
   * The owner's mail, as the owned lookup returns it; another user asking for it
   * is refused, as the real {@link EmailBoxService#getOwnedEmailById} does.
   *
   * @throws Exception never
   */
  @BeforeEach
  void setUp() throws Exception {
    Email email = new Email();
    email.setId(EMAIL_ID);
    email.setUserId(OWNER);
    email.setSubject("Quarterly report");
    lenient().when(emailBoxService.getOwnedEmailById(EMAIL_ID, OWNER)).thenReturn(email);
    lenient().when(emailBoxService.getOwnedEmailById(EMAIL_ID, OTHER_USER)).thenThrow(new IllegalAccessException("not yours"));
  }

  /**
   * Leaves no current user behind for the next test.
   */
  @AfterEach
  void tearDown() {
    ConversationState.setCurrent(null);
  }

  /**
   * The plugin registers itself in Social's content-link registry.
   */
  @Test
  void initRegistersThePlugin() {
    plugin.init();
    verify(contentLinkPluginService).addPlugin(plugin);
  }

  /**
   * The extension serves the {@code email} type, the one the AI chat's source
   * chip asks for, and stays out of the editors' link picker.
   */
  @Test
  void extensionType() {
    ContentLinkExtension extension = plugin.getExtension();
    assertEquals("email", extension.getObjectType());
    assertEquals("email", plugin.getObjectType());
    assertEquals("contentLink.email", extension.getTitleKey());
    assertEquals("fa fa-envelope", extension.getIcon());
    assertTrue(extension.isHidden());
  }

  /**
   * The owner searching their mail id finds it, with its subject as title.
   */
  @Test
  void searchFindsOwnMail() {
    List<ContentLinkSearchResult> results = plugin.search(String.valueOf(EMAIL_ID), new Identity(OWNER), Locale.ENGLISH, 0, 10);
    assertEquals(1, results.size());
    assertEquals("email", results.get(0).getObjectType());
    assertEquals(String.valueOf(EMAIL_ID), results.get(0).getObjectId());
    assertEquals("Quarterly report", results.get(0).getTitle());
  }

  /**
   * Another user searching the owner's mail id gets nothing.
   */
  @Test
  void searchOfAnotherUsersMailIsEmpty() {
    assertTrue(plugin.search(String.valueOf(EMAIL_ID), new Identity(OTHER_USER), Locale.ENGLISH, 0, 10).isEmpty());
  }

  /**
   * Even if the owned lookup ever returned a mail it should have refused, the
   * plugin does not hand it to a user who does not own it.
   *
   * @throws Exception never
   */
  @Test
  void searchNeverReturnsAMailOwnedBySomebodyElse() throws Exception {
    Email foreign = new Email();
    foreign.setId(7L);
    foreign.setUserId(OWNER);
    foreign.setSubject("Secret");
    when(emailBoxService.getOwnedEmailById(7L, OTHER_USER)).thenReturn(foreign);
    assertTrue(plugin.search("7", new Identity(OTHER_USER), Locale.ENGLISH, 0, 10).isEmpty());
  }

  /**
   * An id matching no mail resolves to nothing.
   */
  @Test
  void searchOfUnknownIdIsEmpty() {
    assertTrue(plugin.search("999", new Identity(OWNER), Locale.ENGLISH, 0, 10).isEmpty());
  }

  /**
   * A text keyword, an anonymous caller or an id beyond the long range resolve
   * to nothing, without reading any mail.
   */
  @Test
  void searchOfTextOrAnonymousIsEmpty() {
    assertTrue(plugin.search("report", new Identity(OWNER), Locale.ENGLISH, 0, 10).isEmpty());
    assertTrue(plugin.search(String.valueOf(EMAIL_ID), null, Locale.ENGLISH, 0, 10).isEmpty());
    assertTrue(plugin.search("99999999999999999999", new Identity(OWNER), Locale.ENGLISH, 0, 10).isEmpty());
    verifyNoInteractions(emailBoxService);
  }

  /**
   * The title of the current user's own mail is its subject.
   */
  @Test
  void titleOfOwnMail() {
    ConversationState.setCurrent(new ConversationState(new Identity(OWNER)));
    assertEquals("Quarterly report", plugin.getContentTitle(String.valueOf(EMAIL_ID), Locale.ENGLISH));
  }

  /**
   * The title of somebody else's mail is never given.
   */
  @Test
  void titleOfSomebodyElsesMailIsNone() {
    ConversationState.setCurrent(new ConversationState(new Identity(OTHER_USER)));
    assertNull(plugin.getContentTitle(String.valueOf(EMAIL_ID), Locale.ENGLISH));
  }

  /**
   * With no current user, no title is given and no mail is read.
   */
  @Test
  void titleWithoutCurrentUserIsNone() {
    assertNull(plugin.getContentTitle(String.valueOf(EMAIL_ID), Locale.ENGLISH));
    verifyNoInteractions(emailBoxService);
  }

  /**
   * A mail without subject still resolves, under its sender's name.
   *
   * @throws Exception never
   */
  @Test
  void titleFallsBackToSender() throws Exception {
    Email email = new Email();
    email.setId(8L);
    email.setUserId(OWNER);
    EmailSender sender = new EmailSender();
    sender.setAddress("alice@example.org");
    email.setSender(sender);
    when(emailBoxService.getOwnedEmailById(anyLong(), anyString())).thenReturn(email);
    ConversationState.setCurrent(new ConversationState(new Identity(OWNER)));
    assertEquals("alice@example.org", plugin.getContentTitle("8", Locale.ENGLISH));
  }

}
