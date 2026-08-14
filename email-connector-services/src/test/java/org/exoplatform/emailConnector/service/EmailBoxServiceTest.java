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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.HeaderTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchException;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.ResyncData;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.EmailSentEvent;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxSyncState;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.ThreadSummary;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;

import io.meeds.social.category.model.Category;
import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.model.CategoryWithName;
import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;
import io.meeds.social.util.JsonUtils;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailBoxService.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxServiceTest {

  private static final String     TEST_USER = "testuser";

  // A fixed, readable origin for the body-prefetch timing tests: the rule is a pure
  // function of instants, so it is exercised at chosen instants rather than by really
  // waiting out its windows.
  private static final long       SUBMITTED_AT = 1_000_000L;

  // The documented bounds, spelled out rather than read back from the service, so the
  // tests pin the behaviour instead of restating whatever the constants happen to say.
  private static final long       CONNECT_BOUND_MS = 3 * 60_000L;

  private static final long       SILENCE_BOUND_MS = 90_000L;

  private static final long       FAR_DEADLINE = SUBMITTED_AT + 10 * 60_000L;

  @MockBean
  private UserEmailSettingService userEmailSettingService;

  @MockBean
  private EmailBoxStorage         emailBoxStorage;

  @MockBean
  private SettingService          settingService;

  @MockBean
  private JobSchedulerService     jobSchedulerService;

  @MockBean
  private ListenerService         listenerService;

  @MockBean
  private EmailConnectorService   emailConnectorService;

  @MockBean
  private CategoryLinkService     categoryLinkService;

  @MockBean
  private CategoryService         categoryService;

  @MockBean
  private ApplicationEventPublisher eventPublisher;

  @MockBean
  private EmailFavoriteService    emailFavoriteService;

  @Autowired
  private EmailBoxService         emailBoxService;

  @Test
  @SneakyThrows
  void synchronize() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage message1 = mock(MimeMessage.class);
    when(message1.getSubject()).thenReturn("message1Subject");
    MimeMessage message2 = mock(MimeMessage.class);
    when(message2.getSubject()).thenReturn("message2Subject");
    MimeMessage[] messages = { message1, message2 };
    when((inbox).getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<Email>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(userEmailSettingService, times(2)).setUserEmailSetting(any(UserEmailSetting.class), anyString(), anyBoolean());
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(1000);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(2)).createEmail(any(Email.class));
    verify(inbox, times(2)).close(false);
    verify(store, times(2)).close();
  }

  @Test
  void getEmailBox() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.getEmailBox(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.getEmailBox(TEST_USER);
    verify(userEmailSettingService, times(2)).getUserEmailSetting(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER, "INBOX");
  }

  /**
   * The listing carries the conversation summaries the folder it lists cannot hold:
   * the cross-folder message count, whether a conversation has a reply the user
   * never sent, and who it is with. One read of the summary for the whole page — the
   * alternative is a lookup per visible row.
   * <p>
   * The owner's own address goes down with the read, and the verify is on the pair
   * rather than on the username alone: it is what keeps the user's name out of the
   * participant list of their own drafts, and the mailbox binding is the only place
   * that knows the address.
   */
  @Test
  void getEmailBoxCarriesTheConversationSummaries() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    ThreadSummary summary = new ThreadSummary("thread-1", 2, true, List.of("Veronika"));
    when(emailBoxStorage.getThreadSummaries(TEST_USER, "testEmail")).thenReturn(Map.of("thread-1", summary));

    EmailBox emailBox = emailBoxService.getEmailBox(TEST_USER, MailFolder.INBOX);

    verify(emailBoxStorage, times(1)).getThreadSummaries(TEST_USER, "testEmail");
    assertEquals(summary, emailBox.getThreadSummaries().get("thread-1"));
    assertTrue(emailBox.getThreadSummaries().get("thread-1").hasDraft());
  }

  /**
   * The Drafts folder lists its own rows, and the summary is what those rows are
   * labelled with: a draft's own sender is the account owner, so the name on the row
   * comes from the conversation the summary describes rather than from the row
   * itself.
   */
  @Test
  void getEmailBoxOnTheDraftsFolderListsTheDraftRowsThemselves() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email draft = new Email();
    draft.setFolder(MailFolder.DRAFTS);
    draft.setDraftLocalId("draft-1");
    draft.setThreadId("thread-1");
    when(emailBoxStorage.getEmails(TEST_USER, MailFolder.DRAFTS)).thenReturn(List.of(draft));
    when(emailBoxStorage.getThreadSummaries(TEST_USER, "testEmail")).thenReturn(Map.of("thread-1",
                                                                                              new ThreadSummary("thread-1",
                                                                                                                2,
                                                                                                                true,
                                                                                                                List.of("Veronika"))));

    EmailBox emailBox = emailBoxService.getEmailBox(TEST_USER, MailFolder.DRAFTS);

    assertEquals(1, emailBox.getEmails().size());
    assertEquals("draft-1", emailBox.getEmails().get(0).getDraftLocalId());
    assertTrue(emailBox.getThreadSummaries().get("thread-1").hasDraft());
    assertEquals(List.of("Veronika"),
                 emailBox.getThreadSummaries().get("thread-1").participants(),
                 "the draft row is named after the conversation, which the row itself cannot say");
  }

  @Test
  void getEmailBoxStarredOnlyReadsTheStarredSubset() throws Exception {
    // The starred filter must go through the dedicated starred query, not load the
    // whole folder and filter in memory.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.getEmailBox(TEST_USER, MailFolder.INBOX, true);
    verify(emailBoxStorage).getStarredEmails(TEST_USER, "INBOX");
    verify(emailBoxStorage, never()).getEmails(anyString(), anyString());
  }

  @Test
  void deleteUserEmails() {
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
  }

  @Test
  void scheduleEmailBoxUserSyncJob() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    emailBoxService.scheduleEmailBoxUserSyncJob(TEST_USER);
    verify(jobSchedulerService).removeJob(any(JobInfo.class));
    verify(jobSchedulerService).addPeriodJob(any(JobInfo.class), any(PeriodInfo.class));
  }

  @Test
  void broadcastOpenEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.broadcastOpenEmail(TEST_USER);
    verify(listenerService).broadcast(EmailConnectorUtils.OPEN_EMAIL, TEST_USER, "connector");
  }

  @Test
  void broadcastAccessWebmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.broadcastAccessWebmail(TEST_USER);
    verify(listenerService).broadcast(EmailConnectorUtils.ACCESS_WEBMAIL, TEST_USER, "connector");
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() throws IllegalAccessException {
    emailBoxService.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, false, false, false, false);
    verify(emailBoxStorage).getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", false, false, false);
  }

  @Test
  void getEmailById() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    emailBoxService.getEmailById(121l, TEST_USER);
    verify(emailBoxStorage).getEmailById(121l, TEST_USER, "testEmail");
  }

  @Test
  void getOwnedEmailById() throws IllegalAccessException {
    // The Favorites drawer reaches this with an id the platform stored, and an id is
    // guessable, so ownership is checked here rather than assumed by the caller.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    Email email = new Email();
    email.setId(121l);
    email.setUserId(TEST_USER);
    when(emailBoxStorage.getEmailById(121l, TEST_USER, "testEmail")).thenReturn(email);
    assertSame(email, emailBoxService.getOwnedEmailById(121l, TEST_USER));
  }

  @Test
  void getOwnedEmailByIdOfSomebodyElse() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    Email email = new Email();
    email.setId(121l);
    email.setUserId("someoneelse");
    when(emailBoxStorage.getEmailById(121l, TEST_USER, "testEmail")).thenReturn(email);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.getOwnedEmailById(121l, TEST_USER));
  }

  @Test
  void getOwnedEmailByIdOfUnknownEmail() throws IllegalAccessException {
    // Nothing cached under that id: null, not a refusal — the REST layer turns both
    // into the same 404 anyway.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(emailBoxStorage.getEmailById(121l, TEST_USER, "testEmail")).thenReturn(null);
    assertNull(emailBoxService.getOwnedEmailById(121l, TEST_USER));
  }

  @Test
  void updateEmailReadStatus() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> mailRemoteIds = List.of(1212l);
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, false));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, false);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    reset(emailBoxStorage);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    int failed = emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, false, true);
    org.junit.jupiter.api.Assertions.assertEquals(0, failed);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, false, "INBOX");
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.SEEN, false);
    verify(inbox).close(false);
    verify(store).close();

    // getMessageByUID returns null (UID unknown to the server): the remote update
    // must be counted as a failure and the optimistic local change reverted.
    reset(emailBoxStorage);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(null);
    int failedWhenNotFound = emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, true);
    org.junit.jupiter.api.Assertions.assertEquals(1, failedWhenNotFound);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1212l), TEST_USER, false, "INBOX");
  }

  @Test
  void updateEmailStarredStatus() throws Exception {
    // The star toggle follows the read-status discipline exactly: optimistic local
    // write, then the \Flagged push to the IMAP server -- the flag on the server is
    // what makes the star visible in Gmail and on the user's phone.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> mailRemoteIds = List.of(1212l);
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.updateEmailStarredStatus(mailRemoteIds, TEST_USER, true, false));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.updateEmailStarredStatus(mailRemoteIds, TEST_USER, true, false);
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    reset(emailBoxStorage);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    int failed = emailBoxService.updateEmailStarredStatus(mailRemoteIds, TEST_USER, true, true);
    assertEquals(0, failed);
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.FLAGGED, true);
    verify(inbox).close(false);
    verify(store).close();

    // getMessageByUID returns null (UID unknown to the server): the remote update
    // must be counted as a failure and the optimistic local star reverted.
    reset(emailBoxStorage);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(null);
    int failedWhenNotFound = emailBoxService.updateEmailStarredStatus(mailRemoteIds, TEST_USER, true, true);
    assertEquals(1, failedWhenNotFound);
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(List.of(1212l), TEST_USER, false, "INBOX");
  }

  @Test
  void updateEmailStarredStatusRevertsWhenTheServerRejectsTheFlag() throws Exception {
    // The compensating revert is not optional: a star the server refused must not
    // survive locally, or the mailbox shows a star no other mail client will ever
    // see -- the exact desync the server-side flag exists to prevent.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    doThrow(new MessagingException("STORE rejected")).when(message).setFlag(Flags.Flag.FLAGGED, true);
    int failed = emailBoxService.updateEmailStarredStatus(List.of(1212l), TEST_USER, true, true);
    assertEquals(1, failed);
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(List.of(1212l), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(List.of(1212l), TEST_USER, false, "INBOX");
  }

  @Test
  void deleteEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> emailIds = List.of(1212l);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.deleteEmail(emailIds, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder trashFolder = mock(IMAPFolder.class);
    when(trashFolder.getFullName()).thenReturn("trash");
    Folder[] folders = new Folder[] { trashFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    when(trashFolder.exists()).thenReturn(true);
    when(trashFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    emailBoxService.deleteEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.DELETED, true);
    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(inbox).close(true);
    verify(store).close();
  }

  @Test
  void deleteEmailToleratesGmailTrashExpunge() throws Exception {
    // On Gmail, copying into [Gmail]/Trash moves the message and expunges the source,
    // so the following setFlag(DELETED) throws MessageRemovedException. The delete has
    // in fact succeeded, so it must NOT be counted as a failure nor re-insert the row.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder trashFolder = mock(IMAPFolder.class);
    when(trashFolder.getFullName()).thenReturn("trash");
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { trashFolder });
    when(trashFolder.exists()).thenReturn(true);
    when(trashFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    doThrow(new MessageRemovedException()).when(message).setFlag(Flags.Flag.DELETED, true);

    emailBoxService.deleteEmail(List.of(1212l), TEST_USER);

    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    // The delete succeeded on the server, so the compensating re-insert must NOT fire.
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  void getThreadReadsCacheWithoutImap() throws Exception {
    // getThread is the fast path: a pure cache read, never an IMAP connection, so the
    // reader can render the conversation instantly on open.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<self@host>");
    cached.setThreadId("<self@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    List<Email> thread = emailBoxService.getThread("<self@host>", TEST_USER);

    assertEquals(1, thread.size());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void completeThreadSkipsArchiveLookupWhenNothingMissing() throws Exception {
    // A conversation whose cached messages reference nothing external needs no archive
    // lookup — completeThread must not open an IMAP connection (keeps repeat opens fast).
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<self@host>");
    cached.setThreadId("<self@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    List<Email> thread = emailBoxService.completeThread("<self@host>", TEST_USER);

    assertEquals(1, thread.size());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void completeThreadRecoversArchivedAncestorFromAllMail() throws Exception {
    // The cached message references an ancestor we never synced (archived in Gmail).
    // completeThread must fetch it from All Mail, cache it under ALL_MAIL, and unify
    // the fragments INTO the opened thread id (not flip to the older root id).
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<reply@host>");
    cached.setThreadId("<reply@host>");
    cached.setMailReferences("<root@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder allMail = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(allMail.exists()).thenReturn(true);
    when(allMail.getAttributes()).thenReturn(new String[] { "\\All" });
    when(allMail.isOpen()).thenReturn(true);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { allMail });
    MimeMessage archived = mock(MimeMessage.class);
    when(archived.getMessageID()).thenReturn("<root@host>");
    when(archived.getSubject()).thenReturn("root subject");
    when(allMail.search(any())).thenReturn(new Message[] { archived });
    when(((UIDFolder) allMail).getUID(archived)).thenReturn(999l);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(999l, TEST_USER, null, "ALL_MAIL", false, false, false)).thenReturn(null);
    when(emailBoxStorage.getSiblingThreadIds(anyString(), anyList())).thenReturn(List.of("<reply@host>", "<root@host>"));

    emailBoxService.completeThread("<reply@host>", TEST_USER);

    verify(allMail).search(any());
    // The archived ancestor is persisted under ALL_MAIL and the fragments are unified
    // into the opened id "<reply@host>".
    verify(emailBoxStorage).createEmail(any(Email.class));
    verify(emailBoxStorage).mergeThreads(TEST_USER, "<reply@host>", List.of("<root@host>"));
  }

  @Test
  void archiveEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> emailIds = List.of(1212l);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.archiveEmail(emailIds, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder archiveFolder = mock(IMAPFolder.class);
    when(archiveFolder.getFullName()).thenReturn("archive");
    Folder[] folders = new Folder[] { archiveFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    when(archiveFolder.exists()).thenReturn(true);
    when(archiveFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    emailBoxService.archiveEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(inbox).close(true);
    verify(store).close();
  }

  @Test
  void sendEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    Email email = email(TEST_USER);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.sendEmail(email, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    Session session = mock(Session.class);
    when(session.getProperties()).thenReturn(new Properties());
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder sentFolder = mock(IMAPFolder.class);
    when(sentFolder.getFullName()).thenReturn("sent");
    Folder[] folders = new Folder[] { sentFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    when(sentFolder.exists()).thenReturn(true);
    when(sentFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(sentFolder.isOpen()).thenReturn(true);
    try (MockedStatic<Session> sessionMock = mockStatic(Session.class);
        MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      sessionMock.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class))).thenReturn(session);
      emailBoxService.sendEmail(email, TEST_USER);
      transportMock.verify(() -> Transport.send(any(Message.class)));
      verify(sentFolder).open(Folder.READ_WRITE);
      verify(sentFolder).appendMessages(any(Message[].class));
      verify(sentFolder).close(false);
      verify(store).close();
    }
  }

  @Test
  void sendEmailPublishesSentRecipientsWithoutBcc() throws Exception {
    // The sent-mail event is what contact collection feeds on: it must carry To
    // and Cc, and must NEVER carry Bcc — that exclusion lives at the publish
    // site, not in the consumers.
    // The mock is pinned into the service by hand: for ApplicationEventPublisher the
    // context registers ITSELF as a resolvable dependency, and that candidate can win
    // the @Autowired resolution over the @MockBean, leaving the mock unobserved.
    ReflectionTestUtils.setField(emailBoxService, "eventPublisher", eventPublisher);
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    Email email = email(TEST_USER);
    email.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
    email.setCc(List.of(new EmailRecipient("Carol", "carol@example.org", null, false)));
    email.setBcc(List.of(new EmailRecipient("Hidden", "hidden@example.org", null, false)));
    Session session = mock(Session.class);
    when(session.getProperties()).thenReturn(new Properties());
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder sentFolder = mock(IMAPFolder.class);
    when(sentFolder.getFullName()).thenReturn("sent");
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { sentFolder });
    when(sentFolder.exists()).thenReturn(true);
    when(sentFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(sentFolder.isOpen()).thenReturn(true);
    try (MockedStatic<Session> sessionMock = mockStatic(Session.class);
        MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      sessionMock.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class))).thenReturn(session);
      emailBoxService.sendEmail(email, TEST_USER);
      ArgumentCaptor<EmailSentEvent> published = ArgumentCaptor.forClass(EmailSentEvent.class);
      verify(eventPublisher).publishEvent(published.capture());
      assertEquals(TEST_USER, published.getValue().getUsername());
      List<String> addresses = published.getValue().getRecipients().stream().map(EmailRecipient::getAddress).toList();
      assertEquals(List.of("bob@example.org", "carol@example.org"), addresses);
    }
  }

  @Test
  void sendReplyBuildsFullReferencesChain() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    // a reply whose parent already referenced <root@host>
    Email reply = email(TEST_USER);
    reply.setMailHeaderId("<parent@host>");
    when(emailBoxStorage.getMailReferencesByMailHeaderId("<parent@host>", TEST_USER)).thenReturn("<root@host>");
    Session session = mock(Session.class);
    when(session.getProperties()).thenReturn(new Properties());
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder sentFolder = mock(IMAPFolder.class);
    when(sentFolder.getFullName()).thenReturn("sent");
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { sentFolder });
    when(sentFolder.exists()).thenReturn(true);
    when(sentFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(sentFolder.isOpen()).thenReturn(true);
    try (MockedStatic<Session> sessionMock = mockStatic(Session.class);
        MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      sessionMock.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class))).thenReturn(session);
      emailBoxService.sendEmail(reply, TEST_USER);
      ArgumentCaptor<Message> sentCaptor = ArgumentCaptor.forClass(Message.class);
      transportMock.verify(() -> Transport.send(sentCaptor.capture()));
      Message sent = sentCaptor.getValue();
      // In-Reply-To is the parent; References is parent's chain + parent id (RFC 5322 §3.6.4)
      org.junit.jupiter.api.Assertions.assertEquals("<parent@host>", sent.getHeader("In-Reply-To")[0]);
      org.junit.jupiter.api.Assertions.assertEquals("<root@host> <parent@host>", sent.getHeader("References")[0]);
    }
  }

  @Test
  void getAttachmentByMailRemoteIdAnId() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    // No cached row under that folder and that UID. An answer, not a fault: the REST
    // layer turns it into a 404. It used to be an IllegalStateException, because the
    // code went straight on to set the bytes on the null it had just been handed and
    // the NullPointerException was swallowed into "error connecting to the store" —
    // a 500 blaming the mail server for a row this side does not have. The folder
    // being part of the lookup makes this reachable in the ordinary way (a caller
    // asking under the wrong one), so it is worth answering honestly.
    assertNull(emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX));
    EmailAttachment emailAtatchment = mock(EmailAttachment.class);
    when(emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX)).thenReturn(emailAtatchment);
    // The row exists but the message does not have the part it names: a genuine
    // fault, still reported as one.
    assertThrows(IllegalStateException.class,
                 () -> emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX));
    when(message.isMimeType("multipart/*")).thenReturn(true);
    Multipart multipart = mock(Multipart.class);
    when(message.getContent()).thenReturn(multipart);
    when(multipart.getCount()).thenReturn(1);
    assertThrows(IllegalStateException.class,
                 () -> emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX));
    when(multipart.getCount()).thenReturn(2);
    BodyPart bodyPart = mock(BodyPart.class);
    when(multipart.getBodyPart(anyInt())).thenReturn(bodyPart);
    InputStream is = mock(InputStream.class);
    when(bodyPart.getInputStream()).thenReturn(is);
    when(bodyPart.getContentType()).thenReturn("application/pdf");
    when(bodyPart.getFileName()).thenReturn("attachment.pdf");
    when(is.read(any(byte[].class))).thenReturn(1024).thenReturn(-1);
    emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX);
    verify(emailBoxStorage, times(4)).getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.INBOX);
    verify(emailAtatchment).setName("attachment.pdf");
    verify(emailAtatchment).setMimeType("application/pdf");
  }

  /**
   * The folder is resolved through the same resolver the rows were cached by, and
   * not assumed to be the inbox — which is the whole of slice 1 on the service side.
   * <p>
   * Before this change, an attachment on a message the user had SENT was looked for
   * in {@code INBOX}: either no message carries that UID there (a 500) or an
   * unrelated one does, and the user is handed a completely different message's file.
   *
   * @throws Exception when the mocked mail plumbing misbehaves
   */
  @Test
  void getAttachmentOfASentMessageOpensTheSentFolder() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder sent = mock(IMAPFolder.class);
    when(sent.exists()).thenReturn(true);
    when(sent.getFullName()).thenReturn("Sent");
    when(sent.getAttributes()).thenReturn(new String[] { "\\Sent" });
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.listSubscribed("*")).thenReturn(new Folder[] { sent });

    emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.SENT);

    verify(sent).open(Folder.READ_ONLY);
    verify(store, never()).getFolder("INBOX");
    verify(emailBoxStorage).getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER, MailFolder.SENT);
  }

  @Test
  void shouldNotifyForNewEmailNotifyAllTrue() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.TRUE);
    setting.setNotifyCategories(List.of(1L));
    // Notify-all wins even when the email's category is not among the selected ones.
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(99L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailNotifyAllNull() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(null);
    setting.setNotifyCategories(List.of(1L));
    // Null resolves to "notify for everything" (the default).
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(99L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailSelectedCategoriesMatch() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(2L, 5L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailSelectedCategoriesNoMatch() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    assertEquals(false, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(5L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailUncategorizedFallback() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    // Fallback: an uncategorized email (also the AI-off case) always notifies.
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(null), setting));
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of()), setting));
  }

  @Test
  void countedDeferralFiresOnlyOnTheLastRelease() {
    // Distinct user: the notification scheduler is shared across the test class, and a
    // stray timer from another test must not perturb the per-user verifications below.
    String user = "counteduser";
    // A streamed sync: window opened, three groups claimed by the classifier, window
    // completed -- the notification belongs to whichever release empties the window.
    emailBoxService.openNotificationWindow(user, List.of());
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    emailBoxService.notifyNewEmailsClassified(user);
    emailBoxService.notifyNewEmailsClassified(user);
    // Two of three groups classified: the messages of the third are still uncategorized,
    // so the per-category preference cannot be applied yet and nothing may fire.
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    emailBoxService.notifyNewEmailsClassified(user);
    // The last release empties the completed window: the send runs (observed through its
    // mailbox re-read), exactly once.
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  void countedDeferralIgnoresTransientZeroWhileSyncStillRuns() {
    String user = "earlyzerouser";
    emailBoxService.openNotificationWindow(user, List.of());
    // The classifier finishes the first group before the second is even broadcast: the
    // claim count touches zero while the download is still running. Firing here is the
    // bug streaming introduces -- the window being open must hold the notification.
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.notifyNewEmailsClassified(user);
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    // The next group arrives, the sync ends, and only then does its release fire.
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    emailBoxService.notifyNewEmailsClassified(user);
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  void partitionUidsBalancesContiguousChunks() {
    List<Long> uids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    List<long[]> chunks = EmailBoxService.partitionUids(uids, 3);
    assertEquals(3, chunks.size());
    // Balanced sizes, and the concatenation preserves the mailbox order exactly.
    assertArrayEquals(new long[] { 1L, 2L, 3L, 4L }, chunks.get(0));
    assertArrayEquals(new long[] { 5L, 6L, 7L }, chunks.get(1));
    assertArrayEquals(new long[] { 8L, 9L, 10L }, chunks.get(2));
  }

  @Test
  void partitionUidsWithFewerUidsThanChunks() {
    List<long[]> chunks = EmailBoxService.partitionUids(List.of(7L, 9L), 5);
    assertEquals(2, chunks.size());
    assertArrayEquals(new long[] { 7L }, chunks.get(0));
    assertArrayEquals(new long[] { 9L }, chunks.get(1));
    assertTrue(EmailBoxService.partitionUids(List.of(), 5).isEmpty());
    assertTrue(EmailBoxService.partitionUids(List.of(1L), 0).isEmpty());
  }

  @Test
  void getDefaultEmailCategoryIds() {
    // The importer has not run yet: no nameId -> id mapping is stored at all.
    assertTrue(emailBoxService.getDefaultEmailCategoryIds().isEmpty());

    // A stored id is returned, a missing one is skipped, and a malformed one is
    // ignored instead of propagating a NumberFormatException.
    mockCategoryIdSetting("emailImportantCategory", "11");
    mockCategoryIdSetting("emailInvitationCategory", null);
    mockCategoryIdSetting("emailNotificationCategory", "notANumber");
    mockCategoryIdSetting("emailToReviewCategory", "44");
    assertEquals(List.of(11L, 44L), emailBoxService.getDefaultEmailCategoryIds());
  }

  @Test
  void bodyPrefetchWaitBoundWaitsOutTheLoginsBeforeJudgingSilence() {
    // The regression this fixes: workers submitted at t=0 have not fetched a byte yet --
    // they are still handshaking, logging in and SELECTing -- so the drain must not judge
    // them silent at the 90s slice window. Until one of them reports itself fetching, the
    // only bound that applies is the connect bound, three minutes from submission.
    assertEquals(SUBMITTED_AT + CONNECT_BOUND_MS,
                 EmailBoxService.bodyPrefetchWaitBound(SUBMITTED_AT,
                                                       SUBMITTED_AT,
                                                       EmailBoxService.BODY_PREFETCH_NOT_FETCHING,
                                                       FAR_DEADLINE));
    // A fleet slow to authenticate but healthy: the first worker gets in at t=85s, i.e.
    // after the old window would already have cancelled it. The wait now runs to 85s+90s,
    // so the slices it goes on to deliver are still drained in parallel.
    long slowLoginAt = SUBMITTED_AT + 85_000L;
    assertEquals(slowLoginAt + SILENCE_BOUND_MS,
                 EmailBoxService.bodyPrefetchWaitBound(SUBMITTED_AT, SUBMITTED_AT, slowLoginAt, FAR_DEADLINE));
  }

  @Test
  void bodyPrefetchWaitBoundAbandonsAFleetThatNeverConnects() {
    // The other side of the same rule: waiting on logins is not waiting forever. A fleet
    // that never gets a single connection in is given up on at the connect bound, and the
    // sync falls back to its own already-authenticated connection.
    long waitBound = EmailBoxService.bodyPrefetchWaitBound(SUBMITTED_AT,
                                                           SUBMITTED_AT,
                                                           EmailBoxService.BODY_PREFETCH_NOT_FETCHING,
                                                           FAR_DEADLINE);
    assertEquals(SUBMITTED_AT + CONNECT_BOUND_MS, waitBound);
    assertTrue(waitBound - SUBMITTED_AT <= 3 * 60_000L, "a fleet that never connects must be abandoned promptly");
  }

  @Test
  void bodyPrefetchWaitBoundRestartsTheSilenceWindowOnEveryWait() {
    // Connect-awareness only moves the FIRST wait. Later waits start after slices have
    // been arriving, so they measure silence from their own start -- a fleet that
    // connected long ago and then went quiet is still abandoned one window later, not
    // ninety seconds after a login that happened half an hour ago.
    long connectedAt = SUBMITTED_AT + 20_000L;
    long laterWaitStart = SUBMITTED_AT + 30 * 60_000L;
    // The folder deadline is refreshed on every slice that arrives, so by this wait it
    // has moved with the download rather than staying pinned to submission.
    long refreshedDeadline = laterWaitStart + 10 * 60_000L;
    assertEquals(laterWaitStart + SILENCE_BOUND_MS,
                 EmailBoxService.bodyPrefetchWaitBound(laterWaitStart, SUBMITTED_AT, connectedAt, refreshedDeadline));
  }

  @Test
  void bodyPrefetchWaitBoundNeverOutlivesTheFolderDeadline() {
    long deadline = SUBMITTED_AT + 5_000L;
    assertEquals(deadline,
                 EmailBoxService.bodyPrefetchWaitBound(SUBMITTED_AT,
                                                       SUBMITTED_AT,
                                                       EmailBoxService.BODY_PREFETCH_NOT_FETCHING,
                                                       deadline));
    assertEquals(deadline, EmailBoxService.bodyPrefetchWaitBound(SUBMITTED_AT, SUBMITTED_AT, SUBMITTED_AT, deadline));
  }

  @Test
  void bodyPrefetchFleetOpensTheSilenceWindowOnTheFirstConnection() {
    EmailBoxService.BodyPrefetchFleet fleet = new EmailBoxService.BodyPrefetchFleet(System.currentTimeMillis());
    assertEquals(EmailBoxService.BODY_PREFETCH_NOT_FETCHING, fleet.fetchingSince());
    assertEquals(0, fleet.connectedWorkers());
    fleet.markFetching();
    long firstConnection = fleet.fetchingSince();
    assertTrue(firstConnection > EmailBoxService.BODY_PREFETCH_NOT_FETCHING);
    // A straggler logging in later counts, but must not push the window back: the fleet
    // has been capable of delivering since the first connection.
    fleet.markFetching();
    assertEquals(firstConnection, fleet.fetchingSince());
    assertEquals(2, fleet.connectedWorkers());
  }

  @Test
  @SneakyThrows
  void pollCompletedSliceReturnsTheFirstCompletedSlice() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CompletionService<Map<Long, EmailContent>> completedSlices = new ExecutorCompletionService<>(pool);
      EmailContent content = new EmailContent("body", null, null);
      completedSlices.submit(() -> Map.of(1L, content));
      EmailBoxService.BodyPrefetchFleet fleet = new EmailBoxService.BodyPrefetchFleet(System.currentTimeMillis());
      Future<Map<Long, EmailContent>> completed = emailBoxService.pollCompletedSlice(completedSlices,
                                                                                     System.currentTimeMillis() + 30_000L,
                                                                                     fleet,
                                                                                     TEST_USER);
      assertNotNull(completed);
      assertEquals(Map.of(1L, content), completed.get());
      // Nothing was given up on, so neither log path was taken.
      assertNull(fleet.givenUp());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SneakyThrows
  void pollCompletedSliceReportsLoginsNeverCompleted() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CompletionService<Map<Long, EmailContent>> completedSlices = new ExecutorCompletionService<>(pool);
      CountDownLatch neverCompletes = new CountDownLatch(1);
      completedSlices.submit(() -> {
        neverCompletes.await();
        return Map.of();
      });
      // Submitted four minutes ago and not one worker ever reported itself fetching: the
      // connect bound has elapsed, so the drain gives up -- and names the provider's
      // logins, not the transfers.
      EmailBoxService.BodyPrefetchFleet fleet =
                                             new EmailBoxService.BodyPrefetchFleet(System.currentTimeMillis() - 4 * 60_000L);
      assertNull(emailBoxService.pollCompletedSlice(completedSlices,
                                                    System.currentTimeMillis() + 10 * 60_000L,
                                                    fleet,
                                                    TEST_USER));
      assertEquals(EmailBoxService.BodyPrefetchGiveUp.LOGINS_NEVER_COMPLETED, fleet.givenUp());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SneakyThrows
  void pollCompletedSliceReportsFetchingWentSilent() {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CompletionService<Map<Long, EmailContent>> completedSlices = new ExecutorCompletionService<>(pool);
      CountDownLatch neverCompletes = new CountDownLatch(1);
      completedSlices.submit(() -> {
        neverCompletes.await();
        return Map.of();
      });
      EmailBoxService.BodyPrefetchFleet fleet = new EmailBoxService.BodyPrefetchFleet(System.currentTimeMillis());
      // This fleet did get in: a connection reached the folder and started fetching. The
      // wait is cut short by an already-elapsed folder deadline rather than by really
      // sitting out ninety seconds -- what is asserted is which failure the drain names
      // once it gives up, and that is decided by the fleet's state, not by which bound
      // expired. The window's own timing is covered by the bodyPrefetchWaitBound tests.
      fleet.markFetching();
      assertNull(emailBoxService.pollCompletedSlice(completedSlices, System.currentTimeMillis() - 1L, fleet, TEST_USER));
      assertEquals(EmailBoxService.BodyPrefetchGiveUp.FETCHING_WENT_SILENT, fleet.givenUp());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @SneakyThrows
  void synchronizeSkipsBodyPrefetchForSmallSync() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 3);
    emailBoxService.synchronize(TEST_USER);
    // Below the minimum batch the extra connections cost more than they save: the
    // parallel path must not run, and the bodies come from the serial loop as before.
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class), any(EmailConnector.class));
    verify(emailBoxStorage, times(3)).createEmail(any(Email.class));
    // The serial path streams nothing: one NEW_EMAILS_SYNCED with the whole sync's new
    // messages, then the completion event closing the run.
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED),
                                      eq(TEST_USER),
                                      argThat((List<Long> group) -> group.size() == 3));
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED),
                                      eq(TEST_USER),
                                      argThat((List<Long> all) -> all.size() == 3));
    // And the whole run is announced separately, after Sent and Archive. A consumer
    // that reads sent mail -- the contact backfill -- must not start on the inbox's
    // own completion: Sent is cached seconds later, and a first connection then
    // collected nobody and marked itself done for good.
    verify(listenerService).broadcast(eq(EmailConnectorUtils.MAILBOX_SYNC_COMPLETED), eq(TEST_USER), any());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void synchronizeSurvivesFailingBroadcasts() {
    // A listener that throws is the consumer's problem, never the sync's: the messages
    // are already cached when these events go out, so a failed broadcast must leave the
    // run SUCCESS rather than roll a completed download back into FAILURE. All three
    // announcements are covered here -- the per-group one, the inbox completion and the
    // whole-mailbox one this PR added.
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 3);
    doThrow(new RuntimeException("listener unavailable")).when(listenerService)
                                                        .broadcast(anyString(), any(), any());

    emailBoxService.synchronize(TEST_USER);

    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), eq(TEST_USER), any());
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED), eq(TEST_USER), any());
    verify(listenerService).broadcast(eq(EmailConnectorUtils.MAILBOX_SYNC_COMPLETED), eq(TEST_USER), any());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
    verify(emailBoxStorage, times(3)).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void getAvailableEmailCategories() {
    mockCategoryIdSetting("emailImportantCategory", "11");
    mockCategoryIdSetting("emailInvitationCategory", null);
    mockCategoryIdSetting("emailNotificationCategory", null);
    mockCategoryIdSetting("emailToReviewCategory", "44");
    when(categoryService.getCategory(11L, TEST_USER, Locale.ENGLISH)).thenReturn(new CategoryWithName(11L,
                                                                                                     0L,
                                                                                                     "Important",
                                                                                                     null,
                                                                                                     0L,
                                                                                                     0L,
                                                                                                     null));
    // A default category the user cannot see is skipped rather than failing the listing.
    when(categoryService.getCategory(44L, TEST_USER, Locale.ENGLISH)).thenThrow(ObjectNotFoundException.class);

    List<EmailCategory> categories = emailBoxService.getAvailableEmailCategories(TEST_USER, Locale.ENGLISH);
    assertEquals(1, categories.size());
    assertEquals(11L, categories.get(0).getId());
    assertEquals("Important", categories.get(0).getName());
  }

  @Test
  @SneakyThrows
  void synchronizeSkipsBodyPrefetchWhenSingleWorkerConfigured() {
    System.setProperty("email.connector.sync.body.fetch.threads", "1");
    try {
      UserEmailSetting userEmailSetting = userEmailSetting();
      mockInboxForSync(userEmailSetting, 12);
      emailBoxService.synchronize(TEST_USER);
      verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class), any(EmailConnector.class));
      verify(emailBoxStorage, times(12)).createEmail(any(Email.class));
    } finally {
      System.clearProperty("email.connector.sync.body.fetch.threads");
    }
  }

  @Test
  @SneakyThrows
  void linkEmailsToCategory() {
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(), 5L, TEST_USER));
    verify(categoryLinkService, never()).link(anyLong(), any(CategoryObject.class), anyString());

    // Unknown category: rejected up front, before any email is looked up.
    when(categoryService.getCategory(5L)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));

    when(categoryService.getCategory(5L)).thenReturn(new Category());
    // An email that is not the user's own (or no longer cached) is skipped, not linked.
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
    verify(categoryLinkService, never()).link(anyLong(), any(CategoryObject.class), anyString());

    mockOwnedEmail();
    assertEquals(1, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
    ArgumentCaptor<CategoryObject> objectCaptor = ArgumentCaptor.forClass(CategoryObject.class);
    verify(categoryLinkService).link(eq(5L), objectCaptor.capture(), eq(TEST_USER));
    assertEquals(EmailCategoryPlugin.OBJECT_TYPE, objectCaptor.getValue().getType());
    assertEquals("7", objectCaptor.getValue().getId());

    // Already in that category: idempotent, not counted as newly linked.
    doThrow(ObjectAlreadyExistsException.class).when(categoryLinkService)
                                               .link(anyLong(), any(CategoryObject.class), anyString());
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));

    // The category disappeared in between: surfaced as a 400, not a 500.
    doThrow(ObjectNotFoundException.class).when(categoryLinkService)
                                          .link(anyLong(), any(CategoryObject.class), anyString());
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
  }

  @Test
  @SneakyThrows
  void synchronizePrefetchesBodiesOverParallelConnections() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 100);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    // The workers' own connection: same folder re-opened by full name, its messages
    // resolved by UID. Every worker message carries a distinguishable body, while the
    // main connection's messages have none — so a created email carrying a
    // "prefetched-<uid>" body proves the parallel map was used, not the serial fetch.
    Store workerStore = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting, emailConnector)).thenReturn(workerStore);
    when(workerStore.isConnected()).thenReturn(true);
    Folder workerFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(workerStore.getFolder("INBOX")).thenReturn(workerFolder);
    when(workerFolder.isOpen()).thenReturn(true);
    Map<Long, Message> workerMessages = new HashMap<>();
    for (long uid = 1; uid <= 100; uid++) {
      MimeMessage workerMessage = mock(MimeMessage.class);
      when(workerMessage.isMimeType("text/*")).thenReturn(true);
      when(workerMessage.getContent()).thenReturn("prefetched-" + uid);
      when(((UIDFolder) workerFolder).getUID(workerMessage)).thenReturn(uid);
      workerMessages.put(uid, workerMessage);
    }
    // Read-only answer (all stubbing done above) so concurrent workers never stub.
    when(((UIDFolder) workerFolder).getMessagesByUID(any(long[].class))).thenAnswer(invocation -> {
      long[] uids = invocation.getArgument(0);
      Message[] result = new Message[uids.length];
      for (int i = 0; i < uids.length; i++) {
        result[i] = workerMessages.get(uids[i]);
      }
      return result;
    });
    emailBoxService.synchronize(TEST_USER);
    // 100 new UIDs cut into slices of 20 = 5 slices, each fetched on its own connection
    // and every one closed when its worker finished.
    verify(userEmailSettingService, times(5)).connect(userEmailSetting, emailConnector);
    verify(workerFolder, times(5)).close(false);
    verify(workerStore, times(5)).close();
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(100)).createEmail(emailCaptor.capture());
    // Bodies come from the workers. The rows land in slice-COMPLETION order, newest
    // slices submitted first -- deliberately NOT in ascending UID order. The ascending
    // assertion that used to live here encoded the forward-only threading lookup (a
    // parent had to be cached before its replies); computeThreadId now links
    // conversations in both directions, and the order-independence itself is proven by
    // the *StillFormsOneThread tests. What must hold here is that every message is
    // cached exactly once, with the body its worker prefetched.
    Set<Long> cachedUids = new HashSet<>();
    for (Email created : emailCaptor.getAllValues()) {
      assertEquals("prefetched-" + created.getMailRemoteId(), created.getContent().getBody());
      assertTrue(cachedUids.add(created.getMailRemoteId()), "every message must be cached exactly once");
    }
    assertEquals(100, cachedUids.size());
    // The new-emails events stream out during the download, one group every three slices
    // (3 x 20 = 60, then the remaining 40), so the AI categorization can start on the
    // first messages while the later ones are still being fetched. Which UIDs land in
    // which group depends on completion order; together they must cover the whole sync.
    @SuppressWarnings({ "unchecked", "rawtypes" })
    ArgumentCaptor<List<Long>> groupCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(listenerService, times(2)).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), eq(TEST_USER), groupCaptor.capture());
    List<List<Long>> groups = groupCaptor.getAllValues();
    assertEquals(60, groups.get(0).size());
    assertEquals(40, groups.get(1).size());
    Set<Long> broadcastUids = new HashSet<>(groups.get(0));
    broadcastUids.addAll(groups.get(1));
    assertEquals(cachedUids, broadcastUids);
    // ...and the completion event closes the run with every id, which is what whole-run
    // consumers (conversation alignment) key off.
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED),
                                      eq(TEST_USER),
                                      argThat((List<Long> all) -> all.size() == 100));
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void synchronizeSurvivesPrefetchWorkerFailure() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 12);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    // Every worker connection fails outright: the sync must neither fail nor lose
    // messages — the serial loop fetches every body itself, as before the prefetch.
    when(userEmailSettingService.connect(userEmailSetting, emailConnector)).thenThrow(new javax.mail.AuthenticationFailedException("Too many simultaneous connections"));
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(12)).createEmail(any(Email.class));
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void replyCachedBeforeParentStillFormsOneThread() {
    // Order-independence, direction one: the reply lands FIRST (newest-first caching
    // does exactly this on every reset), its parent second. The forward References
    // lookup finds nothing when the reply is cached, so only the reverse lookup run
    // when the parent lands can reunite them -- before it existed this conversation
    // silently split in two.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage reply = threadedMessage("<reply@host>", "<parent@host>", "<parent@host>", null, new Date(2000));
    MimeMessage parent = threadedMessage("<parent@host>", null, null, null, new Date(1000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { reply, parent });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(2, rows.size());
    assertEquals(rows.get(0).getThreadId(), rows.get(1).getThreadId(), "reply and parent must share one thread id");
  }

  @Test
  @SneakyThrows
  void lateMessageReferencingTwoThreadsMergesThemIntoTheOldest() {
    // Two fragments exist before the linking message arrives: A alone, and C whose only
    // reference points at a not-yet-cached B. When B finally lands it references A (the
    // forward lookup finds A's thread) while C references B (the reverse lookup finds
    // C's thread): one message bridges two conversations, and the merge collapses them
    // into the oldest thread whatever order the three were cached in.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage a = threadedMessage("<a@host>", null, null, null, new Date(1000));
    MimeMessage c = threadedMessage("<c@host>", "<b@host>", "<b@host>", null, new Date(3000));
    MimeMessage b = threadedMessage("<b@host>", "<a@host>", "<a@host>", null, new Date(2000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { a, c, b });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(3, rows.size());
    for (Email row : rows) {
      // A is the oldest message, so its thread id is the canonical one everything
      // collapses into -- including C, whose row was already stored with its own id
      // and must have been rewritten by the merge.
      assertEquals("<a@host>", row.getThreadId(), "all three fragments must collapse into the oldest thread");
    }
  }

  @Test
  @SneakyThrows
  void aStaleReferencesHeaderMergesConversationsThatAreNotRelated() {
    // The other side of lateMessageReferencingTwoThreadsMergesThemIntoTheOldest: the merge
    // trusts the References header completely, so any message naming 2+ already-cached
    // Message-IDs collapses their threads. Here A and B are unrelated conversations and the
    // third message is what a forwarded digest -- or a client dumping a stale References
    // list -- produces: it belongs to neither, yet it merges both.
    //
    // Pinned deliberately rather than asserted as correct. The merge is one-way (threads are
    // never split again), so a false merge is irreversible, and whether a bare 2+ hit should
    // really be enough is a product call. If the rule is tightened to demand a real chain
    // through both threads, this is the test that must change -- on purpose, not by surprise.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage a = threadedMessage("<unrelated-a@host>", null, null, null, new Date(1000));
    MimeMessage b = threadedMessage("<unrelated-b@host>", null, null, null, new Date(2000));
    MimeMessage digest =
                       threadedMessage("<digest@host>", "<unrelated-a@host> <unrelated-b@host>", null, null, new Date(3000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { a, b, digest });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(3, rows.size());
    for (Email row : rows) {
      assertEquals("<unrelated-a@host>",
                   row.getThreadId(),
                   "current behavior: a multi-id References header merges the threads it names, related or not");
    }
  }

  @Test
  @SneakyThrows
  void threadIndexGroupingIsOrderIndependent() {
    // Exchange mail with a broken References chain: the only grouping signal is the
    // Thread-Index conversation root. The root lookup matches any cached row carrying
    // the same root, in either direction, so the pair must group even when the newer
    // message deliberately lands before the conversation starter.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage newer = threadedMessage("<ti-reply@host>", null, null, threadIndex(27), new Date(2000));
    MimeMessage older = threadedMessage("<ti-root@host>", null, null, threadIndex(22), new Date(1000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { newer, older });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(2, rows.size());
    assertEquals(rows.get(0).getThreadId(), rows.get(1).getThreadId(), "a shared Thread-Index root must group either way");
  }

  @Test
  @SneakyThrows
  void interleavedRepliesAcrossParallelSlicesStillFormOneThreadEach() {
    // The full pipeline: 40 messages = 20 parent/reply pairs deliberately split across
    // two prefetch slices (parents in UIDs 1..20, replies in 21..40). The slices are
    // submitted newest-first and drained in completion order, so the replies routinely
    // land before their parents -- every pair must still come out as one conversation,
    // and no two pairs may bleed into each other.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage[] messages = new MimeMessage[40];
    for (int i = 0; i < 20; i++) {
      messages[i] = threadedMessage("<m" + (i + 1) + "@host>", null, null, null, new Date((i + 1) * 1000L));
      messages[20 + i] = threadedMessage("<r" + (i + 1) + "@host>",
                                         "<m" + (i + 1) + "@host>",
                                         "<m" + (i + 1) + "@host>",
                                         null,
                                         new Date((21 + i) * 1000L));
    }
    mockInboxWithMessages(userEmailSetting(), messages);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    mockPrefetchWorkerConnection(emailConnector, 40);
    emailBoxService.synchronize(TEST_USER);
    assertEquals(40, rows.size());
    Map<String, String> threadIdByHeaderId = rows.stream().collect(Collectors.toMap(Email::getMailHeaderId, Email::getThreadId));
    for (int i = 1; i <= 20; i++) {
      assertEquals(threadIdByHeaderId.get("<m" + i + "@host>"),
                   threadIdByHeaderId.get("<r" + i + "@host>"),
                   "pair " + i + " must share one thread id whatever order its slices completed in");
    }
    assertEquals(20, rows.stream().map(Email::getThreadId).distinct().count(), "the 20 conversations must stay distinct");
  }

  @Test
  @SneakyThrows
  void slowSliceDoesNotHoldUpCompletedSlices() {
    // The incident behind completion-order draining: one message trickling in slowly
    // stalled a whole mailbox for ~4 minutes while four workers sat idle holding
    // finished data, because slices were consumed strictly in sequence. Here the
    // NEWEST slice (UIDs 81..100, submitted first) blocks until the other eighty
    // messages have been cached; with a sequential drain that is a deadlock broken
    // only by the slice timeout, with completion-order draining it just finishes last.
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 100);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    CountDownLatch othersCached = new CountDownLatch(80);
    when(emailBoxStorage.createEmail(any(Email.class))).thenAnswer(invocation -> {
      othersCached.countDown();
      return invocation.getArgument(0);
    });
    mockPrefetchWorkerConnection(emailConnector, 100, uids -> {
      if (LongStream.of(uids).anyMatch(uid -> uid == 100L)) {
        // The slow slice: its FETCH does not return until every other slice's messages
        // have been cached by the sync thread -- which can only happen if the drain
        // consumes slices as they complete instead of waiting for this one.
        assertTrue(othersCached.await(30, TimeUnit.SECONDS),
                   "the other slices must be cached while the slow one is still fetching");
      }
      return null;
    });
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(100)).createEmail(emailCaptor.capture());
    List<Email> created = emailCaptor.getAllValues();
    // The blocked slice's messages are exactly the LAST twenty cached: nothing waited
    // for it, and nothing was lost to it.
    Set<Long> lastTwenty = created.subList(80, 100).stream().map(Email::getMailRemoteId).collect(Collectors.toSet());
    assertEquals(LongStream.rangeClosed(81, 100).boxed().collect(Collectors.toSet()), lastTwenty);
  }

  @Test
  @SneakyThrows
  void noOpSyncIssuesNoPerMessageStatements() {
    // The B1 regression guard. A sync that finds nothing new used to issue one SELECT
    // and two guarded UPDATEs per already-known message -- ~15,000 statements on a
    // 5000-message cache to discover nothing changed -- plus the full-entity folder
    // load with every body CLOB. It must now issue NO per-message statement and never
    // touch the full load: this is the regression that would silently return.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[12];
    List<Email> cached = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      messages[i] = flaggedMessage(false);
      cached.add(cachedEmail(i + 1, false, false));
    }
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(),
                                                                     anyString(),
                                                                     any(),
                                                                     anyString(),
                                                                     anyBoolean(),
                                                                     anyBoolean(),
                                                                     anyBoolean());
    verify(emailBoxStorage, never()).updateEmailReadStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    verify(emailBoxStorage, never()).updateEmailStarredStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
    verify(emailBoxStorage, never()).markEmailsAsNotRecent(anyList(), anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(emailBoxStorage, never()).getEmails(anyString(), anyString());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void flagChangesAreAppliedAsBulkUpdates() {
    // Read/unread changes made in another client must still land, but as at most
    // three bulk statements -- one per direction plus one recent-clear -- never as
    // per-message updates.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[6];
    List<Email> cached = new ArrayList<>();
    // UIDs 1-2: read on the server, unread in the cache -> one bulk mark-read.
    messages[0] = flaggedMessage(true);
    messages[1] = flaggedMessage(true);
    cached.add(cachedEmail(1, false, false));
    cached.add(cachedEmail(2, false, false));
    // UIDs 3-4: unread on the server, read in the cache -> one bulk mark-unread.
    messages[2] = flaggedMessage(false);
    messages[3] = flaggedMessage(false);
    cached.add(cachedEmail(3, true, false));
    cached.add(cachedEmail(4, true, false));
    // UID 5: still wearing the recent badge from its own sync -> one bulk clear.
    messages[4] = flaggedMessage(false);
    cached.add(cachedEmail(5, false, true));
    // UID 6: nothing changed -> touched by no statement at all.
    messages[5] = flaggedMessage(false);
    cached.add(cachedEmail(6, false, false));
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1L, 2L), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(3L, 4L), TEST_USER, false, "INBOX");
    verify(emailBoxStorage).markEmailsAsNotRecent(List.of(5L), TEST_USER, "INBOX");
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void starChangesMadeElsewhereAreAppliedAsBulkUpdates() {
    // A star set or cleared in another client (phone, Gmail) rides the same
    // reconcile as read/unread: applied as at most two bulk statements, one per
    // direction — never one statement per message. The exact-count verification is
    // the regression guard: a per-message implementation would still produce the
    // right end state, and only the statement count would betray it.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[5];
    List<Email> cached = new ArrayList<>();
    // UIDs 1-2: starred on the server, not in the cache -> one bulk star.
    messages[0] = flaggedMessage(false, true);
    messages[1] = flaggedMessage(false, true);
    cached.add(cachedEmail(1, false, false, false));
    cached.add(cachedEmail(2, false, false, false));
    // UID 3: unstarred on the server, starred in the cache -> one bulk unstar.
    messages[2] = flaggedMessage(false, false);
    cached.add(cachedEmail(3, false, false, true));
    // UIDs 4-5: star matches in both directions -> touched by no statement.
    messages[3] = flaggedMessage(false, true);
    cached.add(cachedEmail(4, false, false, true));
    messages[4] = flaggedMessage(false, false);
    cached.add(cachedEmail(5, false, false, false));
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(List.of(1L, 2L), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailStarredStatusByMailRemoteIds(List.of(3L), TEST_USER, false, "INBOX");
    verify(emailBoxStorage, times(2)).updateEmailStarredStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    verify(emailBoxStorage, never()).updateEmailReadStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void syncCachesTheServerStarWhenCreating() {
    // A message starred in another client BEFORE it was ever cached here must land
    // already starred: createEmails reads \Flagged off the same prefetched FLAGS as
    // SEEN, so a fresh cache shows the same stars as the phone.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = { flaggedMessage(false, true), flaggedMessage(false, false) };
    mockInboxWithMessages(userEmailSetting, messages);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(2)).createEmail(emailCaptor.capture());
    assertTrue(emailCaptor.getAllValues().get(0).isStarred());
    assertFalse(emailCaptor.getAllValues().get(1).isStarred());
  }

  @Test
  @SneakyThrows
  void mixedSyncCreatesNewAndReconcilesKnownInBulk() {
    // The general case: known-unchanged messages are untouched, a known flag change
    // goes through the bulk path, a lingering recent badge is cleared in bulk, and
    // the genuinely new messages are created -- the same end state the per-message
    // code produced, at O(changes) statements.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[5];
    List<Email> cached = new ArrayList<>();
    // UID 1: known, read on the server but not in the cache.
    messages[0] = flaggedMessage(true);
    cached.add(cachedEmail(1, false, false));
    // UID 2: known, recent badge to clear.
    messages[1] = flaggedMessage(false);
    cached.add(cachedEmail(2, false, true));
    // UID 3: known, nothing changed.
    messages[2] = flaggedMessage(false);
    cached.add(cachedEmail(3, false, false));
    // UIDs 4-5: new, a parent and its reply.
    messages[3] = threadedMessage("<new-parent@host>", null, null, null, new Date(4000));
    messages[4] = threadedMessage("<new-reply@host>", "<new-parent@host>", "<new-parent@host>", null, new Date(5000));
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(2)).createEmail(emailCaptor.capture());
    assertEquals(List.of(4L, 5L), emailCaptor.getAllValues().stream().map(Email::getMailRemoteId).toList());
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1L), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).markEmailsAsNotRecent(List.of(2L), TEST_USER, "INBOX");
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(),
                                                                     anyString(),
                                                                     any(),
                                                                     anyString(),
                                                                     anyBoolean(),
                                                                     anyBoolean(),
                                                                     anyBoolean());
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
  }

  @Test
  @SneakyThrows
  void backfillStillRunsPerRowButOnlyForRowsThatNeedIt() {
    // The threading backfill keeps its per-row writes -- but ONLY for rows that
    // genuinely need them, which is what keeps it out of the steady-state cost.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[] { flaggedMessage(false), flaggedMessage(false), flaggedMessage(false) };
    // UID 1: cached before threading existed -- no thread id at all.
    Email unthreaded = cachedEmail(1, false, false);
    unthreaded.setThreadId("");
    unthreaded.setThreadIndexRoot(null);
    // UID 2: threaded, but its Thread-Index root was never captured.
    Email rootless = cachedEmail(2, false, false);
    rootless.setThreadIndexRoot(null);
    // UID 3: fully backfilled -- must not be written at all.
    Email complete = cachedEmail(3, false, false);
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(List.of(unthreaded, rootless, complete));
    emailBoxService.synchronize(TEST_USER);
    // Exactly one thread-info backfill (UID 1) and one root capture (UID 2); the
    // messages carry no Thread-Index header, so the root is stored as "".
    verify(emailBoxStorage, times(1)).updateThreadInfo(eq(TEST_USER), eq(1L), anyString(), any(), any(), eq("INBOX"), eq(""));
    verify(emailBoxStorage, times(1)).updateThreadIndexRoot(TEST_USER, 2L, "INBOX", "");
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void unchangedSnapshotSkipsTheFolderEntirely() {
    // The B2 payoff and its regression guard: when every change signal matches the
    // snapshot, the folder must never be opened, never window-FETCHed, never loaded
    // from the cache, never broadcast -- 98% of a measured no-op sync was the window
    // FETCH alone. A fully-skipped run must also write no sync state back.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox, never()).open(anyInt());
    verify(inbox, never()).open(anyInt(), any(ResyncData.class));
    verify(inbox, never()).getMessages(anyInt(), anyInt());
    verify(inbox, never()).fetch(any(), any());
    verify(emailBoxStorage, never()).getSyncEmails(anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), any(), any());
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED), any(), any());
    verify(settingService, never()).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), any());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void newArrivalForcesTheFullPath() {
    // uidNext moved: at least one message arrived (even if deleted again since).
    assertFullSyncRunsWhenServerReports(11L, 502L, 100, 777L);
  }

  @Test
  @SneakyThrows
  void expungeForcesTheFullPath() {
    // uidNext unchanged but the count dropped: messages were deleted elsewhere.
    assertFullSyncRunsWhenServerReports(11L, 501L, 99, 777L);
  }

  @Test
  @SneakyThrows
  void remoteFlagChangeForcesTheFullPath() {
    // Only the mod-sequence moved: metadata changed -- a read/unread flip in
    // another client. This is the signal the whole CONDSTORE requirement exists for.
    assertFullSyncRunsWhenServerReports(11L, 501L, 100, 778L);
  }

  @Test
  @SneakyThrows
  void uidValidityChangeForcesTheFullPathWithoutTouchingTheCache() {
    // The server renumbered the folder (Gmail rebuild): every cached UID is
    // meaningless, so the ONLY acceptable reaction is the full path, which re-lists
    // the window and reconciles as it always did. Here the renumbered folder's
    // window is empty and the cache is empty, so the full path must run and destroy
    // nothing.
    IMAPFolder inbox = assertFullSyncRunsWhenServerReports(12L, 501L, 100, 777L);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  @Test
  @SneakyThrows
  void missingSnapshotTakesTheFullPath() {
    // First sync ever (or a reset invalidated the snapshot): nothing to compare,
    // no STATUS worth issuing -- straight to the full path.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void unparseableSyncStateTakesTheFullPathAndHeals() {
    // A corrupt state blob can never do worse than cost one full sync: it reads as
    // an empty state, the full path runs, and the fresh snapshot overwrites the
    // garbage.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    doReturn(SettingValue.create("this is not json")).when(settingService)
                                                     .get(any(Context.class), any(Scope.class), eq("emailBoxSyncState"));
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState healed = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class);
    assertEquals(501L, healed.getSnapshot(MailFolder.INBOX).getUidNext());
  }

  @Test
  @SneakyThrows
  void windowSizeChangeForcesTheFullPath() {
    // The admin grew the cache: the server is unchanged, but the wider window has
    // never been downloaded -- skipping would leave the mailbox permanently short.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 50));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void withoutCondstoreTheSkipIsNeverTaken() {
    // No CONDSTORE means an unchanged uidNext+messageCount still says NOTHING about
    // read/unread flags flipped in another client. Skipping would not make those
    // flags late -- it would make them stale forever. So on such servers the skip
    // is refused outright, even with every other signal matching.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, false);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void fullSyncCapturesTheSnapshotForTheNextRun() {
    // The other half of the contract: a full sync must leave behind the snapshot
    // that lets the NEXT run skip, with the SELECT-time signals and the window size.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 3, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    FolderSyncSnapshot snapshot = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class)
                                           .getSnapshot(MailFolder.INBOX);
    assertEquals(11L, snapshot.getUidValidity());
    assertEquals(501L, snapshot.getUidNext());
    assertEquals(3L, snapshot.getMessageCount());
    assertEquals(777L, snapshot.getHighestModSeq());
    assertEquals(100, snapshot.getWindowSize());
  }

  @Test
  @SneakyThrows
  void cachedFolderNamesSkipTheDiscoveryScan() {
    // Sent/Archive/Drafts used to be re-discovered with a LIST * over the whole
    // subscribed folder list on every sync; the remembered names replace that with
    // one single-folder exists() probe each.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    state.setSentFolderName("MySent");
    state.setArchiveFolderName("MyArchive");
    state.setDraftsFolderName("MyDrafts");
    mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    IMAPFolder sent = mock(IMAPFolder.class);
    when(sent.exists()).thenReturn(true);
    when(sent.getMessageCount()).thenReturn(0);
    IMAPFolder archive = mock(IMAPFolder.class);
    when(archive.exists()).thenReturn(true);
    when(archive.getMessageCount()).thenReturn(0);
    IMAPFolder drafts = mock(IMAPFolder.class);
    when(drafts.exists()).thenReturn(true);
    when(drafts.getMessageCount()).thenReturn(0);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    when(connectedStore.getFolder("MySent")).thenReturn(sent);
    when(connectedStore.getFolder("MyArchive")).thenReturn(archive);
    when(connectedStore.getFolder("MyDrafts")).thenReturn(drafts);
    emailBoxService.synchronize(TEST_USER);
    // All three resolved by name (and INBOX skipped): the full-list scan never runs.
    verify(connectedStore.getDefaultFolder(), never()).listSubscribed("*");
    verify(sent).open(Folder.READ_ONLY);
    verify(archive).open(Folder.READ_ONLY);
    verify(drafts).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void clearingAFolderCacheDropsItsSnapshotSoTheResyncCannotSkip() {
    // The reset flow: the INBOX rows are wiped while the SERVER still matches the
    // old snapshot exactly. If the snapshot survived, the resync would conclude
    // "nothing changed" over an empty cache and the mailbox would come up blank --
    // the silent failure this feature must never cause.
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    state.setSnapshot(MailFolder.SENT, new FolderSyncSnapshot(21L, 61L, 60, 888L, 100));
    doReturn(SettingValue.create(JsonUtils.toJsonString(state))).when(settingService)
                                                                .get(any(Context.class),
                                                                     any(Scope.class),
                                                                     eq("emailBoxSyncState"));
    when(emailBoxStorage.getEmails(TEST_USER, MailFolder.INBOX)).thenReturn(new ArrayList<>());
    emailBoxService.deleteUserEmails(TEST_USER, MailFolder.INBOX);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState cleared = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class);
    assertNull(cleared.getSnapshot(MailFolder.INBOX), "the cleared folder's snapshot must be gone");
    assertEquals(888L, cleared.getSnapshot(MailFolder.SENT).getHighestModSeq(), "other folders' snapshots must survive");
  }

  @Test
  @SneakyThrows
  void deletingTheWholeMailboxRemovesTheSyncState() {
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(new ArrayList<>());
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), eq("emailBoxSyncState"));
  }

  @Test
  @SneakyThrows
  void condstoreServerOpensWithExplicitModSeqRequest() {
    // The Stalwart fix: a server may advertise CONDSTORE yet only send HIGHESTMODSEQ
    // when explicitly asked (RFC 7162 obliges it no further) -- observed live as
    // benjamin's folders looping on "snapshot incomplete" forever. On such servers
    // the sync must open with SELECT (CONDSTORE), never with a plain SELECT.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY, ResyncData.CONDSTORE);
    verify(inbox, never()).open(anyInt());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void resyncRejectionFallsBackToPlainOpen() {
    // A server that advertises CONDSTORE but rejects the SELECT parameter must cost
    // nothing: the open falls back to a plain SELECT and the sync completes -- the
    // skip simply stays off for that folder, exactly the pre-fix behavior.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    when(inbox.open(Folder.READ_ONLY, ResyncData.CONDSTORE)).thenThrow(new MessagingException("CONDSTORE not supported"));
    lenient().when(inbox.isOpen()).thenReturn(false).thenReturn(true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void serverWithholdingModSeqStoresIncompleteSnapshotAndStaysOnTheFullPath() {
    // A server that ACCEPTS the CONDSTORE parameter but still provides no
    // mod-sequence: the capture stores -1, the state persists it (the log then
    // shows exactly which signal is missing), and the skip must never fire.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, -1L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState savedSyncState = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(),
                                                               MailboxSyncState.class);
    assertEquals(-1L, savedSyncState.getSnapshot(MailFolder.INBOX).getHighestModSeq());
  }

  @Test
  @SneakyThrows
  void incompleteSnapshotNeverSkips() {
    // The stored -1 mod-sequence (a capture the server left short) must keep forcing
    // the full path even when every other signal matches -- this is the exact state
    // benjamin's mailbox was stuck in, and it must stay a full sync, never a skip.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, -1L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, -1L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
  }

  /**
   * Runs a sync whose INBOX snapshot is {@code (uidValidity 11, uidNext 501, 100
   * messages, highestModSeq 777, window 100)} against a CONDSTORE server reporting
   * the given signals, and asserts the FULL path ran (folder opened, cache view
   * loaded) -- the shape shared by every one-signal-changed test.
   *
   * @param uidValidity the server-side UIDVALIDITY
   * @param uidNext the server-side UIDNEXT
   * @param messageCount the server-side message count
   * @param highestModSeq the server-side HIGHESTMODSEQ
   * @return the INBOX mock, for extra assertions
   */
  @SneakyThrows
  private IMAPFolder assertFullSyncRunsWhenServerReports(long uidValidity, long uidNext, int messageCount, long highestModSeq) {
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, uidValidity, uidNext, messageCount, highestModSeq, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
    return inbox;
  }

  /**
   * Wires a mailbox for the skip-check tests: an IMAP store (CONDSTORE-capable or
   * not), an INBOX reporting the given change signals over STATUS/SELECT, and the
   * previous run's sync state persisted in the settings mock (none when null). No
   * subscribed folders, so Sent/Archive syncs are no-ops when the full path runs.
   *
   * @param userEmailSetting the user's connector binding
   * @param storedState the persisted sync state, or null for a first sync
   * @param uidValidity the server-side UIDVALIDITY
   * @param uidNext the server-side UIDNEXT
   * @param messageCount the server-side message count
   * @param highestModSeq the server-side HIGHESTMODSEQ
   * @param condstore whether the store advertises CONDSTORE
   * @return the INBOX mock
   */
  @SneakyThrows
  private IMAPFolder mockInboxForSkipCheck(UserEmailSetting userEmailSetting,
                                           MailboxSyncState storedState,
                                           long uidValidity,
                                           long uidNext,
                                           int messageCount,
                                           long highestModSeq,
                                           boolean condstore) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    lenient().when(store.hasCapability("CONDSTORE")).thenReturn(condstore);
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    IMAPFolder inbox = mock(IMAPFolder.class);
    when(store.getFolder("INBOX")).thenReturn(inbox);
    lenient().when(inbox.getUIDValidity()).thenReturn(uidValidity);
    lenient().when(inbox.getUIDNext()).thenReturn(uidNext);
    lenient().when(inbox.getMessageCount()).thenReturn(messageCount);
    lenient().when(inbox.getHighestModSeq()).thenReturn(highestModSeq);
    lenient().when(inbox.isOpen()).thenReturn(true);
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    if (storedState != null) {
      doReturn(SettingValue.create(JsonUtils.toJsonString(storedState))).when(settingService)
                                                                        .get(any(Context.class),
                                                                             any(Scope.class),
                                                                             eq("emailBoxSyncState"));
    }
    Folder defaultFolder = mock(Folder.class);
    lenient().when(store.getDefaultFolder()).thenReturn(defaultFolder);
    lenient().when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * A mocked inbox message for the reconcile tests: only its SEEN flag matters, and
   * the flag comes off the batched window FETCH in production, so a bare mock with a
   * stubbed flag is the honest shape.
   *
   * @param seen the server-side SEEN flag
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage flaggedMessage(boolean seen) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.isSet(Flags.Flag.SEEN)).thenReturn(seen);
    lenient().when(message.getReceivedDate()).thenReturn(new Date());
    return message;
  }

  /**
   * Same as {@link #flaggedMessage(boolean)} with the server-side {@code \Flagged}
   * (star) flag also stubbed — for the starred-reconcile tests. The two flags ride
   * the same prefetched FLAGS item in production, so stubbing them on one mock is
   * the honest shape.
   *
   * @param seen the server-side SEEN flag
   * @param flagged the server-side FLAGGED flag
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage flaggedMessage(boolean seen, boolean flagged) {
    MimeMessage message = flaggedMessage(seen);
    lenient().when(message.isSet(Flags.Flag.FLAGGED)).thenReturn(flagged);
    return message;
  }

  /**
   * A cached row as the light sync view returns it: ids, flags and threading state,
   * no body. Threading is already backfilled (non-empty thread id, captured empty
   * Thread-Index root) so the reconcile tests exercise pure flag logic; the backfill
   * test overrides those fields explicitly.
   *
   * @param uid the IMAP UID
   * @param read the cached read flag
   * @param recent the cached recent flag
   * @return the light row
   */
  private Email cachedEmail(long uid, boolean read, boolean recent) {
    Email email = new Email();
    email.setId(uid * 100);
    email.setMailRemoteId(uid);
    email.setRead(read);
    email.setRecent(recent);
    email.setThreadId("<m" + uid + "@host>");
    email.setThreadIndexRoot("");
    email.setUserId(TEST_USER);
    email.setFolder("INBOX");
    return email;
  }

  /**
   * Same as {@link #cachedEmail(long, boolean, boolean)} with the cached starred
   * flag also set — for the starred-reconcile tests.
   *
   * @param uid the IMAP UID
   * @param read the cached read flag
   * @param recent the cached recent flag
   * @param starred the cached starred flag
   * @return the light row
   */
  private Email cachedEmail(long uid, boolean read, boolean recent, boolean starred) {
    Email email = cachedEmail(uid, read, recent);
    email.setStarred(starred);
    return email;
  }

  /**
   * Turns the mocked storage into a small in-memory mailbox for the threading tests:
   * created rows are remembered, and every thread lookup computeThreadId relies on
   * (forward References, reverse References, Thread-Index root, oldest-thread
   * canonicalization, merge) answers from what has actually been stored so far --
   * exactly what the service sees in production. Without this, the order-independence
   * tests would only prove that the service believes whatever a stubbed list says.
   *
   * @return the live list of stored rows, in creation order
   */
  private List<Email> stubStatefulThreadingStorage() {
    List<Email> rows = Collections.synchronizedList(new ArrayList<>());
    when(emailBoxStorage.createEmail(any(Email.class))).thenAnswer(invocation -> {
      Email email = invocation.getArgument(0);
      rows.add(email);
      return email;
    });
    when(emailBoxStorage.getSiblingThreadIds(anyString(), anyList())).thenAnswer(invocation -> {
      List<String> mailHeaderIds = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> row.getMailHeaderId() != null && mailHeaderIds.contains(row.getMailHeaderId()))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getThreadIdsReferencingMessageId(anyString(), anyString())).thenAnswer(invocation -> {
      String messageId = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> (row.getMailReferences() != null && row.getMailReferences().contains(messageId))
                     || (row.getInReplyTo() != null && row.getInReplyTo().contains(messageId)))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getThreadIdsByThreadIndexRoot(anyString(), anyString())).thenAnswer(invocation -> {
      String threadIndexRoot = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> threadIndexRoot.equals(row.getThreadIndexRoot()))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getOldestThreadId(anyString(), anyList())).thenAnswer(invocation -> {
      List<String> threadIds = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> threadIds.contains(row.getThreadId()))
                 .sorted(Comparator.comparing(Email::getReceivedDate))
                 .map(Email::getThreadId)
                 .findFirst()
                 .orElse(null);
    });
    doAnswer(invocation -> {
      String canonicalThreadId = invocation.getArgument(1);
      List<String> threadIds = invocation.getArgument(2);
      rows.stream().filter(row -> threadIds.contains(row.getThreadId())).forEach(row -> row.setThreadId(canonicalThreadId));
      return null;
    }).when(emailBoxStorage).mergeThreads(anyString(), anyString(), anyList());
    return rows;
  }

  /**
   * A mocked inbox message carrying real threading headers, as the sync connection's
   * batched fetch would expose them. Only non-null headers are stubbed, so an absent
   * header behaves exactly like production (getHeader returns null).
   *
   * @param messageId the Message-ID header (angle-bracketed), may be null
   * @param references the raw References header, may be null
   * @param inReplyTo the raw In-Reply-To header, may be null
   * @param threadIndex the raw Exchange Thread-Index header, may be null
   * @param receivedDate the received date, which drives oldest-thread canonicalization
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage threadedMessage(String messageId, String references, String inReplyTo, String threadIndex, Date receivedDate) {
    MimeMessage message = mock(MimeMessage.class);
    when(message.getMessageID()).thenReturn(messageId);
    // lenient: the service also probes getHeader for headers this message does not
    // carry (delivery headers, the absent ones of the three below), and strict
    // stubbing would report those probes as argument mismatches.
    if (references != null) {
      lenient().when(message.getHeader("References")).thenReturn(new String[] { references });
    }
    if (inReplyTo != null) {
      lenient().when(message.getHeader("In-Reply-To")).thenReturn(new String[] { inReplyTo });
    }
    if (threadIndex != null) {
      lenient().when(message.getHeader("Thread-Index")).thenReturn(new String[] { threadIndex });
    }
    when(message.getReceivedDate()).thenReturn(receivedDate);
    return message;
  }

  /**
   * A synthetic MS-OXOMSG {@code Thread-Index} whose 16-byte conversation GUID
   * (bytes 6..21) is a fixed constant, so indexes of different lengths -- the
   * conversation starter at 22 bytes, each reply 5 bytes longer -- decode to the
   * same conversation root.
   *
   * @param length the raw byte length before base64 encoding, at least 22
   * @return the base64-encoded header value
   */
  private String threadIndex(int length) {
    byte[] raw = new byte[length];
    for (int i = 6; i < 22; i++) {
      raw[i] = (byte) 0x42;
    }
    return Base64.getEncoder().encodeToString(raw);
  }

  /**
   * Wires the mocks for a {@code synchronize()} run over an inbox containing exactly
   * the given messages, with UIDs {@code 1..n} in array order (nothing cached
   * locally, no Sent/Archive folders on the store). Unlike {@link #mockInboxForSync}
   * the caller controls each message's headers, which is what the threading
   * order-independence tests manipulate.
   *
   * @param userEmailSetting the user's connector binding
   * @param messages the inbox content, oldest first (UID = index + 1)
   * @return the mocked INBOX folder
   */
  @SneakyThrows
  private Folder mockInboxWithMessages(UserEmailSetting userEmailSetting, MimeMessage[] messages) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    // Only the parallel-prefetch tests reach getFullName; lenient so the serial-path
    // tests sharing this fixture do not trip strict-stubbing.
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(messages.length);
    for (int i = 0; i < messages.length; i++) {
      when(((UIDFolder) inbox).getUID(messages[i])).thenReturn((long) (i + 1));
    }
    when(inbox.getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * Wires the extra IMAP connection the body prefetch workers open: same folder
   * re-opened by full name, messages resolved by UID, each carrying a
   * {@code prefetched-<uid>} body.
   *
   * @param emailConnector the resolved connector the workers connect with
   * @param count the number of messages, UIDs {@code 1..count}
   * @return the mocked worker-side folder
   */
  @SneakyThrows
  private Folder mockPrefetchWorkerConnection(EmailConnector emailConnector, int count) {
    return mockPrefetchWorkerConnection(emailConnector, count, uids -> null);
  }

  /**
   * Same as {@link #mockPrefetchWorkerConnection(EmailConnector, int)}, with a hook
   * invoked on the worker thread before a slice's messages are returned -- used to
   * make one slice deliberately slow.
   *
   * @param emailConnector the resolved connector the workers connect with
   * @param count the number of messages, UIDs {@code 1..count}
   * @param beforeSlice called with the slice's UIDs before they resolve; may block
   * @return the mocked worker-side folder
   */
  @SneakyThrows
  private Folder mockPrefetchWorkerConnection(EmailConnector emailConnector, int count, SliceHook beforeSlice) {
    Store workerStore = mock(Store.class);
    when(userEmailSettingService.connect(any(UserEmailSetting.class), eq(emailConnector))).thenReturn(workerStore);
    when(workerStore.isConnected()).thenReturn(true);
    Folder workerFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(workerStore.getFolder("INBOX")).thenReturn(workerFolder);
    when(workerFolder.isOpen()).thenReturn(true);
    Map<Long, Message> workerMessages = new HashMap<>();
    for (long uid = 1; uid <= count; uid++) {
      MimeMessage workerMessage = mock(MimeMessage.class);
      when(workerMessage.isMimeType("text/*")).thenReturn(true);
      when(workerMessage.getContent()).thenReturn("prefetched-" + uid);
      when(((UIDFolder) workerFolder).getUID(workerMessage)).thenReturn(uid);
      workerMessages.put(uid, workerMessage);
    }
    // Read-only answer (all stubbing done above) so concurrent workers never stub.
    when(((UIDFolder) workerFolder).getMessagesByUID(any(long[].class))).thenAnswer(invocation -> {
      long[] uids = invocation.getArgument(0);
      beforeSlice.beforeSlice(uids);
      Message[] result = new Message[uids.length];
      for (int i = 0; i < uids.length; i++) {
        result[i] = workerMessages.get(uids[i]);
      }
      return result;
    });
    return workerFolder;
  }

  /**
   * A hook run on a prefetch worker thread just before its slice resolves, allowed to
   * block or throw -- how the slow-slice test freezes exactly one slice.
   */
  @FunctionalInterface
  private interface SliceHook {
    /**
     * Invoked with the slice's UIDs on the worker thread.
     *
     * @param uids the slice's UIDs
     * @return ignored, present so blocking lambdas may end with a return
     * @throws Exception to fail the slice like a dead connection would
     */
    Object beforeSlice(long[] uids) throws Exception;
  }

  /**
   * Wires the mocks for a {@code synchronize()} run over an inbox of {@code count}
   * brand-new messages with UIDs {@code 1..count} (nothing cached locally, no
   * Sent/Archive folders on the store).
   */
  @SneakyThrows
  private Folder mockInboxForSync(UserEmailSetting userEmailSetting, int count) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    // Only the parallel-prefetch tests reach getFullName; lenient so the skip-path
    // tests sharing this fixture do not trip strict-stubbing.
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(count);
    MimeMessage[] messages = new MimeMessage[count];
    for (int i = 0; i < count; i++) {
      messages[i] = mock(MimeMessage.class);
      when(((UIDFolder) inbox).getUID(messages[i])).thenReturn((long) (i + 1));
    }
    when(inbox.getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * The IMAP criteria the search endpoint builds: null when nothing at all was
   * asked (an empty SEARCH would match the whole mailbox), a bare term for a
   * single criterion, an AND of all of them otherwise — with the free-text query
   * expanding to subject OR sender.
   */
  @Test
  void buildEmailSearchTerm() {
    // No criterion at all must yield null, not an empty AND.
    assertNull(EmailBoxService.buildEmailSearchTerm(null, " ", false, null));

    // Free text alone: subject OR sender, trimmed.
    SearchTerm queryTerm = EmailBoxService.buildEmailSearchTerm(" weekly report ", null, false, null);
    assertTrue(queryTerm instanceof OrTerm);
    SearchTerm[] alternatives = ((OrTerm) queryTerm).getTerms();
    assertEquals(2, alternatives.length);
    assertEquals("weekly report", ((SubjectTerm) alternatives[0]).getPattern());
    assertEquals("weekly report", ((FromStringTerm) alternatives[1]).getPattern());

    // Sender alone: a single FROM term, no needless AND wrapper.
    SearchTerm fromTerm = EmailBoxService.buildEmailSearchTerm(null, "alice@example.com", false, null);
    assertTrue(fromTerm instanceof FromStringTerm);
    assertEquals("alice@example.com", ((FromStringTerm) fromTerm).getPattern());

    // Unread alone: messages WITHOUT the \Seen flag.
    SearchTerm unreadTerm = EmailBoxService.buildEmailSearchTerm(null, null, true, null);
    assertTrue(unreadTerm instanceof FlagTerm);
    assertTrue(((FlagTerm) unreadTerm).getFlags().contains(Flags.Flag.SEEN));
    assertFalse(((FlagTerm) unreadTerm).getTestSet());

    // Date bound alone: received on or after the bound.
    Date since = new Date();
    SearchTerm sinceTerm = EmailBoxService.buildEmailSearchTerm(null, null, false, since);
    assertTrue(sinceTerm instanceof ReceivedDateTerm);
    assertEquals(ComparisonTerm.GE, ((ReceivedDateTerm) sinceTerm).getComparison());

    // Everything at once: the four criteria ANDed together.
    SearchTerm combined = EmailBoxService.buildEmailSearchTerm("weekly", "alice@", true, since);
    assertTrue(combined instanceof AndTerm);
    assertEquals(4, ((AndTerm) combined).getTerms().length);

    // Recipient alone: To OR Cc, the SENT-folder way to pin a person (every
    // sender in there is the user). Bcc stays out on purpose: a recipient the
    // user deliberately hid must not surface as visible correspondence.
    SearchTerm toTerm = EmailBoxService.buildEmailSearchTerm(null, null, " alice@example.com ", false, false, null);
    assertTrue(toTerm instanceof OrTerm);
    SearchTerm[] recipients = ((OrTerm) toTerm).getTerms();
    assertEquals(2, recipients.length);
    assertEquals(Message.RecipientType.TO, ((RecipientStringTerm) recipients[0]).getRecipientType());
    assertEquals("alice@example.com", ((RecipientStringTerm) recipients[0]).getPattern());
    assertEquals(Message.RecipientType.CC, ((RecipientStringTerm) recipients[1]).getRecipientType());
    assertEquals("alice@example.com", ((RecipientStringTerm) recipients[1]).getPattern());

    // And it joins the AND as one more criterion, like the others.
    SearchTerm withTo = EmailBoxService.buildEmailSearchTerm("weekly", "alice@", "bob@", true, false, since);
    assertTrue(withTo instanceof AndTerm);
    assertEquals(5, ((AndTerm) withTo).getTerms().length);
  }

  /**
   * The search happy path: ACL and validation first, then one IMAP SEARCH, ONE
   * batched fetch for the whole hit page (the per-message round-trip regression
   * this verification exists to catch), one bulk cached-flag lookup, and hits
   * returned newest first with the folder's read/cached flags filled in.
   */
  @Test
  @SneakyThrows
  void searchEmails() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "INBOX", 20));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    // Unknown folders and criterion-less searches are refused before any IMAP work.
    assertThrows(IllegalArgumentException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "TRASH", 20));
    assertThrows(IllegalArgumentException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, " ", null, false, null, "INBOX", 20));

    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Date now = new Date();
    // The server returns matches oldest first; UID 12 is the one already cached.
    MimeMessage oldest = searchHit("weekly report 1", "alice@example.com", new Date(now.getTime() - 2000), true);
    MimeMessage middle = searchHit("weekly report 2", "bob@example.com", new Date(now.getTime() - 1000), false);
    MimeMessage newest = searchHit("weekly report 3", null, now, true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[] { oldest, middle, newest });
    when(((UIDFolder) inbox).getUID(oldest)).thenReturn(11L);
    when(((UIDFolder) inbox).getUID(middle)).thenReturn(12L);
    when(((UIDFolder) inbox).getUID(newest)).thenReturn(13L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(11L, 12L, 13L))).thenReturn(List.of(12L));

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "INBOX", 20);

    assertEquals(3, page.getTotalMatches());
    assertEquals(List.of(13L, 12L, 11L),
                 page.getResults().stream().map(EmailSearchResult::getMailRemoteId).collect(Collectors.toList()));
    EmailSearchResult first = page.getResults().get(0);
    assertEquals("weekly report 3", first.getSubject());
    assertNull(first.getSender());
    assertTrue(first.isRead());
    assertFalse(first.isCached());
    EmailSearchResult second = page.getResults().get(1);
    assertFalse(second.isRead());
    assertTrue(second.isCached());
    assertEquals("bob@example.com", second.getSender().getAddress());
    // ONE batched fetch for the whole page, never one FETCH per hit.
    verify(inbox, times(1)).fetch(any(Message[].class), any(FetchProfile.class));
    verify(inbox).open(Folder.READ_ONLY);
    // The search connection is its own and short-lived: closed before returning.
    verify(inbox).close(false);
    verify(store).close();
  }

  /**
   * The recipient criterion travels through to the IMAP SEARCH: {@code to} alone
   * is a sufficient criterion (the contact card's correspondence sends nothing
   * else), and what reaches the server is the To-or-Cc term — never a sender
   * term, which in the SENT folder would match everything or nothing.
   */
  @Test
  @SneakyThrows
  void searchEmailsCanPinARecipient() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    MimeMessage hit = searchHit("about the budget", "me@example.com", new Date(), true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[] { hit });
    when(((UIDFolder) inbox).getUID(hit)).thenReturn(21L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(21L))).thenReturn(List.of());

    EmailSearchResultPage page =
                               emailBoxService.searchEmails(TEST_USER, null, null, "alice@example.com", false, false, null, "INBOX", 10);

    assertEquals(1, page.getTotalMatches());
    ArgumentCaptor<SearchTerm> searched = ArgumentCaptor.forClass(SearchTerm.class);
    verify(inbox).search(searched.capture());
    assertTrue(searched.getValue() instanceof OrTerm);
    SearchTerm[] recipientTerms = ((OrTerm) searched.getValue()).getTerms();
    assertEquals(Message.RecipientType.TO, ((RecipientStringTerm) recipientTerms[0]).getRecipientType());
    assertEquals(Message.RecipientType.CC, ((RecipientStringTerm) recipientTerms[1]).getRecipientType());
  }

  /**
   * The unified-search connector reads the local mirror, so the match has to be made
   * over the subject, the sender AND the body — and the body is stored as HTML, so it
   * is reduced to text first: without that, searching "div" or "style" would hit half
   * the mailbox on markup nobody sees.
   */
  @Test
  @SneakyThrows
  void searchCachedEmailsWithoutAnExcerpt() {
    // The two cases buildExcerpt answers with null: no body at all, and a body whose
    // markup reduces to nothing. The row still comes back, just without a quote under
    // the subject.
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    Email noBody = email(TEST_USER);
    noBody.setSubject("budget, no body");
    noBody.setContent(new EmailContent(null));
    Email markupOnly = email(TEST_USER);
    markupOnly.setSubject("budget, markup only");
    markupOnly.setContent(new EmailContent("<div><br></div>"));
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(List.of(noBody, markupOnly));

    EmailSearchResultPage page = emailBoxService.searchCachedEmails(TEST_USER, "budget", false, 10);

    assertEquals(2, page.getTotalMatches());
    assertTrue(page.getResults().stream().allMatch(result -> result.getExcerpt() == null));
  }

  @Test
  @SneakyThrows
  void searchCachedEmailsMatchesSubjectSenderAndBodyText() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    Email bySubject = email(TEST_USER);
    bySubject.setSubject("Quarterly budget");
    Email bySender = email(TEST_USER);
    bySender.setSubject("Nothing to see");
    bySender.setSender(new EmailSender("Budget Team", "budget@example.com", null, null));
    Email byBody = email(TEST_USER);
    byBody.setSubject("Nothing to see either");
    byBody.setContent(new EmailContent("<p>the <b>budget</b> is attached</p>"));
    Email markupOnly = email(TEST_USER);
    markupOnly.setSubject("Unrelated");
    markupOnly.setContent(new EmailContent("<div style=\"budget\">unrelated</div>"));
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(List.of(bySubject, bySender, byBody, markupOnly));

    EmailSearchResultPage page = emailBoxService.searchCachedEmails(TEST_USER, "budget", 10);

    assertEquals(3, page.getTotalMatches());
    // The one that only matched inside an HTML attribute must not be there.
    assertTrue(page.getResults().stream().noneMatch(result -> "Unrelated".equals(result.getSubject())));
  }

  /**
   * The unified search's Favorites filter narrows the section to the messages the
   * user favorited — the mail server's own flag, so the filter agrees with what they
   * see in their webmail.
   */
  @Test
  @SneakyThrows
  void searchCachedEmailsCanBeNarrowedToFavorites() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    Email favorited = email(TEST_USER);
    favorited.setSubject("budget, kept");
    favorited.setStarred(true);
    Email plain = email(TEST_USER);
    plain.setSubject("budget, not kept");
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(List.of(favorited, plain));

    EmailSearchResultPage narrowed = emailBoxService.searchCachedEmails(TEST_USER, "budget", true, 10);

    assertEquals(1, narrowed.getTotalMatches());
    assertEquals("budget, kept", narrowed.getResults().get(0).getSubject());
    // It says so, so the results can continue the same narrowed search on the server.
    assertTrue(narrowed.isFavoritesOnly());

    // Without the filter, both come back.
    assertEquals(2, emailBoxService.searchCachedEmails(TEST_USER, "budget", 10).getTotalMatches());
  }

  /**
   * A cached hit quotes the message around the searched words, so the reader can see
   * why it came back — and quotes its opening words when the match was in the subject
   * or the sender instead.
   */
  @Test
  @SneakyThrows
  void searchCachedEmailsQuotesTheMessageAroundTheMatch() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    Email matchedInBody = email(TEST_USER);
    matchedInBody.setSubject("Nothing to see");
    matchedInBody.setContent(new EmailContent("<p>Ahead of Friday, the <b>budget</b> figures are attached for review.</p>"));
    Email matchedInSubject = email(TEST_USER);
    matchedInSubject.setSubject("Quarterly budget");
    matchedInSubject.setContent(new EmailContent("<p>Opening words of a message that never says the word.</p>"));
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(List.of(matchedInBody, matchedInSubject));

    EmailSearchResultPage page = emailBoxService.searchCachedEmails(TEST_USER, "budget", 10);

    String bodyExcerpt = page.getResults()
                             .stream()
                             .filter(result -> "Nothing to see".equals(result.getSubject()))
                             .findFirst()
                             .orElseThrow()
                             .getExcerpt();
    // Around the match, and as text: the markup around it must not be quoted.
    assertTrue(bodyExcerpt.contains("budget"));
    assertTrue(bodyExcerpt.contains("figures are attached"));
    assertFalse(bodyExcerpt.contains("<b>"));

    String subjectExcerpt = page.getResults()
                                .stream()
                                .filter(result -> "Quarterly budget".equals(result.getSubject()))
                                .findFirst()
                                .orElseThrow()
                                .getExcerpt();
    // Nothing matched in the body, so it opens the message instead of quoting nothing.
    assertTrue(subjectExcerpt.startsWith("Opening words"));
  }

  /**
   * The connector asks for a handful of rows, but the section has to be able to say
   * how much it did not show, so the total counts every match.
   */
  @Test
  @SneakyThrows
  void searchCachedEmailsCapsTheRowsButCountsEveryMatch() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    List<Email> emails = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      Email email = email(TEST_USER);
      email.setSubject("budget " + i);
      email.setReceivedDate(new Date(System.currentTimeMillis() - i * 1000L));
      emails.add(email);
    }
    when(emailBoxStorage.getEmailsForSearch(TEST_USER)).thenReturn(emails);

    EmailSearchResultPage page = emailBoxService.searchCachedEmails(TEST_USER, "budget", 3);

    assertEquals(3, page.getResults().size());
    assertEquals(7, page.getTotalMatches());
    // Newest first, so the section leads with what the user most likely means.
    assertEquals("budget 0", page.getResults().get(0).getSubject());
  }

  /**
   * An empty search would return the whole mailbox, which is never what the search
   * bar means.
   */
  @Test
  @SneakyThrows
  void searchCachedEmailsRefusesAnEmptyTerm() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> emailBoxService.searchCachedEmails(TEST_USER, "  ", 10));
  }

  /**
   * A hit reports whether the user favorited it, so a mail favorited long ago shows
   * its star in the search list even though it is far outside the cached window.
   * The flag costs nothing extra: it is read from the FLAGS the page already fetches
   * for the read state, so the batched fetch stays a single call.
   */
  @Test
  @SneakyThrows
  void searchEmailsReportsTheFavoriteFlagOnEveryHit() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(1L, TEST_USER)).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Date now = new Date();
    MimeMessage plain = searchHit("weekly report 1", "alice@example.com", new Date(now.getTime() - 1000), true);
    MimeMessage favorited = searchHit("weekly report 2", "bob@example.com", now, true);
    when(favorited.isSet(Flags.Flag.FLAGGED)).thenReturn(true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[] { plain, favorited });
    when(((UIDFolder) inbox).getUID(plain)).thenReturn(11L);
    when(((UIDFolder) inbox).getUID(favorited)).thenReturn(12L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(11L, 12L))).thenReturn(List.of());

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "INBOX", 20);

    // Newest first: the favorited one leads, and neither is cached — the star must
    // not depend on the local copy.
    EmailSearchResult first = page.getResults().get(0);
    assertTrue(first.isStarred());
    assertFalse(first.isCached());
    assertFalse(page.getResults().get(1).isStarred());
    // ONE batched fetch, and it must ask for FLAGS: without that item in the profile
    // every isSet(FLAGGED) would turn into its own FETCH against a real server, which
    // is exactly what "the flag rides along free" claims does not happen.
    ArgumentCaptor<FetchProfile> profile = ArgumentCaptor.forClass(FetchProfile.class);
    verify(inbox, times(1)).fetch(any(Message[].class), profile.capture());
    assertTrue(profile.getValue().contains(FetchProfile.Item.FLAGS));
  }

  /**
   * When the SEARCH matches more than the requested limit, only the newest tail of
   * the match list is fetched — the batched FETCH must stay bounded by the limit,
   * not by the match count — while the total still reports every match.
   */
  @Test
  @SneakyThrows
  void searchEmailsCapsThePageToTheNewestHits() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage[] matches = new MimeMessage[5];
    for (int i = 0; i < matches.length; i++) {
      matches[i] = searchHit("hit " + i, "alice@example.com", new Date(), false);
    }
    when(inbox.search(any(SearchTerm.class))).thenReturn(matches);
    // Only the two newest ever get their UID read: the older three are never fetched.
    when(((UIDFolder) inbox).getUID(matches[3])).thenReturn(14L);
    when(((UIDFolder) inbox).getUID(matches[4])).thenReturn(15L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(14L, 15L))).thenReturn(List.of());

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, null, "alice", false, 30, "INBOX", 2);

    assertEquals(5, page.getTotalMatches());
    assertEquals(List.of(15L, 14L),
                 page.getResults().stream().map(EmailSearchResult::getMailRemoteId).collect(Collectors.toList()));
    ArgumentCaptor<Message[]> fetchedPage = ArgumentCaptor.forClass(Message[].class);
    verify(inbox, times(1)).fetch(fetchedPage.capture(), any(FetchProfile.class));
    assertArrayEquals(new Message[] { matches[3], matches[4] }, fetchedPage.getValue());
  }

  /**
   * A search with no match costs nothing beyond the SEARCH itself: no batched
   * fetch, no cached-flag query, an empty page with a zero total.
   */
  @Test
  @SneakyThrows
  void searchEmailsReturnsEmptyPageWithoutFetching() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[0]);

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "nothing", null, false, null, "INBOX", 20);

    assertEquals(0, page.getTotalMatches());
    assertTrue(page.getResults().isEmpty());
    verify(inbox, never()).fetch(any(Message[].class), any(FetchProfile.class));
    verify(emailBoxStorage, never()).getCachedMailRemoteIds(anyString(), anyString(), anyList());
    verify(inbox).close(false);
    verify(store).close();
  }

  /**
   * ARCHIVE must be searched in the folder that OWNS the ARCHIVE UID keyspace — the
   * one the sync caches from — not in the archive destination. On a mailbox carrying
   * both a dedicated {@code \Archive} and an All-Mail-type folder the two differ, and
   * because IMAP UIDs are per-folder the mismatch would make the cached flag, the
   * fetch pre-check and the eventual sync cleanup all compare UIDs across folders.
   * All Mail is listed FIRST here, so the destination lookup would have picked it.
   */
  @Test
  @SneakyThrows
  void searchEmailsResolvesArchiveTheWayTheSyncDoes() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder allMail = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    lenient().when(allMail.exists()).thenReturn(true);
    lenient().when(allMail.getAttributes()).thenReturn(new String[] { "\\All" });
    lenient().when(allMail.getFullName()).thenReturn("[Gmail]/All Mail");
    IMAPFolder archive = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(archive.exists()).thenReturn(true);
    when(archive.getAttributes()).thenReturn(new String[] { "\\Archive" });
    lenient().when(archive.getFullName()).thenReturn("Archive");
    when(archive.isOpen()).thenReturn(true);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { allMail, archive });
    when(archive.search(any(SearchTerm.class))).thenReturn(new Message[0]);

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "report", null, false, null, "ARCHIVE", 20);

    assertEquals(0, page.getTotalMatches());
    verify(archive).open(Folder.READ_ONLY);
    verify(archive).search(any(SearchTerm.class));
    // The All-Mail superset owns a DIFFERENT UID space: it must never be searched
    // while a syncable Archive exists.
    verify(allMail, never()).open(anyInt());
    verify(allMail, never()).search(any(SearchTerm.class));
  }

  /**
   * The Gmail shape: no {@code \Archive} to sync, archived mail living in the
   * {@code \All} superset. Reach must be unchanged — the superset is still searched —
   * and the ARCHIVE keyspace is then exclusively search-fed, so it stays consistent.
   */
  @Test
  @SneakyThrows
  void searchEmailsFallsBackToTheAllMailSupersetWithoutASyncableArchive() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder allMail = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(allMail.exists()).thenReturn(true);
    when(allMail.getAttributes()).thenReturn(new String[] { "\\All" });
    lenient().when(allMail.getFullName()).thenReturn("[Gmail]/All Mail");
    when(allMail.isOpen()).thenReturn(true);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { allMail });
    when(allMail.search(any(SearchTerm.class))).thenReturn(new Message[0]);

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "report", null, false, null, "ARCHIVE", 20);

    assertEquals(0, page.getTotalMatches());
    verify(allMail).open(Folder.READ_ONLY);
  }

  /**
   * A mailbox with no Sent folder yet: the null-folder path returns an empty page
   * without opening or searching anything.
   */
  @Test
  @SneakyThrows
  void searchEmailsReturnsEmptyPageWhenTheFolderDoesNotExist() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "anything", null, false, null, "SENT", 20);

    assertEquals(0, page.getTotalMatches());
    assertTrue(page.getResults().isEmpty());
    verify(store).close();
  }

  /**
   * A negative day window is a future-dated lower bound that silently matches
   * nothing; it is rejected as a bad request rather than returning a confusing
   * empty page.
   */
  @Test
  @SneakyThrows
  void searchEmailsRejectsANegativeDayWindow() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);

    IllegalArgumentException thrown =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> emailBoxService.searchEmails(TEST_USER,
                                                                                    "weekly",
                                                                                    null,
                                                                                    false,
                                                                                    -3,
                                                                                    "INBOX",
                                                                                    20));
    assertEquals("emailConnector.search.invalidSinceDays", thrown.getMessage());
    // Refused before any connection is opened.
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  /**
   * A server refusing the search CRITERIA (the charset path, which an accented query
   * takes) is a rejected input, not a broken mailbox: it owes the caller a 400 with a
   * message code, never the generic 500 the catch-all would produce.
   */
  @Test
  @SneakyThrows
  void searchEmailsMapsRefusedCriteriaToABadRequest() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(inbox.search(any(SearchTerm.class))).thenThrow(new SearchException("Search failed"));

    IllegalArgumentException thrown =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> emailBoxService.searchEmails(TEST_USER,
                                                                                    "réunion",
                                                                                    null,
                                                                                    false,
                                                                                    null,
                                                                                    "INBOX",
                                                                                    20));
    assertEquals("emailConnector.search.criteriaNotSupported", thrown.getMessage());
    // Still closed cleanly on the refusal path.
    verify(inbox).close(false);
    verify(store).close();
  }

  /**
   * Opening a search hit that is already in the local cache must not touch the
   * server at all — the row comes straight from the database.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailReturnsCachedRowWithoutImap() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212L, TEST_USER, "testEmail", "INBOX", true, true, true))
                                                                                                                  .thenReturn(email);

    assertSame(email, emailBoxService.fetchSearchedEmail(1212L, "INBOX", TEST_USER));

    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  /**
   * The not-held-locally path: a hit outside the cache window is fetched from the
   * server in one batched call and cached through the ordinary createEmails
   * pipeline, then served from the database; the sync mutex is released afterwards
   * (the second call must not be refused as sync-in-progress).
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailCachesAnOutOfWindowMessage() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cachedAfterCreate = email(TEST_USER);
    // Miss on the pre-check, miss again on the under-mutex re-check, hit once created.
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(9999L, TEST_USER, "testEmail", "INBOX", true, true, true))
                                                                                                                  .thenReturn(null,
                                                                                                                              null,
                                                                                                                              cachedAfterCreate);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage message = mock(MimeMessage.class);
    when(((UIDFolder) inbox).getMessageByUID(9999L)).thenReturn(message);
    when(((UIDFolder) inbox).getUID(message)).thenReturn(9999L);

    Email fetched = emailBoxService.fetchSearchedEmail(9999L, "INBOX", TEST_USER);

    assertSame(cachedAfterCreate, fetched);
    // The single message is prefetched in one batched call before createEmails
    // reads its headers -- never one round-trip per header.
    verify(inbox, times(1)).fetch(any(Message[].class), any(FetchProfile.class));
    verify(emailBoxStorage).createEmail(any(Email.class));
    verify(inbox).open(Folder.READ_ONLY);
    verify(inbox).close(false);
    verify(store).close();
    // The mutex is released: this second call short-circuits on the cache.
    assertSame(cachedAfterCreate, emailBoxService.fetchSearchedEmail(9999L, "INBOX", TEST_USER));
    verify(userEmailSettingService, times(1)).connect(userEmailSetting);
    // INBOX is bulk-synced, so the documented self-restoring eviction applies and the
    // search-fed trim must keep its hands off it.
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  /**
   * On a provider with no syncable {@code \Archive} — Gmail, where archived mail
   * lives only in the unsynced {@code \All} superset — no bulk sync ever visits
   * ARCHIVE, so {@code cleanupObsoleteEmails} never runs for it and search-fetched
   * rows would pile up for good in a folder whose list and counts the user sees.
   * The overflow is trimmed here instead, and the row just fetched is exempt: it is
   * what the caller is about to read, and being old it sorts last.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailTrimsTheSearchFedArchiveCache() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cachedAfterCreate = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(7000L, TEST_USER, "testEmail", "ARCHIVE", true, true, true))
                                                                                                                    .thenReturn(null,
                                                                                                                                null,
                                                                                                                                cachedAfterCreate);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    // Gmail shape: an All-Mail superset and NO syncable \Archive.
    IMAPFolder allMail = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(allMail.exists()).thenReturn(true);
    when(allMail.getAttributes()).thenReturn(new String[] { "\\All" });
    lenient().when(allMail.getFullName()).thenReturn("[Gmail]/All Mail");
    when(allMail.isOpen()).thenReturn(true);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { allMail });
    MimeMessage message = mock(MimeMessage.class);
    when(((UIDFolder) allMail).getMessageByUID(7000L)).thenReturn(message);
    lenient().when(((UIDFolder) allMail).getUID(message)).thenReturn(7000L);
    // A 100-message window, so 103 cached rows means three of overflow — one of
    // which is the row just fetched.
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    List<Email> cachedRows = new ArrayList<>();
    for (int i = 0; i < 103; i++) {
      Email row = new Email();
      row.setId((long) i);
      row.setMailRemoteId(i == 101 ? 7000L : (long) i);
      cachedRows.add(row);
    }
    when(emailBoxStorage.getSyncEmails(TEST_USER, "ARCHIVE")).thenReturn(cachedRows);

    Email fetched = emailBoxService.fetchSearchedEmail(7000L, "ARCHIVE", TEST_USER);

    assertSame(cachedAfterCreate, fetched);
    ArgumentCaptor<List<Long>> trimmed = ArgumentCaptor.forClass(List.class);
    verify(emailBoxStorage).deleteEmailsByIds(trimmed.capture());
    // Rows 100 and 102 go; row 101 is the one just fetched and stays.
    assertEquals(List.of(100L, 102L), trimmed.getValue());
  }

  /**
   * A hit deleted from the server between the search and the open: null, and
   * nothing is written to the cache.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailReturnsNullWhenGoneFromServer() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    when(((UIDFolder) inbox).getMessageByUID(4242L)).thenReturn(null);

    assertNull(emailBoxService.fetchSearchedEmail(4242L, "INBOX", TEST_USER));

    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  /**
   * While a synchronization holds the {@code syncingUsers} mutex, the on-demand
   * fetch must refuse with the retryable {@code syncInProgress} code instead of
   * racing the sync's known-UIDs snapshot into duplicate (user, folder, UID) rows.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailRefusedWhileASyncIsRunning() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    CountDownLatch syncStarted = new CountDownLatch(1);
    CountDownLatch releaseSync = new CountDownLatch(1);
    // The sync thread parks inside connect() -- i.e. AFTER it acquired the mutex --
    // until the assertion below has run, then aborts (the sync outcome is irrelevant).
    when(userEmailSettingService.connect(userEmailSetting)).thenAnswer(invocation -> {
      syncStarted.countDown();
      releaseSync.await(10, TimeUnit.SECONDS);
      throw new MessagingException("test: abort the sync");
    });
    Thread sync = new Thread(() -> {
      try {
        emailBoxService.synchronize(TEST_USER);
      } catch (Exception e) {
        // The aborted connect is the expected way out.
      }
    });
    sync.start();
    try {
      assertTrue(syncStarted.await(10, TimeUnit.SECONDS));
      IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                   () -> emailBoxService.fetchSearchedEmail(4242L, "INBOX", TEST_USER));
      assertEquals("emailConnector.search.syncInProgress", refusal.getMessage());
    } finally {
      releaseSync.countDown();
      sync.join(10000);
    }
  }

  /**
   * A stubbed search hit carrying just what a result row renders: subject, sender,
   * received date and read flag. Lenient because the capped-page test deliberately
   * never reads the hits that fall outside the returned tail.
   *
   * @param subject the message subject
   * @param fromAddress the sender address, null for a sender-less message
   * @param receivedDate the received date
   * @param seen whether the message carries the \Seen flag
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage searchHit(String subject, String fromAddress, Date receivedDate, boolean seen) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.getSubject()).thenReturn(subject);
    lenient().when(message.getFrom())
             .thenReturn(fromAddress == null ? null : new Address[] { new InternetAddress(fromAddress) });
    lenient().when(message.getReceivedDate()).thenReturn(receivedDate);
    lenient().when(message.isSet(Flags.Flag.SEEN)).thenReturn(seen);
    return message;
  }

  @Test
  void saveDraftRefusesAUserWhoMayNotUseTheirMailbox() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    Email draft = draft(null);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.saveDraft(draft, TEST_USER, false));
  }

  @Test
  void saveDraftWithoutPushNeverOpensAConnection() throws Exception {
    // The typing-pause save is the one that runs constantly, and it must cost nothing
    // but a row: no IMAP connection, no folder discovery, no APPEND.
    givenAUsableMailbox();
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, false);
    assertEquals(MailFolder.DRAFTS, saved.getFolder());
    assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void firstSaveMintsALocalIdAndAMessageIdOfOurOwn() throws Exception {
    givenAUsableMailbox();
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, false);
    assertTrue(StringUtils.isNotBlank(saved.getDraftLocalId()));
    // Our own id, on the user's own domain -- and emphatically NOT the
    // @email-connector.local form synthesizeMessageId produces, which is a local
    // placeholder that must never leave this box.
    assertTrue(saved.getMailHeaderId().startsWith("<"));
    assertTrue(saved.getMailHeaderId().endsWith(">"));
    assertFalse(saved.getMailHeaderId().contains("email-connector.local"));
    assertEquals(1L, saved.getDraftRevision());
    assertTrue(saved.isRead());
    assertFalse(saved.isRecent());
  }

  /**
   * A draft carrying a file goes up to the Drafts folder AS A MULTIPART, with the file
   * on it — and the message survives being written more than once.
   * <p>
   * That second half is the assertion that matters, and it is not hypothetical.
   * {@code IMAPFolder}'s append writes the message once to measure the IMAP literal
   * and again to transmit anything bigger than its buffer, and JavaMail reads the part
   * a further time while choosing a transfer encoding. A part backed by a stream that
   * has already been consumed contributes nothing on the later passes, which is how an
   * attachment lands on the server present, correctly named and zero bytes long — the
   * exact failure this feature exists to avoid, in its most deniable form. So the
   * message is written twice here and the two results are compared byte for byte, and
   * the file store is asserted to have been asked for the file more than once, which
   * is what "the source re-opens" actually means.
   * <p>
   * The stub hands back a FRESH {@code FileItem} on every call, as the real file
   * service does — a stub returning one instance would let a data source that captured
   * a stream once pass anyway.
   *
   * @throws Exception when the mocked mail plumbing misbehaves
   */
  @Test
  void aDraftCarryingAFileIsAppendedAsAMultipartThatCanBeWrittenTwice() throws Exception {
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4242L) });
    givenAStoredDraftCarrying(attachmentRow(), "the bytes of a report".getBytes());
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));

    emailBoxService.saveDraft(draft("draft-1"), TEST_USER, true);

    ArgumentCaptor<Message[]> appended = ArgumentCaptor.forClass(Message[].class);
    verify(draftsFolder).appendUIDMessages(appended.capture());
    Message message = appended.getValue()[0];
    assertTrue(message.getContent() instanceof Multipart, "a draft with a file is a multipart/mixed, not a bare body");
    Multipart multipart = (Multipart) message.getContent();
    assertEquals(2, multipart.getCount(), "the body, then one part per file");
    BodyPart filePart = multipart.getBodyPart(1);
    assertEquals("report.pdf", filePart.getFileName());
    assertEquals(Part.ATTACHMENT, filePart.getDisposition());

    ByteArrayOutputStream first = new ByteArrayOutputStream();
    ByteArrayOutputStream second = new ByteArrayOutputStream();
    message.writeTo(first);
    message.writeTo(second);
    assertArrayEquals(first.toByteArray(),
                      second.toByteArray(),
                      "a one-shot stream would make the second write drop the file's bytes");
    // Read off the WRITTEN message rather than off the part's data handler: what
    // matters is what went on the wire, and an empty part named after a file is
    // indistinguishable from a full one until you look there.
    String written = new String(first.toByteArray());
    String bytes = "the bytes of a report";
    assertTrue(written.contains(bytes) || written.contains(Base64.getEncoder().encodeToString(bytes.getBytes())),
               "the file's bytes really are in the message rather than an empty part named after it");
    verify(emailBoxStorage, atLeast(2)).getAttachmentFileItem(77L);
  }

  /**
   * A draft whose file has gone is not uploaded at all, and no connection is even
   * opened.
   * <p>
   * The invariant the whole feature is built around: an IMAP APPEND writes the ENTIRE
   * message, so a draft uploaded without one of its files puts a copy in the user's
   * Drafts folder that looks complete and is not — and a send from their phone sends
   * that version. The row comes back unchanged and unsynced, which is what makes the
   * composer say the draft lives only here. That is the truth, and it is said with
   * nothing new built.
   *
   * @throws Exception when the mocked mail plumbing misbehaves
   */
  @Test
  void aDraftWhoseFileHasGoneIsNotUploadedAtAll() throws Exception {
    givenAUsableMailbox();
    givenAStoredDraftCarrying(attachmentRow(), null);

    Email saved = emailBoxService.saveDraft(draft("draft-1"), TEST_USER, true);

    assertNotNull(saved);
    assertFalse(DraftState.SYNCED.equals(saved.getDraftState()), "a draft that cannot be assembled whole is not up there");
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void aDraftReplyJoinsTheConversationItRepliesTo() throws Exception {
    // The whole point of writing the threading headers at save time rather than at
    // send time: while the user is still typing, the draft already belongs to the
    // conversation they are answering.
    givenAUsableMailbox();
    when(emailBoxStorage.getMailReferencesByMailHeaderId("<parent@host>", TEST_USER)).thenReturn("<root@host>");
    when(emailBoxStorage.getSiblingThreadIds(eq(TEST_USER), anyList())).thenReturn(List.of("<root@host>"));
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email draft = draft(null);
    // On a FIRST save, mailHeaderId carries the PARENT's id -- the same meaning the
    // send payload gives it, so the composer needs no new concept.
    draft.setMailHeaderId("<parent@host>");
    Email saved = emailBoxService.saveDraft(draft, TEST_USER, false);
    assertEquals("<parent@host>", saved.getInReplyTo());
    assertEquals("<root@host> <parent@host>", saved.getMailReferences());
    assertEquals("<root@host>", saved.getThreadId());
    // and the draft's own id is its own, not the parent's
    assertFalse("<parent@host>".equals(saved.getMailHeaderId()));
  }

  @Test
  void aLaterSaveNeverRethreadsTheDraftAgainstItself() throws Exception {
    // A resumed draft carries its OWN minted id in mailHeaderId. Reading that as a
    // parent id would thread the draft against itself; the identity is settled at the
    // first save and owned here from then on.
    givenAUsableMailbox();
    Email stored = draft("draft-1");
    stored.setMailHeaderId("<mine@example.org>");
    stored.setThreadId("<root@host>");
    stored.setDraftState(DraftState.SYNCED);
    stored.setDraftRevision(3L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email incoming = draft("draft-1");
    incoming.setMailHeaderId("<mine@example.org>");
    incoming.setDraftRevision(4L);
    emailBoxService.saveDraft(incoming, TEST_USER, false);
    ArgumentCaptor<Email> written = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).saveDraft(written.capture());
    assertNull(written.getValue().getInReplyTo());
    assertNull(written.getValue().getThreadId());
    // the previously-synced copy is now stale, so the next push has one to replace
    assertEquals(DraftState.DIRTY, written.getValue().getDraftState());
    verify(emailBoxStorage, never()).getMailReferencesByMailHeaderId(anyString(), anyString());
  }

  /**
   * Every revision of a draft is stored declaring itself HTML, first save and later
   * ones alike.
   * <p>
   * It is the format the composer's rich editor produces and the format
   * {@code buildDraftMessage} uploads to the Drafts folder, so a row saying anything
   * else describes a message that does not exist. The composer sends a body and no
   * format, and the flag is a primitive that defaults to false, so this has to be
   * stamped rather than merely passed through — untouched, a draft went to the row
   * as plain text and to the server as text/html.
   *
   * @throws Exception if the mailbox stubbing fails
   */
  @Test
  void everyRevisionOfADraftIsStoredAsHtml() throws Exception {
    givenAUsableMailbox();
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));

    Email firstSave = emailBoxService.saveDraft(draft(null), TEST_USER, false);
    assertTrue(firstSave.getContent().isHtml(), "the first save of a draft says its body is HTML");

    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setDraftRevision(2L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    Email laterSave = emailBoxService.saveDraft(incoming, TEST_USER, false);
    assertTrue(laterSave.getContent().isHtml(), "and so does every save after it");
  }

  @Test
  void anEditedDraftThatWasNeverUploadedStaysLocalOnly() throws Exception {
    // LOCAL_ONLY is not merely "unsynced": it is what tells the upload path there is
    // no previous copy to remove.
    givenAUsableMailbox();
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setDraftRevision(2L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    emailBoxService.saveDraft(incoming, TEST_USER, false);
    ArgumentCaptor<Email> written = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).saveDraft(written.capture());
    assertEquals(DraftState.LOCAL_ONLY, written.getValue().getDraftState());
  }

  @Test
  void aPushSkipsTheAppendWhenNothingChangedSinceTheLastOne() throws Exception {
    // The interlock that makes "save on close" free for a draft nobody touched: the
    // storage layer drops the stale revision and hands back the SYNCED row, and a
    // SYNCED row is never re-appended. An APPEND re-uploads the entire message,
    // attachments included, so this is not a micro-optimisation.
    givenAUsableMailbox();
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.SYNCED);
    stored.setDraftRevision(5L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenReturn(stored);
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(5L);
    Email saved = emailBoxService.saveDraft(incoming, TEST_USER, true);
    assertEquals(DraftState.SYNCED, saved.getDraftState());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void aPushAppendsToTheDraftsFolderAndRecordsTheUidTheServerReports() throws Exception {
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4242L) });
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, true);
    verify(draftsFolder).open(Folder.READ_WRITE);
    assertEquals(4242L, saved.getMailRemoteId());
    assertEquals(DraftState.SYNCED, saved.getDraftState());
  }

  @Test
  void aPushFallsBackToAMessageIdSearchWhenTheServerHasNoUidplus() throws Exception {
    // Without UIDPLUS there is no way to ask what UID the message we just wrote got.
    // Minting our own Message-ID is what leaves us a handle on it at all.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { null });
    Message appended = mock(Message.class);
    when(draftsFolder.search(any(HeaderTerm.class))).thenReturn(new Message[] { appended });
    when(draftsFolder.getUID(appended)).thenReturn(77L);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, true);
    assertEquals(77L, saved.getMailRemoteId());
    assertEquals(DraftState.SYNCED, saved.getDraftState());
  }

  @Test
  void aMailboxWithNoDraftsFolderKeepsTheDraftLocalAndCreatesNothing() throws Exception {
    // Creating a folder in someone's mailbox is a permanent, visible change to a store
    // they share with every other client they own. It is not ours to make because they
    // typed two words into a compose window.
    givenAUsableMailbox();
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(any(UserEmailSetting.class))).thenReturn(store);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    when(defaultFolder.list("*")).thenReturn(new Folder[0]);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, true);
    assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
    assertNull(saved.getMailRemoteId());
    verify(defaultFolder, never()).create(anyInt());
  }

  @Test
  void theServerSideKillSwitchStopsTheUploadWithoutStoppingDrafts() throws Exception {
    givenAUsableMailbox();
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    System.setProperty(EmailBoxService.DRAFTS_SERVER_ENABLED_PROPERTY, "false");
    try {
      Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, true);
      // saved, listable, resumable -- just not uploaded
      assertEquals(MailFolder.DRAFTS, saved.getFolder());
      assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
      verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
    } finally {
      System.clearProperty(EmailBoxService.DRAFTS_SERVER_ENABLED_PROPERTY);
    }
  }

  @Test
  void aFailedUploadLeavesTheWordsSafelyStoredLocally() throws Exception {
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenThrow(new MessagingException("server said no"));
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, true);
    assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
    assertNull(saved.getMailRemoteId());
  }

  @Test
  void deleteDraftRemovesTheRowAndAnswersWhetherThereWasOne() throws Exception {
    givenAUsableMailbox();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "nope")).thenReturn(null);
    assertFalse(emailBoxService.deleteDraft("nope", TEST_USER));
    Email stored = draft("draft-1");
    stored.setId(9L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    assertTrue(emailBoxService.deleteDraft("draft-1", TEST_USER));
    verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
    // Never uploaded, so there is nothing up there to remove and no connection to make
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void aPushRemovesThePreviousCopyOnlyAfterTheNewOneIsUp() throws Exception {
    // Append before delete, always. If everything after the append fails the user sees
    // the same draft twice in another client; the other way round they see it nowhere.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4243L) });
    Message previous = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(previous);
    when(draftsFolder.isOpen()).thenReturn(true);
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.DIRTY);
    stored.setDraftRevision(2L);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> storedRowAfter(invocation.getArgument(0), 4242L));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    emailBoxService.saveDraft(incoming, TEST_USER, true);
    InOrder inOrder = inOrder(draftsFolder, previous);
    inOrder.verify(draftsFolder).appendUIDMessages(any(Message[].class));
    inOrder.verify(previous).setFlag(Flags.Flag.DELETED, true);
    // UID EXPUNGE: this message and no other, so the folder closes WITHOUT expunging
    verify(draftsFolder).expunge(new Message[] { previous });
    verify(draftsFolder).close(false);
  }

  @Test
  void aServerWithoutUidplusHasThePreviousCopyExpungedOnClose() throws Exception {
    // Without UIDPLUS the only expunge IMAP offers is the whole-folder one. See
    // removePreviousDraftCopy for why we still take it.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4243L) });
    Message previous = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(previous);
    when(draftsFolder.isOpen()).thenReturn(true);
    doThrow(new MessagingException("EXPUNGE not supported")).when(draftsFolder).expunge(any(Message[].class));
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.DIRTY);
    stored.setDraftRevision(2L);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> storedRowAfter(invocation.getArgument(0), 4242L));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    emailBoxService.saveDraft(incoming, TEST_USER, true);
    verify(previous).setFlag(Flags.Flag.DELETED, true);
    verify(draftsFolder).close(true);
  }

  @Test
  void aFirstPushHasNoPreviousCopyToRemove() throws Exception {
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4242L) });
    when(draftsFolder.isOpen()).thenReturn(true);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    emailBoxService.saveDraft(draft(null), TEST_USER, true);
    verify(draftsFolder, never()).getMessageByUID(anyLong());
    verify(draftsFolder).close(false);
  }

  @Test
  void theCopyOfADraftUploadedWhileItsAuthorKeptTypingIsRemovedOnTheNextPush() throws Exception {
    // The seam between the upload's bookkeeping and the removal it is supposed to
    // trigger. The APPEND lands, but the user typed while it was in flight, so the
    // revision guard rightly refuses to call the row SYNCED — and the row is left
    // LOCAL_ONLY carrying the UID of a copy that is really up there. Deciding from the
    // state that a LOCAL_ONLY row has nothing to remove meant that copy was never
    // deleted, and the next push added a second one: a duplicate draft in the user's
    // other mail client, which is the exact outcome this design exists to prevent.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4242L) },
                                                                         new AppendUID[] { new AppendUID(1L, 4243L) });
    Message firstCopy = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(firstCopy);
    // The first push: a draft that has never been uploaded, being typed into.
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setDraftRevision(2L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> storedRowAfter(invocation.getArgument(0),
                                                                                             stored.getMailRemoteId()));
    // What the storage layer really does when the row moved under the upload: the UID
    // is recorded, the state is NOT moved to SYNCED. Asserted in EmailBoxStorageTest;
    // reproduced here because it is the input this path was getting wrong.
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any())).thenAnswer(invocation -> {
      stored.setMailRemoteId(invocation.getArgument(2));
      stored.setDraftState(DraftState.LOCAL_ONLY);
      return stored;
    });
    Email firstPush = draft("draft-1");
    firstPush.setDraftRevision(3L);
    Email afterFirstPush = emailBoxService.saveDraft(firstPush, TEST_USER, true);
    assertEquals(DraftState.LOCAL_ONLY, afterFirstPush.getDraftState());
    assertEquals(4242L, afterFirstPush.getMailRemoteId());
    verify(draftsFolder, never()).getMessageByUID(anyLong());
    // The second push, carrying the sentence that was typed during the first.
    stored.setDraftRevision(4L);
    Email secondPush = draft("draft-1");
    secondPush.setDraftRevision(5L);
    secondPush.setContent(new EmailContent("the sentence typed during the upload", null, null));
    emailBoxService.saveDraft(secondPush, TEST_USER, true);
    // Exactly one removal, of the first copy and of nothing else, and only after the
    // second APPEND: no duplicate is left behind and none was risked.
    InOrder inOrder = inOrder(draftsFolder, firstCopy);
    inOrder.verify(draftsFolder, times(2)).appendUIDMessages(any(Message[].class));
    inOrder.verify(firstCopy).setFlag(Flags.Flag.DELETED, true);
    verify(draftsFolder).expunge(new Message[] { firstCopy });
    verify(draftsFolder, never()).getMessageByUID(4243L);
    // And the newer text is what went up, not the text the first push carried.
    ArgumentCaptor<Message[]> appended = ArgumentCaptor.forClass(Message[].class);
    verify(draftsFolder, times(2)).appendUIDMessages(appended.capture());
    assertEquals("the sentence typed during the upload", appended.getAllValues().get(1)[0].getContent());
  }

  @Test
  void aRememberedUidThatNowHoldsSomebodyElsesMessageIsLeftAlone() throws Exception {
    // A UID names a message only within one UIDVALIDITY of one folder, so a rebuilt or
    // restored mailbox hands the same numbers out to entirely different messages. The
    // sync notices that and clears the UIDs it holds, but it notices on its own
    // schedule and a push can run first. Message-ID equality is what settles it — the
    // same identity test the stray-copy janitor uses, and for the same reason.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4243L) });
    Message aStrangersMessage = serverDraftCopy("<somebody-elses-mail@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(aStrangersMessage);
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.DIRTY);
    stored.setDraftRevision(2L);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> storedRowAfter(invocation.getArgument(0), 4242L));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    emailBoxService.saveDraft(incoming, TEST_USER, true);
    // Nothing of somebody else's is flagged, and the folder closes without expunging.
    verify(aStrangersMessage, never()).setFlag(any(Flags.Flag.class), anyBoolean());
    verify(draftsFolder, never()).expunge(any(Message[].class));
    verify(draftsFolder).close(false);
  }

  @Test
  void aDraftWhoseServerCopyVanishedAppendsAFreshOneWithoutExpungingAnything() throws Exception {
    // The other half of the same rule, and where it meets the sync's detach path: that
    // path clears the UID precisely so a number that means nothing any more is never
    // acted on. A row with no UID has nothing to remove, whatever its state says.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    when(draftsFolder.appendUIDMessages(any(Message[].class))).thenReturn(new AppendUID[] { new AppendUID(1L, 4244L) });
    Email stored = draft("draft-1");
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setDraftRevision(2L);
    stored.setMailRemoteId(null);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> storedRowAfter(invocation.getArgument(0), null));
    when(emailBoxStorage.markDraftUploaded(eq(TEST_USER), anyString(), anyLong(), any()))
                                                                                        .thenAnswer(invocation -> uploaded(invocation.getArgument(2)));
    Email incoming = draft("draft-1");
    incoming.setDraftRevision(3L);
    Email saved = emailBoxService.saveDraft(incoming, TEST_USER, true);
    assertEquals(4244L, saved.getMailRemoteId());
    verify(draftsFolder, never()).getMessageByUID(anyLong());
    verify(draftsFolder).close(false);
  }

  @Test
  void discardingADraftUploadedMidTypingStillTakesItsServerCopy() throws Exception {
    // The discard's mirror of the push bug, and the worse of the two: reading the state
    // here left the copy of a draft the user had thrown away sitting in their Drafts
    // folder — where the next sync, finding a Drafts message with no row of its own,
    // would import it back as the draft they had just discarded.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    Message serverCopy = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(serverCopy);
    Email stored = draft("draft-1");
    stored.setId(9L);
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    assertTrue(emailBoxService.deleteDraft("draft-1", TEST_USER));
    verify(serverCopy).setFlag(Flags.Flag.DELETED, true);
    verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
  }

  @Test
  void sendingADraftUploadedMidTypingStillTakesItsServerCopy() throws Exception {
    // Same rule on the send path. Here the janitor would eventually tidy the leftover
    // (its Message-ID is in Sent), but relying on that means the user's other client
    // shows a draft of a mail they have already sent until the next sync runs.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    Message serverCopy = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(serverCopy);
    Email stored = storedDraft();
    stored.setDraftState(DraftState.LOCAL_ONLY);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      verify(serverCopy).setFlag(Flags.Flag.DELETED, true);
      verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
    }
  }

  @Test
  void discardingAnUploadedDraftTakesTheServerCopyFirst() throws Exception {
    // The mirror image of the upload's order: a draft the user threw away must not
    // reappear in their other mail client.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    Message serverCopy = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(serverCopy);
    when(draftsFolder.isOpen()).thenReturn(true);
    Email stored = draft("draft-1");
    stored.setId(9L);
    stored.setDraftState(DraftState.SYNCED);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    assertTrue(emailBoxService.deleteDraft("draft-1", TEST_USER));
    verify(serverCopy).setFlag(Flags.Flag.DELETED, true);
    verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
  }

  @Test
  void aDiscardThatCannotReachTheServerKeepsTheLocalRow() throws Exception {
    // Keeping the row is what stops the two copies from disagreeing about whether the
    // draft exists, and what lets the user simply try again.
    givenAUsableMailbox();
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    doThrow(new MessagingException("no")).when(draftsFolder).open(Folder.READ_WRITE);
    Email stored = draft("draft-1");
    stored.setId(9L);
    stored.setDraftState(DraftState.SYNCED);
    stored.setMailRemoteId(4242L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    assertThrows(IllegalStateException.class, () -> emailBoxService.deleteDraft("draft-1", TEST_USER));
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  @Test
  void aDraftIsStampedWithTheMomentItWasLastTyped() throws Exception {
    // Every save rewrites the row's date to now, and the date means "when the user
    // last typed". It no longer decides where the draft sits in its conversation --
    // that comes from In-Reply-To, because this stamp was moving a reply to Monday's
    // message below a mail that arrived tonight -- but it is still what the Drafts
    // listing, the conversation list and the cache trim read as recency, so a draft
    // being written must keep counting as the most recent thing in the mailbox.
    givenAUsableMailbox();
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> invocation.getArgument(0));
    Date before = new Date();
    Email saved = emailBoxService.saveDraft(draft(null), TEST_USER, false);
    assertNotNull(saved.getReceivedDate());
    assertNotNull(saved.getDraftUpdatedDate());
    assertFalse(saved.getReceivedDate().before(before));
    // A later revision moves it again: the date means "when the user last typed", not
    // "when this draft was started".
    Email stored = draft("draft-1");
    stored.setDraftRevision(1L);
    stored.setReceivedDate(new Date(0));
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    Email edited = emailBoxService.saveDraft(draft("draft-1"), TEST_USER, false);
    assertFalse(edited.getReceivedDate().before(before));
  }

  @Test
  void sendDraftRefusesAUserWhoMayNotUseTheirMailbox() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    Email draft = draft("draft-1");
    assertThrows(IllegalAccessException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
  }

  @Test
  void sendDraftRefusesAComposerThatHasNothingSavedYet() throws Exception {
    // A composer with no local id has no row to take apart; that send goes through
    // sendEmail, which owns the path that has nothing to clean up.
    givenAUsableMailbox();
    Email draft = draft(null);
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
  }

  @Test
  void sendingADraftThatIsNoLongerThereSaysSoRatherThanSendingSomethingElse() throws Exception {
    givenAUsableMailbox();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(null);
    Email draft = draft("draft-1");
    assertThrows(ObjectNotFoundException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
  }

  @Test
  void aSecondSendOfTheSameDraftIsRefusedWhileTheFirstIsStillInTheAir() throws Exception {
    // Double-sending a mail cannot be undone, so the claim on the row is checked
    // before anything reaches the SMTP server.
    givenAUsableMailbox();
    Email stored = storedDraft();
    stored.setDraftState(DraftState.SENDING);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      Email draft = draft("draft-1");
      assertThrows(IllegalArgumentException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
      transportMock.verifyNoInteractions();
    }
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  @Test
  void sendingADraftSavesItThenSendsItThenRemovesTheCopiesInThatOrder() throws Exception {
    // The whole point of the slice, expressed as an ordering. Save first, because
    // everything after it is destructive and the row is what the user gets back.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    IMAPFolder draftsFolder = givenADraftsFolder();
    when(draftsFolder.isOpen()).thenReturn(true);
    Message serverCopy = serverDraftCopy("<draft@example.org>");
    when(draftsFolder.getMessageByUID(4242L)).thenReturn(serverCopy);
    Email stored = storedDraft();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      InOrder inOrder = inOrder(emailBoxStorage, serverCopy);
      inOrder.verify(emailBoxStorage).saveDraft(any(Email.class));
      inOrder.verify(emailBoxStorage).updateDraftState(TEST_USER, "draft-1", DraftState.SENDING);
      transportMock.verify(() -> Transport.send(any(Message.class)));
      // The server copy goes before the local row, and only after the send returned.
      inOrder.verify(serverCopy).setFlag(Flags.Flag.DELETED, true);
      inOrder.verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
    }
  }

  @Test
  void aSentDraftGoesOutUnderTheMessageIdItWasSavedWith() throws Exception {
    // THE landmine. MimeMessage#saveChanges regenerates the Message-ID from scratch,
    // and Transport.send(msg) calls saveChanges as its very first statement -- so a
    // header stamped before the send is discarded and the mail leaves under an id
    // nobody recorded. That breaks the one thing the minted id exists for: the sent
    // copy being recognisable as the draft it grew out of, in the reader and in every
    // other mail client's threading.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    givenADraftsFolder();
    Email stored = storedDraft();
    stored.setDraftState(DraftState.LOCAL_ONLY);
    stored.setMailRemoteId(null);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      ArgumentCaptor<Message> sentCaptor = ArgumentCaptor.forClass(Message.class);
      transportMock.verify(() -> Transport.send(sentCaptor.capture()));
      MimeMessage sent = (MimeMessage) sentCaptor.getValue();
      // Transport is mocked here, so the call that would destroy the id never ran.
      // Run it by hand -- this line IS the test.
      sent.saveChanges();
      assertEquals("<draft@example.org>", sent.getHeader("Message-ID")[0]);
    }
  }

  @Test
  void aPlainMimeMessageWouldHaveLostThatMessageId() throws Exception {
    // The control for the test above: the same two calls on an ordinary MimeMessage,
    // proving the landmine is real and not a precaution against nothing.
    MimeMessage plain = new MimeMessage(Session.getInstance(new Properties()));
    plain.setText("body");
    plain.setHeader("Message-ID", "<draft@example.org>");
    plain.saveChanges();
    assertFalse("<draft@example.org>".equals(plain.getHeader("Message-ID")[0]));
    // And stamping it AFTER saveChanges -- which is enough for the APPEND, since
    // writeTo does not re-save an already-saved message -- does not survive the
    // second saveChanges that Transport.send performs.
    plain.setHeader("Message-ID", "<draft@example.org>");
    plain.saveChanges();
    assertFalse("<draft@example.org>".equals(plain.getHeader("Message-ID")[0]));
  }

  @Test
  void aSentDraftKeepsTheThreadingItWasGivenAtItsFirstSave() throws Exception {
    // Not re-derived from the composer's payload: the draft joined its conversation
    // when it was first saved, and the mail that goes out must land in that same
    // thread rather than in one computed a second time from different inputs.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    givenADraftsFolder();
    Email stored = storedDraft();
    stored.setInReplyTo("<parent@host>");
    stored.setMailReferences("<root@host> <parent@host>");
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      ArgumentCaptor<Message> sentCaptor = ArgumentCaptor.forClass(Message.class);
      transportMock.verify(() -> Transport.send(sentCaptor.capture()));
      assertEquals("<parent@host>", sentCaptor.getValue().getHeader("In-Reply-To")[0]);
      assertEquals("<root@host> <parent@host>", sentCaptor.getValue().getHeader("References")[0]);
    }
  }

  @Test
  void aRefusedSendLeavesTheDraftExactlyWhereItWas() throws Exception {
    // Nothing was removed before the send returned, so there is nothing to undo: the
    // claim comes back off and the draft is intact, here and on the server.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    // No mailbox stubs at all, and that is the assertion behind the assertion: a
    // refused send never reaches the IMAP side, so nothing was there to undo.
    Email stored = storedDraft();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      transportMock.when(() -> Transport.send(any(Message.class))).thenThrow(new MessagingException("relay refused"));
      Email draft = draft("draft-1");
      assertThrows(IllegalStateException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
    }
    // The row was SYNCED and has just been re-saved with newer text, so DIRTY is where
    // it belongs: the copy up on the server is now stale.
    verify(emailBoxStorage).updateDraftState(TEST_USER, "draft-1", DraftState.DIRTY);
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  /**
   * A send whose draft shows a file that can no longer be read is refused, and refused
   * BEFORE anything is written, claimed or removed.
   * <p>
   * The asymmetry with the upload is the point. An upload that does not happen costs
   * the user nothing they can see; a mail delivered without the file its sender
   * attached cannot be discovered by them and cannot be taken back. So this one fails
   * the whole operation — and leaves the draft exactly where it was, on both sides, for
   * the user to take the broken chip off and try again.
   * <p>
   * Four separate "nothing happened" assertions rather than one, because there are four
   * distinct ways this could go wrong and each is its own regression: the text written
   * to the row, the SENDING claim taken, the mail put on the wire, the row deleted.
   *
   * @throws Exception when the mocked mail plumbing misbehaves
   */
  @Test
  void aSendRefusedOverAMissingFileClaimsNothingAndChangesNothing() throws Exception {
    givenAUsableMailbox();
    Email stored = storedDraft();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.getDraftAttachments(TEST_USER, "draft-1")).thenReturn(List.of(attachmentRow()));
    when(emailBoxStorage.attachmentFileExists(77L)).thenReturn(false);

    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      Email draft = draft("draft-1");
      assertThrows(IllegalStateException.class, () -> emailBoxService.sendDraft(draft, TEST_USER));
      transportMock.verifyNoInteractions();
    }
    verify(emailBoxStorage, never()).saveDraft(any(Email.class));
    verify(emailBoxStorage, never()).updateDraftState(anyString(), anyString(), any(DraftState.class));
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  /**
   * A draft sent tomorrow carries the file attached yesterday — and the send itself
   * never frees it.
   * <p>
   * The composer's payload holds upload ids for files attached in the session that is
   * still open, and a draft resumed after a restart has none of them; a message built
   * from the payload alone would go out with the words and without the files. So the
   * stored file is read off the row and put on the message, which is asserted here on
   * the message that actually reached {@code Transport.send}.
   * <p>
   * The second half is the slice's own rule: the files belong to the DRAFT, not to the
   * attempt at sending it. They are freed by the row delete that follows a successful
   * send — {@code deleteEmailsByIds}, which writes the file ids down before the bulk
   * delete takes the attachment rows out from under Java — and by nothing else on this
   * path. A {@code finally} that released them the way the ordinary send releases
   * upload ids would leave a refused send with a draft the user can read and never
   * send.
   *
   * @throws Exception when the mocked mail plumbing misbehaves
   */
  @Test
  void aDraftSentTomorrowCarriesYesterdaysFileAndTheRowDeleteIsWhatFreesIt() throws Exception {
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    givenADraftsFolder();
    Email stored = storedDraft();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.getDraftAttachments(TEST_USER, "draft-1")).thenReturn(List.of(attachmentRow()));
    when(emailBoxStorage.attachmentFileExists(77L)).thenReturn(true);
    givenTheFileStoreHolds(77L, "yesterday's bytes".getBytes());

    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      // The composer sends NO attachments at all: everything this draft carries was
      // attached in a session that is over, which is the whole case being tested.
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
      transportMock.verify(() -> Transport.send(sent.capture()));
      Multipart multipart = (Multipart) sent.getValue().getContent();
      assertEquals(2, multipart.getCount(), "the body, then the file the draft has been carrying");
      assertEquals("report.pdf", multipart.getBodyPart(1).getFileName());
    }
    // The one and only thing that frees a sent draft's files, and it does it by
    // recording them before the rows go.
    verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
  }

  @Test
  void aSendWhoseCleanupFailsStillTakesTheLocalRowAway() throws Exception {
    // The mail is out and cannot be recalled. Showing the user a draft of a message
    // they have already sent -- and inviting them to send it twice -- is worse than
    // leaving a stray copy in a Drafts folder.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    IMAPFolder draftsFolder = givenADraftsFolder();
    doThrow(new MessagingException("no")).when(draftsFolder).open(Folder.READ_WRITE);
    Email stored = storedDraft();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft("draft-1"), TEST_USER);
      transportMock.verify(() -> Transport.send(any(Message.class)));
    }
    verify(emailBoxStorage).deleteEmailsByIds(List.of(9L));
  }

  @Test
  void theRevisionGuardNeverDropsTheTextASendIsCarrying() throws Exception {
    // Everywhere else a save at a revision the row has already reached is a late
    // autosave and is dropped. A send is not that: it carries what is on screen at the
    // moment the button was pressed, so it is forced past the stored revision -- or
    // the mail that goes out and the row that is kept would say different things.
    givenAUsableMailbox();
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    givenADraftsFolder();
    Email stored = storedDraft();
    stored.setDraftRevision(7L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    Email draft = draft("draft-1");
    draft.setDraftRevision(7L);
    try (MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      emailBoxService.sendDraft(draft, TEST_USER);
    }
    ArgumentCaptor<Email> written = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).saveDraft(written.capture());
    assertEquals(8L, written.getValue().getDraftRevision());
  }

  @Test
  void aSaveUnderAnIdThatIsGoneNeverBringsTheDraftBack() throws Exception {
    // An autosave that left the browser before the draft was sent and landed after.
    // Re-creating the row would put a draft of an already-sent mail back in front of
    // the user; answering "there is no such draft" is the whole fix.
    givenAUsableMailbox();
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(null);
    assertNull(emailBoxService.saveDraft(draft("draft-1"), TEST_USER, false));
    verify(emailBoxStorage, never()).saveDraft(any(Email.class));
  }

  @Test
  @SneakyThrows
  void theSyncCleanupNeverDeletesADraftThatIsNotSafelyOnTheServer() {
    // The single most dangerous existing rule meeting the one row it must not apply
    // to: cleanupObsoleteEmails deletes any cached row whose UID is not in the server
    // window, and for a draft that has not been uploaded that is exactly the normal
    // state of the newest thing the user has written.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageCount()).thenReturn(1);
    MimeMessage onServer = mock(MimeMessage.class);
    when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new MimeMessage[] { onServer });
    when(((UIDFolder) inbox).getUID(onServer)).thenReturn(1L);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(10);
    // Four cached rows the server window does not contain: an ordinary message, an
    // unpushed draft, a draft whose server copy is stale, and one a send has claimed
    // — that last one because the send is going to take it apart itself, in an order
    // chosen for what happens when a step fails.
    Email obsolete = lightRow(11L, 900L, null);
    Email localOnlyDraft = lightRow(12L, null, DraftState.LOCAL_ONLY);
    Email dirtyDraft = lightRow(13L, 901L, DraftState.DIRTY);
    Email sendingDraft = lightRow(14L, 902L, DraftState.SENDING);
    when(emailBoxStorage.getSyncEmails(TEST_USER, MailFolder.INBOX)).thenReturn(List.of(obsolete,
                                                                                        localOnlyDraft,
                                                                                        dirtyDraft,
                                                                                        sendingDraft));
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<List<Long>> deleted = ArgumentCaptor.forClass(List.class);
    verify(emailBoxStorage).deleteEmailsByIds(deleted.capture());
    assertEquals(List.of(11L), deleted.getValue());
  }

  @Test
  @SneakyThrows
  void aDraftWrittenInAnotherClientBecomesARowOfItsOwn() {
    // There is no reliable cross-client identity for a draft — most clients mint a
    // fresh Message-ID on every save — so a Drafts message whose UID we do not know
    // becomes a NEW row. It is emphatically not merged into a draft of ours by
    // subject, In-Reply-To or recipients: that would silently discard one side's
    // writing, which is the failure this whole design exists to prevent.
    MimeMessage phoneDraft = serverDraft("<phone@example.org>");
    IMAPFolder draftsFolder = givenASyncableDraftsFolder(phoneDraft);
    when(draftsFolder.getUID(phoneDraft)).thenReturn(77L);
    // One draft of our own, being written here, on a UID the server does not have.
    when(emailBoxStorage.getSyncEmails(TEST_USER, MailFolder.DRAFTS))
                                                                    .thenReturn(List.of(lightDraftRow(13L,
                                                                                                      null,
                                                                                                      DraftState.LOCAL_ONLY,
                                                                                                      "draft-1")));
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    Email imported = created.getValue();
    assertEquals(MailFolder.DRAFTS, imported.getFolder());
    assertEquals(77L, imported.getMailRemoteId());
    assertEquals("<phone@example.org>", imported.getMailHeaderId());
    assertEquals(DraftState.SYNCED, imported.getDraftState());
    assertTrue(StringUtils.isNotBlank(imported.getDraftLocalId()));
    // Threaded through the one computeThreadId every other message goes through, so
    // an imported draft sits in the conversation it answers rather than in a notion
    // of a conversation of its own.
    assertEquals("<phone@example.org>", imported.getThreadId());
    // A draft is the user's own text: never unread, never recent, so the new-mail
    // notification cannot see it.
    assertTrue(imported.isRead());
    assertFalse(imported.isRecent());
    // And ours is untouched.
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  /**
   * An imported draft keeps what the message said about its own body.
   * <p>
   * The import reads the part with the same reader every synced message goes through
   * — which records the part's Content-Type — and then rebuilt the content around
   * the body alone, dropping that answer. The row went down claiming plain text, and
   * a draft written in HTML on someone's phone came back here as its own markup.
   */
  @Test
  @SneakyThrows
  void anImportedDraftKeepsWhatTheMessageSaidAboutItsBody() {
    MimeMessage phoneDraft = serverDraft("<phone@example.org>");
    when(phoneDraft.isMimeType("text/*")).thenReturn(true);
    when(phoneDraft.isMimeType("text/html")).thenReturn(true);
    when(phoneDraft.getContent()).thenReturn("<div dir=\"ltr\">half a sentence</div>");
    IMAPFolder draftsFolder = givenASyncableDraftsFolder(phoneDraft);
    when(draftsFolder.getUID(phoneDraft)).thenReturn(77L);

    emailBoxService.synchronize(TEST_USER);

    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    assertTrue(created.getValue().getContent().isHtml());
    assertEquals("<div dir=\"ltr\">half a sentence</div>", created.getValue().getContent().getBody());
  }

  @Test
  @SneakyThrows
  void aDraftWithUnsavedWordsSurvivesASyncThatCannotSeeItAnyMore() {
    // The moment the cleanup's draft guard stops being theoretical. The server no
    // longer has the copy this row points at, and the row carries text that never
    // reached it — so it is kept, and put back to the state a draft that has never
    // been uploaded is in, which is exactly what it now is.
    IMAPFolder draftsFolder = givenASyncableDraftsFolder();
    Email dirtyDraft = lightDraftRow(13L, 901L, DraftState.DIRTY, "draft-1");
    Email unpushedDraft = lightDraftRow(12L, null, DraftState.LOCAL_ONLY, "draft-2");
    when(emailBoxStorage.getSyncEmails(TEST_USER, MailFolder.DRAFTS)).thenReturn(List.of(dirtyDraft, unpushedDraft));
    Email stored = storedDraft();
    stored.setDraftState(DraftState.DIRTY);
    stored.setMailRemoteId(901L);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage).detachDraftFromServerCopy(TEST_USER, "draft-1");
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
    // Read in the window, never written to: the sync opens Drafts READ_ONLY like
    // every other folder, and only the janitor takes a writable connection.
    verify(draftsFolder, never()).open(Folder.READ_WRITE);
  }

  @Test
  @SneakyThrows
  void aDraftDeletedInAnotherClientDisappearsHereToo() {
    // Nothing unsaved on this side: the row and the copy said the same thing, the
    // user deleted it on their phone, and that is what they meant. It is the
    // ordinary cleanup that carries this out — a SYNCED draft is deliberately not
    // protected from it.
    givenASyncableDraftsFolder();
    when(emailBoxStorage.getSyncEmails(TEST_USER, MailFolder.DRAFTS))
                                                                    .thenReturn(List.of(lightDraftRow(21L,
                                                                                                      800L,
                                                                                                      DraftState.SYNCED,
                                                                                                      "draft-9")));
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(List.of(21L));
    verify(emailBoxStorage, never()).detachDraftFromServerCopy(anyString(), anyString());
  }

  @Test
  @SneakyThrows
  void aDraftASendHasClaimedIsLeftExactlyWhereItIs() {
    // SENDING says nothing about the server copy: it is a claim, and the send that
    // took it is going to take the row apart itself, in an order chosen for what
    // happens when a step fails. A sync deleting it — or quietly rewriting its state
    // — would turn "sent but not cleaned up" into a silent double outcome.
    givenASyncableDraftsFolder();
    when(emailBoxStorage.getSyncEmails(TEST_USER, MailFolder.DRAFTS))
                                                                    .thenReturn(List.of(lightDraftRow(22L,
                                                                                                      802L,
                                                                                                      DraftState.SENDING,
                                                                                                      "draft-8")));
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
    verify(emailBoxStorage, never()).detachDraftFromServerCopy(anyString(), anyString());
  }

  @Test
  @SneakyThrows
  void theJanitorRemovesACopyOfAlreadySentMailAndSparesAGenuineDraft() {
    // A send whose cleanup failed leaves a copy in Drafts. It is recognisable with
    // certainty and by nothing else: the sent mail went out under the very
    // Message-ID the draft was pinned with, so a Drafts entry whose Message-ID is
    // already in Sent is that leftover. A draft that merely LOOKS sent is untouched.
    MimeMessage strayCopy = serverDraft("<already-sent@example.org>");
    MimeMessage genuineDraft = serverDraft("<still-writing@example.org>");
    IMAPFolder draftsFolder = givenASyncableDraftsFolder(strayCopy, genuineDraft);
    when(draftsFolder.getUID(strayCopy)).thenReturn(500L);
    when(draftsFolder.getUID(genuineDraft)).thenReturn(501L);
    when(emailBoxStorage.isMessageCachedInFolder(TEST_USER, "<already-sent@example.org>", MailFolder.SENT)).thenReturn(true);
    when(emailBoxStorage.isMessageCachedInFolder(TEST_USER, "<still-writing@example.org>", MailFolder.SENT)).thenReturn(false);
    Message strayOnServer = serverDraftCopy("<already-sent@example.org>");
    when(draftsFolder.getMessageByUID(500L)).thenReturn(strayOnServer);
    emailBoxService.synchronize(TEST_USER);
    // The leftover is removed from the server and never becomes a row: a draft of a
    // mail the user has already sent is the one thing this feature must not show.
    verify(draftsFolder).open(Folder.READ_WRITE);
    verify(strayOnServer).setFlag(Flags.Flag.DELETED, true);
    verify(draftsFolder, never()).getMessageByUID(501L);
    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    assertEquals("<still-writing@example.org>", created.getValue().getMailHeaderId());
  }

  @Test
  @SneakyThrows
  void aServerDraftWithNoSenderIsStoredUnderTheMailboxOwnersOwnAddress() {
    // Half-written mail with no From is exactly what a Drafts folder holds, and a
    // row stored with a blank sender cannot be read back at all — the entity mapper
    // splits that column on a comma and takes the second half. A draft in the user's
    // own Drafts folder is the user's, so their address is both safe and true.
    MimeMessage anonymousDraft = serverDraft("<no-from@example.org>");
    IMAPFolder draftsFolder = givenASyncableDraftsFolder(anonymousDraft);
    when(draftsFolder.getUID(anonymousDraft)).thenReturn(88L);
    when(anonymousDraft.getFrom()).thenReturn(null);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    assertNotNull(created.getValue().getSender());
    assertEquals(userEmailSetting().getEmailAddress(), created.getValue().getSender().getAddress());
  }

  @Test
  @SneakyThrows
  void aCacheResetNeverGoesNearTheDraftsFolder() {
    // The reset's premise is that the server is the truth and the local copy is
    // disposable. That is false for the one folder whose rows are authored here, so
    // inboxOnly skips Drafts exactly as it skips Sent and Archive — and this is the
    // caller for which the difference is not about cost.
    UserEmailSetting userEmailSetting = givenAUsableMailbox();
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    emailBoxService.resetAndResynchronize(TEST_USER);
    verify(emailBoxStorage, never()).getSyncEmails(TEST_USER, MailFolder.DRAFTS);
    // Not even resolved: a reset must not so much as look for the folder, or a
    // failure in there would report itself as a failed reset.
    verify(store, never()).getDefaultFolder();
  }

  /**
   * A connected mailbox whose Drafts folder holds the given messages — the starting
   * point of every Drafts-sync test. The inbox is present but empty, so the sync
   * reaches the folder under test without any of the inbox machinery running.
   *
   * @param serverDrafts the messages the Drafts folder holds, newest last
   * @return the mocked Drafts folder
   */
  @SneakyThrows
  private IMAPFolder givenASyncableDraftsFolder(MimeMessage... serverDrafts) {
    UserEmailSetting userEmailSetting = givenAUsableMailbox();
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder draftsFolder = mock(IMAPFolder.class);
    when(draftsFolder.exists()).thenReturn(true);
    when(draftsFolder.getAttributes()).thenReturn(new String[] { "\\Drafts" });
    lenient().when(draftsFolder.getFullName()).thenReturn("Drafts");
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { draftsFolder });
    when(draftsFolder.getMessageCount()).thenReturn(serverDrafts.length);
    if (serverDrafts.length > 0) {
      when(draftsFolder.getMessages(anyInt(), anyInt())).thenReturn(serverDrafts);
    }
    // A real cache size, because the trim half of the cleanup is one of the two
    // things under test here and a size of zero would put every row in the overflow.
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    return draftsFolder;
  }

  /**
   * A draft as it sits in the mail server's Drafts folder, written by some other
   * client of the user's.
   *
   * @param messageId the Message-ID that client gave it
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage serverDraft(String messageId) {
    MimeMessage message = mock(MimeMessage.class);
    when(message.getMessageID()).thenReturn(messageId);
    lenient().when(message.getFrom()).thenReturn(new Address[] { new InternetAddress("someone@example.org", "Someone") });
    return message;
  }

  /**
   * A copy of a draft as it is found again at a remembered UID: a message that
   * answers a Message-ID header, because that header is what proves the copy is the
   * one the row is pointing at before anything is flagged for deletion.
   *
   * @param messageId the Message-ID the message carries
   * @return the mocked message
   */
  @SneakyThrows
  private Message serverDraftCopy(String messageId) {
    Message message = mock(Message.class);
    when(message.getHeader("Message-ID")).thenReturn(new String[] { messageId });
    return message;
  }

  /**
   * A draft row as the light sync view returns it.
   *
   * @param id the row's technical id
   * @param mailRemoteId the IMAP UID of its copy on the server, null when it has none
   * @param draftState where the row stands against that copy
   * @param draftLocalId the composer's handle on it
   * @return the light row
   */
  private Email lightDraftRow(Long id, Long mailRemoteId, DraftState draftState, String draftLocalId) {
    Email email = lightRow(id, mailRemoteId, draftState);
    email.setDraftLocalId(draftLocalId);
    email.setFolder(MailFolder.DRAFTS);
    return email;
  }

  /**
   * A mailbox the user is allowed to use, with no other expectation set — the
   * starting point of every draft test.
   *
   * @return the user's email setting, for the tests that need to hand it back
   */
  private UserEmailSetting givenAUsableMailbox() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    return userEmailSetting;
  }

  /**
   * A connected store whose only subscribed folder announces itself as the Drafts
   * folder through the RFC 6154 SPECIAL-USE attribute.
   *
   * @return the mocked Drafts folder
   */
  @SneakyThrows
  private IMAPFolder givenADraftsFolder() {
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(any(UserEmailSetting.class))).thenReturn(store);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder draftsFolder = mock(IMAPFolder.class);
    when(draftsFolder.exists()).thenReturn(true);
    when(draftsFolder.getAttributes()).thenReturn(new String[] { "\\Drafts" });
    lenient().when(draftsFolder.getFullName()).thenReturn("Drafts");
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { draftsFolder });
    return draftsFolder;
  }

  /**
   * One file attached to a draft, as the row carries it: a name, a content type, the
   * file-store id its bytes live under, and no MIME part path at all — a file the user
   * attached is not part of any message until the draft is sent.
   *
   * @return the attachment row
   */
  private EmailAttachment attachmentRow() {
    return new EmailAttachment(3L, null, null, "report.pdf", "application/pdf", null, MailFolder.DRAFTS, 77L, 21L);
  }

  /**
   * A stored draft that carries one file, with the file store either holding its bytes
   * or having lost them.
   *
   * @param attachment the attachment row the draft carries
   * @param bytes the file's content, or null when the file is gone
   */
  private void givenAStoredDraftCarrying(EmailAttachment attachment, byte[] bytes) {
    Email stored = storedDraft();
    stored.setDraftState(DraftState.DIRTY);
    when(emailBoxStorage.getDraftByLocalId(TEST_USER, "draft-1")).thenReturn(stored);
    when(emailBoxStorage.saveDraft(any(Email.class))).thenAnswer(invocation -> {
      // What the storage layer hands back after an edit: the row, read whole, with the
      // file on it.
      Email toStore = invocation.getArgument(0);
      toStore.setMailHeaderId(stored.getMailHeaderId());
      toStore.setContent(new EmailContent(toStore.getContent().getBody(), null, List.of(attachment)));
      return toStore;
    });
    when(emailBoxStorage.attachmentFileExists(attachment.getFileId())).thenReturn(bytes != null);
    if (bytes != null) {
      givenTheFileStoreHolds(attachment.getFileId(), bytes);
    }
  }

  /**
   * The file store answering with a file — a FRESH {@link FileItem} on every call, as
   * the real one does.
   * <p>
   * Freshness is the point rather than realism for its own sake: a stub handing back
   * one instance forever would let a data source that captured a stream once look
   * correct, and that is exactly the defect these tests exist to catch.
   *
   * @param fileId the file id to answer for
   * @param bytes its content
   */
  @SneakyThrows
  private void givenTheFileStoreHolds(Long fileId, byte[] bytes) {
    when(emailBoxStorage.getAttachmentFileItem(fileId)).thenAnswer(invocation -> new FileItem(fileId,
                                                                                             "report.pdf",
                                                                                             "application/pdf",
                                                                                             "emailConnector",
                                                                                             bytes.length,
                                                                                             new Date(),
                                                                                             null,
                                                                                             false,
                                                                                             new ByteArrayInputStream(bytes)));
  }

  /**
   * A composed draft as the client sends it: text and recipients, and the local id
   * only once the client has one.
   *
   * @param draftLocalId the draft's local id, or null for a first save
   * @return the composed draft
   */
  private Email draft(String draftLocalId) {
    Email draft = new Email();
    draft.setDraftLocalId(draftLocalId);
    draft.setSubject("half a subject");
    draft.setContent(new EmailContent("half a sentence", null, null));
    draft.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
    return draft;
  }

  /**
   * A draft as the storage layer hands it back: the composer's text plus the
   * identity the service settled at the first save — the technical id, the minted
   * Message-ID, and the UID of the copy sitting in the mailbox's Drafts folder.
   *
   * @return the stored draft
   */
  private Email storedDraft() {
    Email stored = draft("draft-1");
    stored.setId(9L);
    stored.setUserId(TEST_USER);
    stored.setFolder(MailFolder.DRAFTS);
    stored.setDraftState(DraftState.SYNCED);
    stored.setDraftRevision(2L);
    stored.setMailRemoteId(4242L);
    stored.setMailHeaderId("<draft@example.org>");
    return stored;
  }

  /**
   * What the storage layer hands back from a draft save: the text that was just
   * written, over the identity the row already carried. The service reads the UID and
   * the Message-ID off that answer to decide what to remove from the Drafts folder,
   * so a mock that dropped them would be testing a row no real save ever produces.
   *
   * @param written the draft as the service asked for it to be stored
   * @param mailRemoteId the UID the row already carried, null when it carried none
   * @return the row as the storage layer would answer it
   */
  private Email storedRowAfter(Email written, Long mailRemoteId) {
    written.setMailRemoteId(mailRemoteId);
    written.setMailHeaderId("<draft@example.org>");
    return written;
  }

  /**
   * The row the storage layer answers with once an appended copy has been recorded.
   *
   * @param mailRemoteId the IMAP UID the server gave the appended copy
   * @return the draft as it now stands
   */
  private Email uploaded(long mailRemoteId) {
    Email email = draft("draft-1");
    email.setMailRemoteId(mailRemoteId);
    email.setDraftState(DraftState.SYNCED);
    return email;
  }

  /**
   * A row as the light sync view returns it — ids, flags and the draft state, and
   * nothing else.
   *
   * @param id the row's technical id
   * @param mailRemoteId the IMAP UID, null for a draft never uploaded
   * @param draftState the draft state, null for an ordinary message
   * @return the light row
   */
  private Email lightRow(Long id, Long mailRemoteId, DraftState draftState) {
    Email email = new Email();
    email.setId(id);
    email.setMailRemoteId(mailRemoteId);
    email.setDraftState(draftState);
    email.setUserId(TEST_USER);
    return email;
  }

  private Email emailWithCategories(List<Long> categoryIds) {
    Email email = email(TEST_USER);
    email.setCategoryIds(categoryIds);
    return email;
  }

  /**
   * A mocked message carrying the bulk-mail classification headers. Only non-null values
   * are stubbed, so an absent header behaves exactly as in production.
   */
  @SneakyThrows
  private MimeMessage bulkSignalMessage(String autoSubmitted, String precedence, String listPost) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.getHeader("Auto-Submitted"))
             .thenReturn(autoSubmitted == null ? null : new String[] { autoSubmitted });
    lenient().when(message.getHeader("Precedence")).thenReturn(precedence == null ? null : new String[] { precedence });
    lenient().when(message.getHeader("List-Post")).thenReturn(listPost == null ? null : new String[] { listPost });
    return message;
  }

  @Test
  @SneakyThrows
  void isAutoSubmittedReadsTheDeliveryHeaders() {
    // No headers at all: a message somebody typed.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, null, null)));
    // RFC 3834: any value other than "no" declares the message machine-generated, and the
    // comparison is case-insensitive -- providers stamp "Auto-Replied" and "AUTO-GENERATED".
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("auto-generated", null, null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("Auto-Replied", null, null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("AUTO-NOTIFIED", null, null)));
    // "no" is the explicit opposite and must NOT be read as automated, whatever its case
    // or surrounding whitespace.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("no", null, null)));
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("NO", null, null)));
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("  no  ", null, null)));
    // A blank header is not a declaration either.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("   ", null, null)));
    // Legacy Precedence, again case-insensitively.
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "bulk", null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "Junk", null)));
    // "list" is deliberately NOT automated: a mailing list stamps it on every message it
    // relays, including one a colleague typed by hand.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "list", null)));
  }

  @Test
  @SneakyThrows
  void isPostableListNeedsAnActualMailtoAddress() {
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, null)));
    assertTrue(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "<mailto:team@example.com>")));
    assertTrue(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "<MAILTO:team@example.com>")));
    // A list that explicitly refuses posting, and a malformed value: neither is postable,
    // which is what separates a discussion list from a one-way blast.
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "NO")));
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "team@example.com")));
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "")));
  }

  @Test
  @SneakyThrows
  void synchronizePersistsTheBulkMailSignals() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    Folder inbox = mockInboxForSync(userEmailSetting, 1);
    MimeMessage message = (MimeMessage) inbox.getMessages(1, 1)[0];
    lenient().when(message.getHeader("Auto-Submitted")).thenReturn(new String[] { "auto-generated" });
    lenient().when(message.getHeader("List-Id")).thenReturn(new String[] { "<team.example.com>" });
    lenient().when(message.getHeader("List-Post")).thenReturn(new String[] { "<mailto:team@example.com>" });
    lenient().when(message.getHeader("List-Unsubscribe")).thenReturn(new String[] { "<mailto:bye@example.com>" });
    lenient().when(message.getHeader("X-Original-Sender")).thenReturn(new String[] { "author@example.com" });

    emailBoxService.synchronize(TEST_USER);

    // The five signals must survive onto the cached row: they are the whole point of this
    // groundwork, and a downstream categorizer only ever sees the row, never the message.
    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    Email cached = created.getValue();
    assertTrue(cached.isAutoSubmitted());
    assertTrue(cached.isHasListId());
    assertTrue(cached.isHasListPost());
    assertTrue(cached.isHasListUnsubscribe());
    assertEquals("author@example.com", cached.getOriginalSender());
  }

  @Test
  @SneakyThrows
  void aBackstopOnlyFlushesTheWindowItWasArmedFor() {
    String user = "backstopuser";
    // The race the generation stamp exists for: cancel(false) does nothing once the timer
    // task has started, so a backstop can reach its flush at the very moment a new sync has
    // installed a fresh window. Driven through the guard directly rather than through the
    // scheduler -- the timer thread needs a live PortalContainer to get as far as the send,
    // so a timing-based test would pass or fail on the container, not on the guard.
    emailBoxService.openNotificationWindow(user, List.of());
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    long armedGeneration = notificationGeneration();

    // A new sync opens its own window: the old backstop is now stale.
    emailBoxService.openNotificationWindow(user, List.of());
    long freshGeneration = notificationGeneration();
    assertTrue(freshGeneration > armedGeneration);

    // The stale backstop must leave that fresh window alone -- flushing it here is what sent
    // an early notification and lost the in-flight window, with nothing in the logs.
    assertNull(emailBoxService.takePendingNotificationIfCurrent(user, armedGeneration));
    // And the window really is still there: its own generation still flushes it.
    assertNotNull(emailBoxService.takePendingNotificationIfCurrent(user, freshGeneration));
    // Once taken it is gone, so two timers cannot both send.
    assertNull(emailBoxService.takePendingNotificationIfCurrent(user, freshGeneration));
  }

  @Test
  @SneakyThrows
  void everyWindowGetsItsOwnGeneration() {
    String user = "generationuser";
    // Each transition installs a new window, so the timer armed by the previous one can
    // never flush the next: claims and releases re-arm the backstop each time.
    emailBoxService.openNotificationWindow(user, List.of());
    long opened = notificationGeneration();
    emailBoxService.deferNewEmailsNotification(user);
    long claimed = notificationGeneration();
    emailBoxService.deferNewEmailsNotification(user);
    long claimedAgain = notificationGeneration();
    emailBoxService.completeNotificationWindow(user, List.of());
    long completed = notificationGeneration();
    assertTrue(opened < claimed && claimed < claimedAgain && claimedAgain < completed);
    // Only the newest one is honoured.
    assertNull(emailBoxService.takePendingNotificationIfCurrent(user, opened));
    assertNull(emailBoxService.takePendingNotificationIfCurrent(user, claimed));
    assertNull(emailBoxService.takePendingNotificationIfCurrent(user, claimedAgain));
    assertNotNull(emailBoxService.takePendingNotificationIfCurrent(user, completed));
  }

  /**
   * The generation stamped on the most recently installed notification window.
   */
  private long notificationGeneration() {
    return ((AtomicLong) ReflectionTestUtils.getField(emailBoxService, "notificationGenerations")).get();
  }

  @Test
  @SneakyThrows
  void concurrentClaimsAndReleasesSendExactlyOnce() {
    String user = "raceuser";
    // The backstop is 15 minutes, far beyond this test, so the single send asserted below is
    // necessarily the real release path -- the point is that racing threads cannot produce
    // two sends, nor zero.
    int groups = 32;
    emailBoxService.openNotificationWindow(user, List.of());
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(groups);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < groups; i++) {
      Thread thread = new Thread(() -> {
        try {
          emailBoxService.deferNewEmailsNotification(user);
          start.await();
          emailBoxService.notifyNewEmailsClassified(user);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
      threads.add(thread);
      thread.start();
    }
    // Every claim is taken before any release, so the window can only empty after the
    // sync is marked complete below.
    emailBoxService.completeNotificationWindow(user, List.of());
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    for (Thread thread : threads) {
      thread.join(10000);
    }
    // Exactly one send, whatever order the releases landed in: the claim count and the
    // window's completion flag are read and written inside the same compute().
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void unlinkEmailsFromCategory() {
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(), 5L, TEST_USER));
    verify(categoryLinkService, never()).unlink(anyLong(), any(CategoryObject.class), anyString());

    // An email that is not the user's own (or no longer cached) is skipped.
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
    verify(categoryLinkService, never()).unlink(anyLong(), any(CategoryObject.class), anyString());

    mockOwnedEmail();
    assertEquals(1, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
    ArgumentCaptor<CategoryObject> objectCaptor = ArgumentCaptor.forClass(CategoryObject.class);
    verify(categoryLinkService).unlink(eq(5L), objectCaptor.capture(), eq(TEST_USER));
    assertEquals(EmailCategoryPlugin.OBJECT_TYPE, objectCaptor.getValue().getType());
    assertEquals("7", objectCaptor.getValue().getId());

    // Not linked to that category: idempotent, not counted as unlinked.
    doThrow(ObjectNotFoundException.class).when(categoryLinkService)
                                          .unlink(anyLong(), any(CategoryObject.class), anyString());
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
  }

  private void mockCategoryIdSetting(String nameId, String storedId) {
    doReturn(storedId == null ? null : SettingValue.create(storedId)).when(settingService)
                                                                    .get(any(Context.class), any(Scope.class), eq(nameId));
  }

  @SneakyThrows
  private void mockOwnedEmail() {
    Email email = email(TEST_USER);
    email.setId(7l);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(eq(1212l),
                                                         eq(TEST_USER),
                                                         any(),
                                                         anyString(),
                                                         anyBoolean(),
                                                         anyBoolean(),
                                                         anyBoolean())).thenReturn(email);
  }

  /**
   * The badge mirrors the inbox unread count, so every operation that changes
   * it has to announce it — and none that does not change it may, or every
   * online user pays an eviction, a frame and a re-fetch for nothing. These
   * cases are what a later refactor of this service could silently break.
   */
  @Test
  void readStatusChangeBroadcastsTheUnreadCountChange() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);

    emailBoxService.updateEmailReadStatus(List.of(1212l), TEST_USER, true, false);

    verify(listenerService).broadcast(EmailConnectorUtils.UNREAD_EMAILS_CHANGED, TEST_USER, null);
  }

  @Test
  void aNoOpReadStatusCallBroadcastsNothing() throws Exception {
    emailBoxService.updateEmailReadStatus(List.of(), TEST_USER, true, false);
    emailBoxService.updateEmailReadStatus(null, TEST_USER, true, false);

    // Nothing changed, so nothing to announce
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.UNREAD_EMAILS_CHANGED), any(), any());
  }

  @Test
  void deletingEmailsBroadcastsTheUnreadCountChange() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false))
                                                                                                            .thenReturn(email(TEST_USER));
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    IMAPFolder trashFolder = mock(IMAPFolder.class);
    when(trashFolder.getFullName()).thenReturn("trash");
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { trashFolder });
    when(trashFolder.exists()).thenReturn(true);
    when(trashFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(mock(Message.class));

    emailBoxService.deleteEmail(List.of(1212l), TEST_USER);

    // Removing rows the badge counts changes it just as reading them does
    verify(listenerService).broadcast(EmailConnectorUtils.UNREAD_EMAILS_CHANGED, TEST_USER, null);
  }

  @Test
  void syncStaysSilentWhenTheUnreadCountDidNotMove() throws Exception {
    mockEmptySync();
    when(emailBoxStorage.countUnreadEmails(TEST_USER)).thenReturn(3L);

    emailBoxService.synchronize(TEST_USER);

    // A cycle that changed nothing must stay silent: the badge cache would
    // otherwise be defeated for every connected user on every sync period
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.UNREAD_EMAILS_CHANGED), any(), any());
  }

  @Test
  void syncBroadcastsWhenTheUnreadCountMoved() throws Exception {
    mockEmptySync();
    when(emailBoxStorage.countUnreadEmails(TEST_USER)).thenReturn(3L, 5L);

    emailBoxService.synchronize(TEST_USER);

    verify(listenerService).broadcast(EmailConnectorUtils.UNREAD_EMAILS_CHANGED, TEST_USER, null);
  }

  /** A synchronisation that reaches its success path with no message to import. */
  @SneakyThrows
  private void mockEmptySync() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    lenient().when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    lenient().when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    lenient().when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    lenient().when(store.getFolder("INBOX")).thenReturn(inbox);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new MimeMessage[0]);
    lenient().when(emailBoxStorage.getEmails(anyString(), anyString())).thenReturn(new ArrayList<Email>());
    Folder defaultFolder = mock(Folder.class);
    lenient().when(store.getDefaultFolder()).thenReturn(defaultFolder);
    lenient().when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, "connector", true);
  }

  private Email email(String username) {
    return new Email(null,
                     1212l,
                     null,
                     username,
                     null,
                     "subject",
                     new EmailContent("excerpt"),
                     new Date(),
                     new EmailSender("sender", null, null, null),
                     false,
                     false,
                     List.of(new EmailRecipient()),
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     "INBOX",
                     null,
                     false,
                     false,
                     false,
                     false,
                     null,
                     false,
                     null,
                     null,
                     null,
                     null, null);
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null,
                              "testName",
                              null,
                              1L,
                              null,
                              "testImapUrl",
                              "8000",
                              "testSmtpUrl",
                              "9000",
                              "STARTTLS",
                              true,
                              false,
                              true,
                              "testUploadId",
                              "", null);
  }
}
