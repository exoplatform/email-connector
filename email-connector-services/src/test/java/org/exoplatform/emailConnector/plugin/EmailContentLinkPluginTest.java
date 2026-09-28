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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
   * chip and the links already written ask for, and is listed in the editors'
   * "/" menu under the {@code /mail} command, searched in place; its chips
   * open the mail in a drawer, over the page they are on.
   */
  @Test
  void extensionType() {
    ContentLinkExtension extension = plugin.getExtension();
    assertEquals("email", extension.getObjectType());
    assertEquals("email", plugin.getObjectType());
    assertEquals("contentLink.email", extension.getTitleKey());
    assertEquals("fa fa-envelope", extension.getIcon());
    assertEquals("mail", extension.getCommand());
    assertFalse(extension.isHidden(), "Mail is offered in the editors' insert menu");
    assertTrue(extension.isDrawer(), "A mail chip opens the mail over the page, not on another page");
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
   * An anonymous caller, a blank keyword or a non-positive limit resolve to
   * nothing, without reading any mail.
   */
  @Test
  void searchOfAnonymousOrBlankIsEmpty() {
    assertTrue(plugin.search(String.valueOf(EMAIL_ID), null, Locale.ENGLISH, 0, 10).isEmpty());
    assertTrue(plugin.search("report", null, Locale.ENGLISH, 0, 10).isEmpty());
    assertTrue(plugin.search("  ", new Identity(OWNER), Locale.ENGLISH, 0, 10).isEmpty());
    assertTrue(plugin.search("report", new Identity(OWNER), Locale.ENGLISH, 0, 0).isEmpty());
    verifyNoInteractions(emailBoxService);
  }

  /**
   * A text keyword searches the user's own mails, trimmed, and titles each by
   * its subject, "(no subject)" when it has none.
   */
  @Test
  void searchOfTextReturnsOwnMatches() {
    when(emailBoxService.searchOwnEmailsForLink(OWNER, "invoice", 0, 10)).thenReturn(List.of(mail(5L, OWNER, "Invoice 12"),
                                                                                          mail(4L, OWNER, " ")));
    List<ContentLinkSearchResult> results = plugin.search(" invoice ", new Identity(OWNER), Locale.ENGLISH, 0, 10);
    assertEquals(List.of("5", "4"), results.stream().map(ContentLinkSearchResult::getObjectId).toList());
    assertEquals(List.of("Invoice 12", "(no subject)"), results.stream().map(ContentLinkSearchResult::getTitle).toList());
    assertEquals("email", results.get(0).getObjectType());
    assertEquals("fa fa-envelope", results.get(0).getIcon());
  }

  /**
   * Even if the text search ever returned another user's mail, it would not be
   * handed out.
   */
  @Test
  void searchOfTextDropsAMailOwnedBySomebodyElse() {
    when(emailBoxService.searchOwnEmailsForLink(OTHER_USER, "report", 0, 10)).thenReturn(List.of(mail(EMAIL_ID, OWNER, "Secret")));
    assertTrue(plugin.search("report", new Identity(OTHER_USER), Locale.ENGLISH, 0, 10).isEmpty());
  }

  /**
   * A number that is none of the user's mail ids, or beyond the long range, is
   * searched as text, since a subject can hold a number.
   */
  @Test
  void searchOfUnresolvedNumberFallsBackToText() {
    when(emailBoxService.searchOwnEmailsForLink(OWNER, "2026", 0, 10)).thenReturn(List.of(mail(9L, OWNER, "Budget 2026")));
    assertEquals("Budget 2026", plugin.search("2026", new Identity(OWNER), Locale.ENGLISH, 0, 10).get(0).getTitle());
    assertTrue(plugin.search("99999999999999999999", new Identity(OWNER), Locale.ENGLISH, 0, 10).isEmpty());
    verify(emailBoxService).searchOwnEmailsForLink(OWNER, "99999999999999999999", 0, 10);
  }

  /**
   * A resolved mail id answers that mail alone, without a text search.
   */
  @Test
  void searchOfOwnIdDoesNotSearchText() {
    assertEquals(1, plugin.search(String.valueOf(EMAIL_ID), new Identity(OWNER), Locale.ENGLISH, 0, 10).size());
    verify(emailBoxService, never()).searchOwnEmailsForLink(anyString(), anyString(), anyInt(), anyInt());
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
   * Even should the owned lookup ever hand back a mail of another owner, its
   * subject is not given: the plugin checks the owner again.
   *
   * @throws Exception never
   */
  @Test
  void titleNeverGivesAMailOwnedBySomebodyElse() throws Exception {
    when(emailBoxService.getOwnedEmailById(7L, OTHER_USER)).thenReturn(mail(7L, OWNER, "Salary review"));
    ConversationState.setCurrent(new ConversationState(new Identity(OTHER_USER)));
    assertNull(plugin.getContentTitle("7", Locale.ENGLISH));
  }

  /**
   * An id matching no mail answers exactly as somebody else's mail does, so a
   * chip never tells whether a mail id exists.
   *
   * @throws Exception never
   */
  @Test
  void titleOfUnknownMailIsTheSameAsSomebodyElses() throws Exception {
    when(emailBoxService.getOwnedEmailById(999L, OTHER_USER)).thenReturn(null);
    ConversationState.setCurrent(new ConversationState(new Identity(OTHER_USER)));
    assertEquals(plugin.getContentTitle(String.valueOf(EMAIL_ID), Locale.ENGLISH),
                 plugin.getContentTitle("999", Locale.ENGLISH));
    assertNull(plugin.getContentTitle("999", Locale.ENGLISH));
  }

  /**
   * An id that is not a number designates no mail, and reads none.
   */
  @Test
  void titleOfNonNumericIdIsNone() {
    ConversationState.setCurrent(new ConversationState(new Identity(OWNER)));
    assertNull(plugin.getContentTitle("42abc", Locale.ENGLISH));
    assertNull(plugin.getContentTitle("../42", Locale.ENGLISH));
    verifyNoInteractions(emailBoxService);
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
   * A mail without subject still resolves, as "(no subject)".
   *
   * @throws Exception never
   */
  @Test
  void titleFallsBackToNoSubject() throws Exception {
    Email email = mail(8L, OWNER, null);
    EmailSender sender = new EmailSender();
    sender.setAddress("alice@example.org");
    email.setSender(sender);
    when(emailBoxService.getOwnedEmailById(anyLong(), anyString())).thenReturn(email);
    ConversationState.setCurrent(new ConversationState(new Identity(OWNER)));
    assertEquals("(no subject)", plugin.getContentTitle("8", Locale.ENGLISH));
  }

  /**
   * A light mail, as the link searches return it.
   *
   * @param id the mail id
   * @param owner the mailbox owner
   * @param subject the subject
   * @return the mail
   */
  private static Email mail(long id, String owner, String subject) {
    Email email = new Email();
    email.setId(id);
    email.setUserId(owner);
    email.setSubject(subject);
    return email;
  }

}
