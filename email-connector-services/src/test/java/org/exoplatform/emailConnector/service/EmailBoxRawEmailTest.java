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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.UIDFolder;

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
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.RawEmailSource;
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
 * The raw source of a message -- the {@code .eml} download and the "Show original" view
 * (EXO-90842): who may read it, and what is read. The access check is the surface: a UID
 * is a number anybody can guess, so every refusal and every "no such message" below is
 * asserted to happen before the mail server is asked anything, or before the sink is
 * opened.
 */
@SpringBootTest(classes = { EmailBoxService.class, EmailFolderService.class })
@ExtendWith(MockitoExtension.class)
class EmailBoxRawEmailTest {

  private static final String     OWNER     = "owner";

  private static final String     DELEGATE  = "delegate";

  private static final long       UID       = 4242L;

  private static final String     MESSAGE_ID = "<m1@example.com>";

  private static final String     RAW       = "From: Alice <alice@example.com>\r\n"
      + "Subject: Hello\r\n"
      + "Message-ID: <m1@example.com>\r\n"
      + "\r\n"
      + "<script>alert(1)</script> body\r\n";

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

  @MockitoBean
  private EmailDmarcVerdictBackfillService emailDmarcVerdictBackfillService;

  @Autowired
  private EmailBoxService         emailBoxService;

  /**
   * The owner reads the source of a message of their own inbox: the header block, the
   * whole source as text (markup included, as characters), the size the server reports,
   * not truncated. The folder is opened read-only and the message fetched with PEEK, so
   * reading the source does not mark it read.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theOwnerReadsTheSourceOfTheirOwnMessage() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.INBOX, MESSAGE_ID, "Hello");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPMessage message = message(MESSAGE_ID, RAW.getBytes(StandardCharsets.UTF_8));
    when(inbox.getMessageByUID(UID)).thenReturn(message);

    RawEmailSource source = emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX);

    assertNotNull(source);
    assertEquals(RAW, source.getSource());
    assertEquals("From: Alice <alice@example.com>\r\nSubject: Hello\r\nMessage-ID: <m1@example.com>", source.getHeaders());
    assertEquals(RAW.length(), source.getSize());
    assertEquals(RAW.length(), source.getShownBytes());
    assertFalse(source.isTruncated());
    verify(inbox).open(Folder.READ_ONLY);
    verify(message).setPeek(true);
    verify(emailDelegationService).checkRight(OWNER, MailFolder.INBOX, MailboxRights.READ);
    verify(store).close();
  }

  /**
   * A message longer than the shown limit is cut there and marked truncated, while its
   * size is still the whole message's: the view says how much it left out and offers the
   * download.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aLargeMessageIsCutAtTheShownLimit() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.INBOX, MESSAGE_ID, "Big");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    byte[] big = new byte[EmailBoxService.RAW_SOURCE_SHOWN_MAX_BYTES + 10];
    java.util.Arrays.fill(big, (byte) 'a');
    IMAPMessage message = message(MESSAGE_ID, big);
    when(inbox.getMessageByUID(UID)).thenReturn(message);

    RawEmailSource source = emailBoxService.getRawEmailSource(UID, OWNER, null);

    assertNotNull(source);
    assertTrue(source.isTruncated());
    assertEquals(EmailBoxService.RAW_SOURCE_SHOWN_MAX_BYTES, source.getSource().length());
    assertEquals(big.length, source.getSize());
    assertEquals(EmailBoxService.RAW_SOURCE_SHOWN_MAX_BYTES, source.getShownBytes());
  }

  /**
   * The download writes the whole source to the sink, which is opened once with the cached
   * subject and the size the server reports.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theDownloadStreamsTheWholeSourceToTheSink() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.SENT, MESSAGE_ID, "Report");
    Store store = connectedStore(OWNER);
    IMAPFolder sent = uidFolder();
    when(sent.getAttributes()).thenReturn(new String[] { "\\Sent" });
    when(sent.getFullName()).thenReturn("Sent");
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.listSubscribed("*")).thenReturn(new Folder[] { sent });
    byte[] raw = RAW.getBytes(StandardCharsets.UTF_8);
    IMAPMessage message = message(MESSAGE_ID, raw);
    when(sent.getMessageByUID(UID)).thenReturn(message);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AtomicInteger opened = new AtomicInteger();
    String[] subject = new String[1];

    boolean written = emailBoxService.writeRawEmail(UID, OWNER, MailFolder.SENT, (cachedSubject, size) -> {
      opened.incrementAndGet();
      subject[0] = cachedSubject;
      assertEquals(raw.length, size);
      return out;
    });

    assertTrue(written);
    assertEquals(1, opened.get());
    assertEquals("Report", subject[0]);
    assertEquals(RAW, out.toString(StandardCharsets.UTF_8));
  }

  /**
   * A caller whose mailbox is not connected (or not allowed) is refused before anything
   * is read: no row, no connection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aCallerWhoMayNotUseTheirMailboxIsRefused() throws Exception {
    UserEmailSetting setting = setting();
    when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(setting);
    when(userEmailSettingService.canConnect(1L, OWNER)).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX));
    assertThrows(IllegalAccessException.class, () -> emailBoxService.writeRawEmail(UID, OWNER, MailFolder.INBOX, failingSink()));
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * Another user's message, addressed by its UID: the caller has no row under that UID in
   * that folder (the cache is per user), so the answer is "no such message" -- and the
   * mail server is never asked, so a guessed UID cannot be read off the caller's own
   * mailbox under somebody else's number either.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aGuessedUidOfAnotherUsersMessageFindsNothing() throws Exception {
    connected(DELEGATE);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(eq(UID), eq(DELEGATE), isNull(), eq(MailFolder.INBOX), eq(false), eq(false), eq(false)))
                                                                                                                                             .thenReturn(null);

    assertNull(emailBoxService.getRawEmailSource(UID, DELEGATE, MailFolder.INBOX));
    assertFalse(emailBoxService.writeRawEmail(UID, DELEGATE, MailFolder.INBOX, failingSink()));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A delegate addressing a folder of the shared mailbox they were not given: the key is
   * unknown to their registry, so the rights check sees an own folder and lets it pass,
   * and the per-user cache holds no row under it -- "no such message", with no connection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aDelegateWithoutTheFolderFindsNothing() throws Exception {
    connected(DELEGATE);
    String notGiven = "CUSTOM:77";

    assertNull(emailBoxService.getRawEmailSource(UID, DELEGATE, notGiven));
    verify(emailDelegationService).checkRight(DELEGATE, notGiven, MailboxRights.READ);
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A delegate whose rights on that shared folder do not include reading is refused with
   * the missing right, before any row or the mail server is read.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aDelegateWithoutTheReadRightIsRefused() throws Exception {
    connected(DELEGATE);
    String folder = "CUSTOM:12";
    doThrow(new MailboxRightMissingException(MailboxRights.READ)).when(emailDelegationService)
                                                                .checkRight(DELEGATE, folder, MailboxRights.READ);

    assertThrows(MailboxRightMissingException.class, () -> emailBoxService.getRawEmailSource(UID, DELEGATE, folder));
    assertThrows(MailboxRightMissingException.class, () -> emailBoxService.writeRawEmail(UID, DELEGATE, folder, failingSink()));
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A share that ended is said to be gone, not refused, and nothing is read.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void anEndedShareIsGone() throws Exception {
    connected(DELEGATE);
    String folder = "CUSTOM:12";
    doThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED)).when(emailDelegationService)
                                                                              .checkRight(DELEGATE, folder, MailboxRights.READ);

    assertThrows(DelegationRevokedException.class, () -> emailBoxService.writeRawEmail(UID, DELEGATE, folder, failingSink()));
    verify(userEmailSettingService, never()).connect(anyString(), anyString());
  }

  /**
   * A delegate holding the read right on a shared folder reads a message of it, through
   * the folder its rows were cached from: the registry entry of that key, on the
   * delegate's own connection.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aDelegateWithTheReadRightReadsTheSharedFolder() throws Exception {
    connected(DELEGATE);
    String folder = "CUSTOM:12";
    cachedRow(DELEGATE, folder, MESSAGE_ID, "Shared");
    EmailFolder registered = new EmailFolder();
    registered.setId(12L);
    registered.setRemoteName("Other Users/owner/INBOX");
    registered.setDelegationId(5L);
    when(emailFolderStorage.getFolder(DELEGATE, 12L)).thenReturn(registered);
    Store store = connectedStore(DELEGATE);
    IMAPFolder shared = uidFolder();
    when(shared.exists()).thenReturn(true);
    when(store.getFolder("Other Users/owner/INBOX")).thenReturn(shared);
    IMAPMessage message = message(MESSAGE_ID, RAW.getBytes(StandardCharsets.UTF_8));
    when(shared.getMessageByUID(UID)).thenReturn(message);

    RawEmailSource source = emailBoxService.getRawEmailSource(UID, DELEGATE, folder);

    assertNotNull(source);
    assertEquals(RAW, source.getSource());
    verify(userEmailSettingService).connect("1", DELEGATE);
  }

  /**
   * The message the server holds under the UID is not the one the row was cached from
   * (the folder was renumbered since the last sync): "no such message", and the sink is
   * never opened, so another message is never served under this one's name.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aRenumberedUidIsNotAnsweredWithAnotherMessage() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.INBOX, MESSAGE_ID, "Hello");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPMessage other = mock(IMAPMessage.class);
    when(other.getMessageID()).thenReturn("<other@example.com>");
    when(inbox.getMessageByUID(UID)).thenReturn(other);

    assertFalse(emailBoxService.writeRawEmail(UID, OWNER, MailFolder.INBOX, failingSink()));
    verify(other, never()).writeTo(any());
  }

  /**
   * The Message-ID comparison is the class's own ({@code sameMessageId}): angle brackets
   * and the domain's case aside; and a row cached without one is read, there being nothing
   * to compare it with.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theMessageIdComparisonIgnoresBracketsAndMissingIds() throws Exception {
    connected(OWNER);
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    IMAPMessage message = message(MESSAGE_ID, RAW.getBytes(StandardCharsets.UTF_8));
    when(inbox.getMessageByUID(UID)).thenReturn(message);

    cachedRow(OWNER, MailFolder.INBOX, "m1@example.com", "Hello");
    assertNotNull(emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX));
    cachedRow(OWNER, MailFolder.INBOX, null, "Hello");
    assertNotNull(emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX));
    // The domain's case is not part of the identity: the class's one rule (EXO-90437).
    cachedRow(OWNER, MailFolder.INBOX, "<m1@EXAMPLE.COM>", "Hello");
    assertNotNull(emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX));
  }

  /**
   * The message is gone from the server (expunged since the last sync): "no such
   * message".
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aMessageGoneFromTheServerFindsNothing() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.INBOX, MESSAGE_ID, "Hello");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageByUID(UID)).thenReturn(null);

    assertNull(emailBoxService.getRawEmailSource(UID, OWNER, MailFolder.INBOX));
  }

  /**
   * The mail server failing before anything was written is a fault (500); failing once
   * the download is under way is not reported as one, there being nothing left to answer
   * with.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aServerFailureIsAFaultOnlyBeforeTheSinkIsOpened() throws Exception {
    connected(OWNER);
    cachedRow(OWNER, MailFolder.INBOX, MESSAGE_ID, "Hello");
    Store store = connectedStore(OWNER);
    IMAPFolder inbox = uidFolder();
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.getMessageByUID(UID)).thenThrow(new MessagingException("down"));

    assertThrows(IllegalStateException.class, () -> emailBoxService.writeRawEmail(UID, OWNER, MailFolder.INBOX, failingSink()));

    IMAPMessage message = mock(IMAPMessage.class);
    when(message.getMessageID()).thenReturn(MESSAGE_ID);
    doThrow(new IOException("client went away")).when(message).writeTo(any());
    doAnswer(invocation -> message).when(inbox).getMessageByUID(UID);
    assertTrue(emailBoxService.writeRawEmail(UID, OWNER, MailFolder.INBOX, (subject, size) -> new ByteArrayOutputStream()));
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
   * Stubs the caller's cached row for the test UID in a folder.
   *
   * @param username the caller
   * @param folder the folder key
   * @param messageId the Message-ID the row was cached with
   * @param subject the cached subject
   */
  private void cachedRow(String username, String folder, String messageId, String subject) {
    Email row = new Email();
    row.setMailRemoteId(UID);
    row.setMailHeaderId(messageId);
    row.setSubject(subject);
    row.setFolder(folder);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(UID, username, null, folder, false, false, false)).thenReturn(row);
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
    when(message.getMessageID()).thenReturn(messageId);
    when(message.getSize()).thenReturn(raw.length);
    doAnswer(invocation -> {
      OutputStream out = invocation.getArgument(0);
      // In chunks, as the IMAP stream copies: a cap is reached inside a write.
      for (int offset = 0; offset < raw.length; offset += 16 * 1024) {
        out.write(raw, offset, Math.min(16 * 1024, raw.length - offset));
      }
      return null;
    }).when(message).writeTo(any());
    return message;
  }

  /**
   * A sink a refused or empty read must never open.
   *
   * @return the sink
   */
  private RawEmailSink failingSink() {
    return (subject, size) -> {
      throw new AssertionError("The sink must not be opened");
    };
  }
}
