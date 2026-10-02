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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailWaitingSuggestionMail;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ThreadSummary;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.emailConnector.storage.EmailReadReceiptAnswerStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobSchedulerService;

import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;

/**
 * EXO-90882 -- the mails of a list that is not a folder's, the mail drawer's search hits
 * and the "Suggestions" view's mails, given what the folder list's rows carry: the
 * excerpt and the attachments of the cached message, never its body, and its
 * conversation's size and unsent draft as the folder list counts them. One batch read of
 * the rows and one count per mailbox, and only for a cached row of the user's own, in the
 * folder the mail was listed from, of a folder the user may list.
 */
@SpringBootTest(classes = { EmailBoxService.class, EmailFolderService.class })
@ExtendWith(MockitoExtension.class)
class EmailBoxListedRowsTest {

  private static final String       USER    = "john";

  private static final String       SHARED  = "CUSTOM:8";

  @MockitoBean
  private UserEmailSettingService   userEmailSettingService;

  @MockitoBean
  private EmailBoxStorage           emailBoxStorage;

  @MockitoBean
  private SettingService            settingService;

  @MockitoBean
  private JobSchedulerService       jobSchedulerService;

  @MockitoBean
  private EmailSyncStateStorage     emailSyncStateStorage;

  @MockitoBean
  private ListenerService           listenerService;

  @MockitoBean
  private EmailConnectorService     emailConnectorService;

  @MockitoBean
  private CategoryLinkService       categoryLinkService;

  @MockitoBean
  private CategoryService           categoryService;

  @MockitoBean
  private ApplicationEventPublisher eventPublisher;

  @MockitoBean
  private EmailFavoriteService      emailFavoriteService;

  @MockitoBean
  private EmailSignatureService     emailSignatureService;

  @MockitoBean
  private EmailFolderStorage        emailFolderStorage;

  @MockitoBean
  private EmailCredentialsResolver  emailCredentialsResolver;

  @MockitoBean
  private EmailScheduledSendStorage emailScheduledSendStorage;

  @MockitoBean
  private SmtpTransmitter           smtpTransmitter;

  @MockitoBean
  private EmailReadReceiptAnswerStorage readReceiptAnswerStorage;

  @MockitoBean
  private EmailDelegationService    emailDelegationService;

  @MockitoBean
  private EmailDmarcVerdictBackfillService emailDmarcVerdictBackfillService;

  @Autowired
  private EmailBoxService           emailBoxService;

  /**
   * Two hits of the user's own mailbox, in two folders, are given the content of their
   * cached rows -- the excerpt and the attachments, never the body -- and the size and
   * draft of their conversations, counted over the user's own folders, out of one batch
   * read of the rows and one count for the mailbox.
   */
  @Test
  void ownHitsAreGivenTheListedRowsDataInOneBatch() {
    EmailSearchResult inbox = hit(7L, MailFolder.INBOX);
    EmailSearchResult sent = hit(8L, MailFolder.SENT);
    EmailAttachment attachment = new EmailAttachment();
    attachment.setName("figures.xlsx");
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(7L, 8L))).thenReturn(Map.of(7L,
                                                                                         row(7L, MailFolder.INBOX, "t1", attachment),
                                                                                         8L,
                                                                                         row(8L, MailFolder.SENT, "t2")));
    when(emailDelegationService.getDelegatedFolderKeys(USER)).thenReturn(List.of());
    when(emailBoxStorage.getThreadSummariesOf(USER, Set.of("t1", "t2"), List.of())).thenReturn(Map.of("t1",
                                                                              new ThreadSummary("t1", 3, true, List.of()),
                                                                              "t2",
                                                                              new ThreadSummary("t2", 2, false, List.of())));

    emailBoxService.decorateListedRows(USER, List.of(inbox, sent));

    assertNull(inbox.getContent().getBody(), "the body never travels with a listed mail");
    assertEquals("Excerpt of 7", inbox.getContent().getExcerpt());
    assertEquals(List.of(attachment), inbox.getContent().getAttachments());
    assertEquals(3, inbox.getThreadCount());
    assertTrue(inbox.getThreadHasDraft());
    assertNull(sent.getContent().getBody());
    assertEquals("Excerpt of 8", sent.getContent().getExcerpt());
    assertEquals(List.of(), sent.getContent().getAttachments());
    assertEquals(2, sent.getThreadCount());
    assertFalse(sent.getThreadHasDraft());
    verify(emailBoxStorage, times(1)).getListedEmailsByIds(anyString(), any());
    verify(emailBoxStorage, times(1)).getThreadSummariesOf(anyString(), any(), anyList());
    verify(emailBoxStorage, never()).getThreadSummaries(anyString(), any());
    verify(emailBoxStorage, never()).getThreadSummaries(anyString(), any(), anyList());
  }

  /**
   * A Suggestions view's mail is given them as a hit is; one whose conversation the count
   * does not know -- not threaded yet -- keeps no size, which the row reads as a lone mail.
   */
  @Test
  void aSuggestionsMailIsGivenThemAsAHitIs() {
    EmailWaitingSuggestionMail threaded = suggestion(7L, MailFolder.INBOX);
    EmailWaitingSuggestionMail lone = suggestion(8L, MailFolder.ARCHIVE);
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(7L, 8L))).thenReturn(Map.of(7L,
                                                                                         row(7L, MailFolder.INBOX, "t1"),
                                                                                         8L,
                                                                                         row(8L, MailFolder.ARCHIVE, null)));
    when(emailDelegationService.getDelegatedFolderKeys(USER)).thenReturn(List.of(SHARED));
    when(emailBoxStorage.getThreadSummariesOf(USER, Set.of("t1"), List.of(SHARED))).thenReturn(Map.of("t1",
                                                                                               new ThreadSummary("t1",
                                                                                                                 5,
                                                                                                                 false,
                                                                                                                 List.of())));

    emailBoxService.decorateListedRows(USER, List.of(threaded, lone));

    assertEquals("Excerpt of 7", threaded.getContent().getExcerpt());
    assertEquals(5, threaded.getThreadCount());
    assertFalse(threaded.getThreadHasDraft());
    assertEquals("Excerpt of 8", lone.getContent().getExcerpt());
    assertNull(lone.getThreadCount());
    assertNull(lone.getThreadHasDraft());
  }

  /**
   * A mail is left as it came when it is not in the local copy (no id), when its id names
   * no cached row of the user's -- the read is the user's own rows only, so another
   * user's id answers nothing --, or when the row is filed in another folder than the one
   * the mail was listed from. No mail with an id: nothing is read at all.
   */
  @Test
  void aMailWithNoCachedRowOfTheUsersInItsFolderIsLeftAsItCame() {
    EmailSearchResult notCached = hit(null, MailFolder.INBOX);
    EmailSearchResult othersRow = hit(9L, MailFolder.INBOX);
    EmailSearchResult otherFolder = hit(7L, MailFolder.SENT);
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(7L, 9L))).thenReturn(Map.of(7L, row(7L, MailFolder.INBOX, "t1")));

    emailBoxService.decorateListedRows(USER, List.of(notCached, othersRow, otherFolder));

    for (EmailSearchResult hit : List.of(notCached, othersRow, otherFolder)) {
      assertNull(hit.getContent());
      assertNull(hit.getThreadCount());
      assertNull(hit.getThreadHasDraft());
    }
    verify(emailBoxStorage, never()).getThreadSummariesOf(anyString(), any(), anyList());

    emailBoxService.decorateListedRows(USER, List.of(hit(null, MailFolder.INBOX)));
    emailBoxService.decorateListedRows(" ", List.of(hit(7L, MailFolder.INBOX)));
    verify(emailBoxStorage, times(1)).getListedEmailsByIds(anyString(), any());
  }

  /**
   * A cached row of a folder the user may not list a mail of is never shown so, though it
   * is the user's: Trash, Spam and All Mail, and a folder of a mailbox shared with them
   * that its search may not read, or whose share is no longer accepted.
   */
  @Test
  void aRowOfAFolderTheUserMayNotListIsNeverGivenThem() {
    String unreadable = "CUSTOM:9";
    String revoked = "CUSTOM:10";
    EmailSearchResult trash = hit(1L, MailFolder.TRASH);
    EmailSearchResult junk = hit(2L, MailFolder.JUNK);
    EmailSearchResult allMail = hit(3L, MailFolder.ALL_MAIL);
    EmailSearchResult notReadable = hit(4L, unreadable);
    EmailSearchResult notAccepted = hit(5L, revoked);
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(1L, 2L, 3L, 4L, 5L))).thenReturn(Map.of(1L,
                                                                                                    row(1L, MailFolder.TRASH, "t1"),
                                                                                                    2L,
                                                                                                    row(2L, MailFolder.JUNK, "t1"),
                                                                                                    3L,
                                                                                                    row(3L, MailFolder.ALL_MAIL, "t1"),
                                                                                                    4L,
                                                                                                    row(4L, unreadable, "t1"),
                                                                                                    5L,
                                                                                                    row(5L, revoked, "t1")));
    when(emailDelegationService.delegationOf(USER, unreadable)).thenReturn(share(9L));
    when(emailDelegationService.delegationOf(USER, revoked)).thenReturn(share(10L));
    when(emailDelegationService.isSearchableSharedFolder(USER, unreadable)).thenReturn(false);
    when(emailDelegationService.isSearchableSharedFolder(USER, revoked)).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));

    emailBoxService.decorateListedRows(USER, List.of(trash, junk, allMail, notReadable, notAccepted));

    for (EmailSearchResult hit : List.of(trash, junk, allMail, notReadable, notAccepted)) {
      assertNull(hit.getContent(), hit.getFolder());
      assertNull(hit.getThreadCount(), hit.getFolder());
    }
    verify(emailBoxStorage, never()).getMailboxThreadSummariesOf(anyString(), anyList(), any());
    verify(emailBoxStorage, never()).getThreadSummariesOf(anyString(), any(), anyList());
  }

  /**
   * A hit of a folder of a mailbox shared with the user that its search may read is given
   * them, its conversation counted within that mailbox's own folders, never with the
   * user's own copies.
   */
  @Test
  void aHitOfAReadableSharedFolderIsCountedWithinThatMailbox() {
    EmailSearchResult shared = hit(7L, SHARED);
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(7L))).thenReturn(Map.of(7L, row(7L, SHARED, "t1")));
    when(emailDelegationService.delegationOf(USER, SHARED)).thenReturn(share(8L));
    when(emailDelegationService.isSearchableSharedFolder(USER, SHARED)).thenReturn(true);
    when(emailDelegationService.getMailboxFolderKeys(USER, 8L)).thenReturn(List.of(SHARED, "CUSTOM:11"));
    when(emailBoxStorage.getMailboxThreadSummariesOf(USER, List.of(SHARED, "CUSTOM:11"), Set.of("t1"))).thenReturn(Map.of("t1",
                                                                                                          new ThreadSummary("t1",
                                                                                                                            4,
                                                                                                                            false,
                                                                                                                            List.of())));

    emailBoxService.decorateListedRows(USER, List.of(shared));

    assertEquals("Excerpt of 7", shared.getContent().getExcerpt());
    assertEquals(4, shared.getThreadCount());
    verify(emailBoxStorage, never()).getThreadSummariesOf(anyString(), any(), anyList());
  }

  /**
   * The mails of one folder of the user's own -- a folder they made, which the
   * Suggestions view lists from -- look the folder's share up once, not once per mail:
   * the folder has none, and that answer is kept too.
   */
  @Test
  void aFoldersShareIsLookedUpOnceWhateverTheNumberOfItsMails() {
    String own = "CUSTOM:3";
    EmailWaitingSuggestionMail first = suggestion(7L, own);
    EmailWaitingSuggestionMail second = suggestion(8L, own);
    EmailWaitingSuggestionMail third = suggestion(9L, own);
    when(emailBoxStorage.getListedEmailsByIds(USER, Set.of(7L, 8L, 9L))).thenReturn(Map.of(7L,
                                                                                             row(7L, own, "t1"),
                                                                                             8L,
                                                                                             row(8L, own, "t2"),
                                                                                             9L,
                                                                                             row(9L, own, "t2")));
    when(emailDelegationService.getDelegatedFolderKeys(USER)).thenReturn(List.of());
    when(emailBoxStorage.getThreadSummariesOf(USER, Set.of("t1", "t2"), List.of())).thenReturn(Map.of("t2",
                                                                                                    new ThreadSummary("t2",
                                                                                                                      2,
                                                                                                                      false,
                                                                                                                      List.of())));

    emailBoxService.decorateListedRows(USER, List.of(first, second, third));

    assertEquals("Excerpt of 9", third.getContent().getExcerpt());
    assertNull(first.getThreadCount(), "a conversation the count does not know keeps no size");
    assertEquals(2, second.getThreadCount());
    assertEquals(2, third.getThreadCount());
    verify(emailDelegationService, times(1)).delegationOf(USER, own);
    verify(emailBoxStorage, times(1)).getThreadSummariesOf(anyString(), any(), anyList());
  }

  /**
   * A search hit, named by its local id and the folder it was found in.
   *
   * @param emailId the local id, null for a hit not in the local copy
   * @param folder the folder
   * @return the hit
   */
  private static EmailSearchResult hit(Long emailId, String folder) {
    return new EmailSearchResult(40L, folder, "Subject", null, new Date(), false, false, emailId != null, null, emailId);
  }

  /**
   * A Suggestions view's mail, named by its local id and its folder.
   *
   * @param emailId the local id
   * @param folder the folder
   * @return the mail
   */
  private static EmailWaitingSuggestionMail suggestion(Long emailId, String folder) {
    EmailWaitingSuggestionMail mail = new EmailWaitingSuggestionMail();
    mail.setEmailId(emailId);
    mail.setFolder(folder);
    return mail;
  }

  /**
   * A cached row as the folder list maps it, with a body the list never shows.
   *
   * @param id the local id
   * @param folder the folder it is filed in
   * @param threadId its conversation, may be null
   * @param attachments its attachments
   * @return the row
   */
  private static Email row(Long id, String folder, String threadId, EmailAttachment... attachments) {
    Email email = new Email();
    email.setId(id);
    email.setFolder(folder);
    email.setThreadId(threadId);
    email.setContent(new EmailContent("<p>Body of " + id + "</p>", "Excerpt of " + id, List.of(attachments)));
    return email;
  }

  /**
   * A share of the user's.
   *
   * @param id its id
   * @return the share
   */
  private static EmailDelegation share(long id) {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(id);
    return delegation;
  }
}
