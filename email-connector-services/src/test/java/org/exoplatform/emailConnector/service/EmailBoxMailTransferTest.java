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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.mail.Folder;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.RawEmailRef;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.emailConnector.storage.EmailReadReceiptAnswerStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobSchedulerService;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;

import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;

/**
 * The reads and the write behind mail exports and imports (EXO-90845, EXO-90846): who
 * may read a selection or a folder, who may import into a folder, and what is read. The
 * access checks are the surface: every refusal below is asserted to happen before the
 * mail server is asked anything, and a selection is refused whole rather than exported
 * with holes.
 */
@SpringBootTest(classes = { EmailBoxService.class, EmailFolderService.class })
@ExtendWith(MockitoExtension.class)
class EmailBoxMailTransferTest {

  private static final String     OWNER     = "owner";

  private static final String     DELEGATE  = "delegate";

  @MockitoBean
  private UserEmailSettingService userEmailSettingService;

  @MockitoBean
  private EmailBoxStorage         emailBoxStorage;

  @MockitoBean
  private SettingService          settingService;

  @MockitoBean
  private JobSchedulerService     jobSchedulerService;

  @MockitoBean
  private EmailSyncStateStorage   emailSyncStateStorage;

  @MockitoBean
  private ListenerService         listenerService;

  @MockitoBean
  private EmailConnectorService   emailConnectorService;

  @MockitoBean
  private CategoryLinkService     categoryLinkService;

  @MockitoBean
  private CategoryService         categoryService;

  @MockitoBean
  private ApplicationEventPublisher eventPublisher;

  @MockitoBean
  private EmailFavoriteService    emailFavoriteService;

  @MockitoBean
  private EmailSignatureService   emailSignatureService;

  @MockitoBean
  private EmailFolderStorage      emailFolderStorage;

  @MockitoBean
  private EmailCredentialsResolver emailCredentialsResolver;

  @MockitoBean
  private EmailScheduledSendStorage emailScheduledSendStorage;

  @MockitoBean
  private SmtpTransmitter         smtpTransmitter;

  @MockitoBean
  private EmailReadReceiptAnswerStorage readReceiptAnswerStorage;

  @MockitoBean
  private EmailDelegationService  emailDelegationService;

  @Autowired
  private EmailBoxService         emailBoxService;

  /**
   * Two messages of two folders: every check runs first, then one connection reads both
   * folders read-only, each message with PEEK, in the order named; the visitor begins once.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aSelectionIsReadFolderByFolderWithPeek() throws Exception {
    connected(OWNER);
    row(OWNER, MailFolder.INBOX, 11L, "<a@x>", "A");
    row(OWNER, MailFolder.SENT, 22L, "<b@x>", "B");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPFolder sent = uidFolder();
    when(sent.getAttributes()).thenReturn(new String[] { "\\Sent" });
    when(sent.getFullName()).thenReturn("Sent");
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.listSubscribed("*")).thenReturn(new Folder[] { sent });
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    IMAPMessage b = message("<b@x>", "B".getBytes(StandardCharsets.UTF_8));
    when(inbox.getMessagesByUID(new long[] { 11L })).thenReturn(new javax.mail.Message[] { a });
    when(sent.getMessagesByUID(new long[] { 22L })).thenReturn(new javax.mail.Message[] { b });
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readRawEmails(OWNER,
                                             List.of(new RawEmailRef(MailFolder.INBOX, 11L), new RawEmailRef(MailFolder.SENT, 22L)),
                                             visitor));

    assertEquals(List.of("begin:2", "message:A", "message:B"), visitor.events);
    verify(inbox).open(Folder.READ_ONLY);
    verify(sent).open(Folder.READ_ONLY);
    verify(a).setPeek(true);
    verify(b).setPeek(true);
    verify(userEmailSettingService).connect("1", OWNER);
    verify(store).close();
  }

  /**
   * One message of the selection the caller does not have refuses the whole export: no
   * connection, the visitor never begins.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void oneMessageNotCachedRefusesTheWholeSelection() throws Exception {
    connected(OWNER);
    row(OWNER, MailFolder.INBOX, 11L, "<a@x>", "A");
    RecordingVisitor visitor = new RecordingVisitor();

    assertFalse(emailBoxService.readRawEmails(OWNER,
                                              List.of(new RawEmailRef(MailFolder.INBOX, 11L), new RawEmailRef(MailFolder.INBOX, 12L)),
                                              visitor));

    assertTrue(visitor.events.isEmpty());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * One message in a shared folder the delegate may not read refuses the whole export,
   * before any row is read or the mail server contacted -- never a silent skip.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void oneFolderWithoutTheReadRightRefusesTheWholeSelection() throws Exception {
    connected(DELEGATE);
    row(DELEGATE, MailFolder.INBOX, 11L, "<a@x>", "A");
    doThrow(new MailboxRightMissingException(MailboxRights.READ)).when(emailDelegationService)
                                                                .checkRight(DELEGATE, "CUSTOM:12", MailboxRights.READ);

    assertThrows(MailboxRightMissingException.class,
                 () -> emailBoxService.readRawEmails(DELEGATE,
                                                     List.of(new RawEmailRef(MailFolder.INBOX, 11L), new RawEmailRef("CUSTOM:12", 5L)),
                                                     new RecordingVisitor()));
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(eq(5L), anyString(), any(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A caller who may not use their mailbox is refused before anything is read.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aCallerWhoMayNotUseTheirMailboxCannotExport() throws Exception {
    UserEmailSetting setting = setting();
    when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(setting);
    when(userEmailSettingService.canConnect(1L, OWNER)).thenReturn(false);

    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.readRawEmails(OWNER, List.of(new RawEmailRef(MailFolder.INBOX, 11L)), new RecordingVisitor()));
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 10, new RecordingVisitor()));
    assertThrows(IllegalAccessException.class, () -> emailBoxService.countFolderRawEmails(OWNER, MailFolder.INBOX));
    assertThrows(IllegalAccessException.class, () -> emailBoxService.checkImportTarget(OWNER, MailFolder.INBOX));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A message the server no longer holds under its UID, or holds another message under,
   * is handed over as missing once the export is under way -- for the visitor to report.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aMessageGoneOrRenumberedIsReportedMissing() throws Exception {
    connected(OWNER);
    row(OWNER, MailFolder.INBOX, 11L, "<a@x>", "A");
    row(OWNER, MailFolder.INBOX, 12L, "<b@x>", "B");
    row(OWNER, MailFolder.INBOX, 13L, "<c@x>", "C");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPMessage other = message("<other@x>", "O".getBytes(StandardCharsets.UTF_8));
    IMAPMessage c = message("<c@x>", "C".getBytes(StandardCharsets.UTF_8));
    when(inbox.getMessagesByUID(new long[] { 11L, 12L, 13L })).thenReturn(new javax.mail.Message[] { null, other, c });
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readRawEmails(OWNER,
                                             List.of(new RawEmailRef(MailFolder.INBOX, 11L),
                                                     new RawEmailRef(MailFolder.INBOX, 12L),
                                                     new RawEmailRef(MailFolder.INBOX, 13L)),
                                             visitor));

    assertEquals(List.of("begin:3", "missing:A", "missing:B", "message:C"), visitor.events);
    verify(other, never()).writeTo(any());
  }

  /**
   * A folder the selection names that the mailbox no longer has answers "no such
   * message" before the export begins.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aFolderGoneAnswersNothingBeforeTheExportBegins() throws Exception {
    connected(OWNER);
    row(OWNER, "CUSTOM:9", 11L, "<a@x>", "A");
    connectedStore(OWNER);
    RecordingVisitor visitor = new RecordingVisitor();

    assertFalse(emailBoxService.readRawEmails(OWNER, List.of(new RawEmailRef("CUSTOM:9", 11L)), visitor));
    assertTrue(visitor.events.isEmpty());
  }

  /**
   * A whole folder is read oldest first, read-only and with PEEK; a folder holding more
   * than the cap is refused before the export begins and before any message is read.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aFolderIsReadWholeUnderItsCap() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageCount()).thenReturn(2);
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    IMAPMessage b = message("<b@x>", "B".getBytes(StandardCharsets.UTF_8));
    folderOf(inbox, a, b);
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 2, visitor));
    assertEquals(List.of("begin:2", "folder-message", "folder-message"), visitor.events);
    verify(inbox, times(2)).open(Folder.READ_ONLY);
    verify(a).setPeek(true);
    verify(b).setPeek(true);

    RecordingVisitor refused = new RecordingVisitor();
    IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                                                    () -> emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 1, refused));
    assertEquals(EmailBoxService.EXPORT_TOO_MANY, tooMany.getMessage());
    assertTrue(refused.events.isEmpty());
  }

  /**
   * A delegate without the read right on a shared folder cannot export it, count it, or
   * have the mail server asked; a folder key their registry does not know names nothing.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aSharedFolderIsExportedOnlyWithTheReadRight() throws Exception {
    connected(DELEGATE);
    doThrow(new MailboxRightMissingException(MailboxRights.READ)).when(emailDelegationService)
                                                                .checkRight(DELEGATE, "CUSTOM:12", MailboxRights.READ);
    assertThrows(MailboxRightMissingException.class,
                 () -> emailBoxService.readFolderRawEmails(DELEGATE, "CUSTOM:12", 10, new RecordingVisitor()));
    assertThrows(MailboxRightMissingException.class, () -> emailBoxService.countFolderRawEmails(DELEGATE, "CUSTOM:12"));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());

    connectedStore(DELEGATE);
    RecordingVisitor visitor = new RecordingVisitor();
    assertFalse(emailBoxService.readFolderRawEmails(DELEGATE, "CUSTOM:77", 10, visitor));
    assertEquals(-1, emailBoxService.countFolderRawEmails(DELEGATE, "CUSTOM:77"));
    assertTrue(visitor.events.isEmpty());
  }

  /**
   * A delegate holding the read right exports the shared folder through its registry
   * entry, on their own connection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aDelegateWithTheReadRightExportsTheSharedFolder() throws Exception {
    connected(DELEGATE);
    EmailFolder registered = new EmailFolder();
    registered.setId(12L);
    registered.setRemoteName("Other Users/owner/INBOX");
    registered.setDelegationId(5L);
    when(emailFolderStorage.getFolder(DELEGATE, 12L)).thenReturn(registered);
    Store store = connectedStore(DELEGATE);
    IMAPFolder shared = uidFolder();
    when(shared.exists()).thenReturn(true);
    when(store.getFolder("Other Users/owner/INBOX")).thenReturn(shared);
    when(shared.getMessageCount()).thenReturn(1);
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    folderOf(shared, a);
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readFolderRawEmails(DELEGATE, "CUSTOM:12", 10, visitor));
    assertEquals(List.of("begin:1", "folder-message"), visitor.events);
    verify(emailDelegationService).checkRight(DELEGATE, "CUSTOM:12", MailboxRights.READ);
  }

  /**
   * Mail is never imported into Drafts, Trash, Spam, the Scheduled view or All Mail, nor
   * into a shared mailbox's folder of those roles.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void mailIsNotImportedIntoFoldersOfThoseRoles() throws Exception {
    connected(OWNER);
    for (String key : List.of(MailFolder.DRAFTS, MailFolder.TRASH, MailFolder.JUNK, MailFolder.SCHEDULED, MailFolder.ALL_MAIL)) {
      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                      () -> emailBoxService.checkImportTarget(OWNER, key));
      assertEquals(EmailBoxService.IMPORT_FOLDER_REFUSED, refused.getMessage());
    }
    connected(DELEGATE);
    when(emailDelegationService.roleOf(DELEGATE, "CUSTOM:13")).thenReturn(FolderRole.TRASH);
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.checkImportTarget(DELEGATE, "CUSTOM:13"));
    emailBoxService.checkImportTarget(OWNER, MailFolder.INBOX);
    emailBoxService.checkImportTarget(OWNER, "CUSTOM:3");
  }

  /**
   * A delegate without the insert right on a shared folder cannot import into it: the
   * refusal names the missing right, and the mail server is never contacted.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aDelegateWithoutTheInsertRightCannotImport() throws Exception {
    connected(DELEGATE);
    doThrow(new MailboxRightMissingException(MailboxRights.INSERT)).when(emailDelegationService)
                                                                  .checkRight(DELEGATE, "CUSTOM:12", MailboxRights.INSERT);

    assertThrows(MailboxRightMissingException.class, () -> emailBoxService.checkImportTarget(DELEGATE, "CUSTOM:12"));
    assertThrows(MailboxRightMissingException.class, () -> emailBoxService.openImportTarget(DELEGATE, "CUSTOM:12"));
    doThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED)).when(emailDelegationService)
                                                                              .checkRight(DELEGATE, "CUSTOM:14", MailboxRights.INSERT);
    assertThrows(DelegationRevokedException.class, () -> emailBoxService.openImportTarget(DELEGATE, "CUSTOM:14"));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * Opening an import target reads the Message-IDs the folder holds, so a mail already
   * there is recognised -- by the class's one rule of identity -- and an appended one is
   * learnt; closing it closes the connection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void anImportTargetKnowsTheFoldersMessageIds() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPMessage existing = mock(IMAPMessage.class);
    when(existing.getHeader("Message-ID")).thenReturn(new String[] { "<old@EXAMPLE.com>" });
    // The server's search, the term matched against what the folder holds.
    when(inbox.search(any())).thenAnswer(invocation -> {
      javax.mail.search.SearchTerm term = invocation.getArgument(0);
      return term.match(existing) ? new javax.mail.Message[] { existing } : new javax.mail.Message[0];
    });

    MailImportTarget target = emailBoxService.openImportTarget(OWNER, MailFolder.INBOX);

    assertNotNull(target);
    verify(inbox).open(Folder.READ_ONLY);
    assertTrue(target.contains("<old@example.com>"));
    assertTrue(target.contains("old@example.com"));
    assertFalse(target.contains("<new@example.com>"));
    javax.mail.internet.MimeMessage added = new javax.mail.internet.MimeMessage(javax.mail.Session.getInstance(new java.util.Properties()),
                                                                               new java.io.ByteArrayInputStream(("Message-ID: <new@example.com>\r\nFrom: a@b\r\nDate: Mon, 1 Jan 2024 10:00:00 +0000\r\n\r\nx").getBytes(StandardCharsets.US_ASCII)));
    target.append(added);
    assertTrue(target.contains("<new@example.com>"));
    assertEquals(1, target.getAppended());
    verify(inbox).appendMessages(new javax.mail.Message[] { added });
    target.close();
    target.close();
    verify(store).close();
  }

  /**
   * An import into a folder the caller's registry does not know opens nothing.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void anImportIntoAnUnknownFolderOpensNothing() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);

    assertNull(emailBoxService.openImportTarget(OWNER, "CUSTOM:404"));
    verify(store).close();
  }

  /**
   * Once the export began, a folder the server fails to open, and a message whose
   * envelope it fails to give, are each handed over as missing -- every row not yet
   * handed, never a silent end of the selection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aServerFailureAfterTheExportBeganReportsTheRest() throws Exception {
    connected(OWNER);
    row(OWNER, MailFolder.INBOX, 11L, "<a@x>", "A");
    row(OWNER, MailFolder.INBOX, 12L, "<b@x>", "B");
    row(OWNER, "CUSTOM:9", 21L, "<c@x>", "C");
    row(OWNER, "CUSTOM:9", 22L, "<d@x>", "D");
    EmailFolder registered = new EmailFolder();
    registered.setId(9L);
    registered.setRemoteName("Invoices");
    when(emailFolderStorage.getFolder(OWNER, 9L)).thenReturn(registered);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPFolder invoices = uidFolder();
    when(invoices.exists()).thenReturn(true);
    when(store.getFolder("Invoices")).thenReturn(invoices);
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    IMAPMessage b = mock(IMAPMessage.class);
    when(b.getMessageID()).thenThrow(new javax.mail.FolderClosedException(inbox, "dropped"));
    when(inbox.getMessagesByUID(new long[] { 11L, 12L })).thenReturn(new javax.mail.Message[] { a, b });
    doThrow(new javax.mail.MessagingException("renamed")).when(invoices).open(Folder.READ_ONLY);
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readRawEmails(OWNER,
                                             List.of(new RawEmailRef(MailFolder.INBOX, 11L),
                                                     new RawEmailRef(MailFolder.INBOX, 12L),
                                                     new RawEmailRef("CUSTOM:9", 21L),
                                                     new RawEmailRef("CUSTOM:9", 22L)),
                                             visitor));

    assertEquals(List.of("begin:4", "message:A", "missing:B", "missing:C", "missing:D"), visitor.events);
  }

  /**
   * A whole-folder export the server fails once begun is told so, with how far it got,
   * for the file to say it is incomplete -- never ended as a complete-looking file, and
   * never thrown out of a request whose answer is committed; one whose reader left ends
   * quietly.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aFolderExportTheServerFailsIsInterrupted() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageCount()).thenReturn(1);
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    when(inbox.getMessages()).thenReturn(new javax.mail.Message[] { a });
    when(inbox.getUID(a)).thenReturn(7L);
    when(inbox.getMessagesByUID(new long[] { 7L })).thenThrow(new javax.mail.StoreClosedException(store, "dropped"));
    RecordingVisitor visitor = new RecordingVisitor();

    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 10, visitor));
    assertEquals(List.of("begin:1", "interrupted:0/1"), visitor.events);

    doReturn(new javax.mail.Message[] { a }).when(inbox).getMessagesByUID(new long[] { 7L });
    RawEmailVisitor gone = new RecordingVisitor() {
      /**
       * The reader left.
       *
       * @param cached unused
       * @param message unused
       * @throws java.io.IOException always
       */
      @Override
      public void message(Email cached, MimeMessage message) throws java.io.IOException {
        throw new org.exoplatform.emailConnector.exception.ExportOutputClosedException(new java.io.IOException("reset"));
      }
    };
    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 10, gone));

    // A message cut mid-copy is in the file in part: the notice counts it.
    RecordingVisitor cut = new RecordingVisitor() {
      /**
       * Cut mid-copy.
       *
       * @param cached unused
       * @param message unused
       */
      @Override
      public void message(Email cached, MimeMessage message) {
        throw new org.exoplatform.emailConnector.exception.ExportInterruptedException("cut", null);
      }
    };
    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 10, cut));
    assertEquals(List.of("begin:1", "interrupted:1/1"), cut.events);
  }

  /**
   * The notice counts every message in the file: those copied whole, and the one cut
   * mid-copy.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theInterruptionCountsTheMessagesInTheFile() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageCount()).thenReturn(2);
    IMAPMessage a = message("<a@x>", "A".getBytes(StandardCharsets.UTF_8));
    IMAPMessage b = message("<b@x>", "B".getBytes(StandardCharsets.UTF_8));
    folderOf(inbox, a, b);
    RecordingVisitor visitor = new RecordingVisitor() {
      /**
       * Copies the first message whole, cuts the second.
       *
       * @param cached unused
       * @param message the message
       */
      @Override
      public void message(Email cached, MimeMessage message) {
        if (message == b) {
          throw new org.exoplatform.emailConnector.exception.ExportInterruptedException("cut", null);
        }
        events.add("whole");
      }
    };

    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 10, visitor));
    assertEquals(List.of("begin:2", "whole", "interrupted:2/2"), visitor.events);
  }

  /**
   * A folder larger than one window is read window by window -- each window its own
   * opening of the folder -- every message once, in order.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aLargeFolderIsReadWindowByWindow() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageCount()).thenReturn(501);
    IMAPMessage[] messages = new IMAPMessage[501];
    for (int i = 0; i < messages.length; i++) {
      messages[i] = mock(IMAPMessage.class);
      when(inbox.getUID(messages[i])).thenReturn(i + 1L);
    }
    when(inbox.getMessages()).thenReturn(messages);
    when(inbox.getMessagesByUID(any())).thenAnswer(invocation -> {
      long[] uids = invocation.getArgument(0);
      return java.util.Arrays.stream(uids).mapToObj(uid -> messages[(int) uid - 1]).toArray(javax.mail.Message[]::new);
    });
    List<IMAPMessage> handed = new ArrayList<>();
    RecordingVisitor visitor = new RecordingVisitor() {
      /**
       * Keeps the message handed over.
       *
       * @param cached unused
       * @param message the message
       */
      @Override
      public void message(Email cached, MimeMessage message) {
        handed.add((IMAPMessage) message);
      }
    };

    assertTrue(emailBoxService.readFolderRawEmails(OWNER, MailFolder.INBOX, 1000, visitor));

    assertEquals(List.of(messages), handed);
    verify(inbox, times(3)).open(Folder.READ_ONLY);
    verify(inbox).getMessagesByUID(org.mockito.ArgumentMatchers.argThat((long[] uids) -> uids.length == 500 && uids[0] == 1L));
    verify(inbox).getMessagesByUID(new long[] { 501L });
  }

  /**
   * Stubs a folder's messages for a whole-folder export: their UIDs, and the messages by
   * UID.
   *
   * @param folder the folder
   * @param messages its messages, oldest first
   * @throws Exception when the mock cannot be stubbed
   */
  private void folderOf(IMAPFolder folder, IMAPMessage... messages) throws Exception {
    when(folder.getMessages()).thenReturn(messages);
    long[] uids = new long[messages.length];
    for (int i = 0; i < messages.length; i++) {
      uids[i] = i + 1L;
      when(folder.getUID(messages[i])).thenReturn(uids[i]);
    }
    when(folder.getMessagesByUID(uids)).thenReturn(messages);
  }

  /**
   * Stubs one cached row of the caller.
   *
   * @param username the caller
   * @param folder the folder key
   * @param uid the UID
   * @param messageId the Message-ID the row was cached with
   * @param subject the cached subject
   */
  private void row(String username, String folder, long uid, String messageId, String subject) {
    Email row = new Email();
    row.setMailRemoteId(uid);
    row.setMailHeaderId(messageId);
    row.setSubject(subject);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(uid, username, null, folder, false, false, false)).thenReturn(row);
  }

  /**
   * Records what an export hands its visitor.
   */
  private static class RecordingVisitor implements RawEmailVisitor {

    final List<String>         events = new ArrayList<>();

    /**
     * Records the start.
     *
     * @param count how many messages follow
     */
    @Override
    public void begin(int count) {
      events.add("begin:" + count);
    }

    /**
     * Records a message, by its cached subject, or as a folder's message.
     *
     * @param cached the cached row, null for a folder export
     * @param message the message
     * @throws java.io.IOException never here; a subclass's output may fail
     */
    @Override
    public void message(Email cached, MimeMessage message) throws java.io.IOException {
      events.add(cached == null ? "folder-message" : "message:" + cached.getSubject());
    }

    /**
     * Records an interruption, with how far the export got.
     *
     * @param handed how many messages were handed over
     * @param count how many the export was to hold
     */
    @Override
    public void interrupted(int handed, int count) {
      events.add("interrupted:" + handed + "/" + count);
    }

    /**
     * Records a missing message, by its cached subject.
     *
     * @param cached the cached row
     */
    @Override
    public void missing(Email cached) {
      events.add("missing:" + cached.getSubject());
    }
  }

  /**
   * Stubs the caller's connected mailbox setting.
   *
   * @param username the caller
   */
  private void connected(String username) {
    UserEmailSetting setting = setting();
    when(userEmailSettingService.getUserEmailSetting(username)).thenReturn(setting);
    when(userEmailSettingService.canConnect(1L, username)).thenReturn(true);
  }

  /**
   * A connected setting on connector 1.
   *
   * @return the setting
   */
  private UserEmailSetting setting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, "connector", true);
  }

  /**
   * Stubs the caller's own connection.
   *
   * @param username the caller
   * @return the store the connection answers
   * @throws Exception when the mock cannot be stubbed
   */
  private Store connectedStore(String username) throws Exception {
    Store store = mock(Store.class);
    when(userEmailSettingService.connect("1", username)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    return store;
  }

  /**
   * An IMAP folder, addressable by UID.
   *
   * @return the folder mock
   */
  private IMAPFolder uidFolder() {
    return mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
  }

  /**
   * A message that writes the given bytes as its source.
   *
   * @param messageId its Message-ID
   * @param raw its source
   * @return the message mock
   * @throws Exception when the mock cannot be stubbed
   */
  private IMAPMessage message(String messageId, byte[] raw) throws Exception {
    IMAPMessage message = mock(IMAPMessage.class);
    lenient().when(message.getMessageID()).thenReturn(messageId);
    lenient().when(message.getSize()).thenReturn(raw.length);
    lenient().doAnswer(invocation -> {
      OutputStream out = invocation.getArgument(0);
      // In chunks, as the IMAP stream copies: a cap is reached inside a write.
      for (int offset = 0; offset < raw.length; offset += 16 * 1024) {
        out.write(raw, offset, Math.min(16 * 1024, raw.length - offset));
      }
      return null;
    }).when(message).writeTo(any());
    return message;
  }
}
