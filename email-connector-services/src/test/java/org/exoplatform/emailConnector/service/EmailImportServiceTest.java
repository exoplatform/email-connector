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
 */package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.StoreClosedException;
import javax.mail.search.SearchTerm;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.command.NotificationCommand;
import org.exoplatform.commons.api.notification.command.NotificationExecutor;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.MailImportRefusal;
import org.exoplatform.emailConnector.model.MailImportState;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.notification.plugin.MailImportFinishedNotificationPlugin;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.social.util.JsonUtils;

/**
 * The mail import (EXO-90846): what the request refuses on the spot, and what a run does
 * with the mails it reads -- appended with their own date and a read state taken from
 * the source, skipped when the folder holds their Message-ID or the run met them
 * already, refused by reason, stopped at its limits -- and that however it ends, the
 * uploads are deleted, the state stored and the user notified with the counts.
 */
@ExtendWith(MockitoExtension.class)
class EmailImportServiceTest {

  private static final String           USER     = "user";

  private static final String           FOLDER   = "CUSTOM:3";

  private static final String           MAIL_OLD = "From: a@example.com\r\nDate: Mon, 1 Jan 2024 10:00:00 +0000\r\n"
      + "Message-ID: <old@example.com>\r\nSubject: already there\r\n\r\nx\r\n";

  private static final String           MAIL_NEW = "From: b@example.com\r\nDate: Tue, 2 Jan 2024 11:00:00 +0000\r\n"
      + "Message-ID: <new@example.com>\r\nSubject: new\r\n\r\ny\r\n";

  @Mock
  private EmailBoxService               emailBoxService;

  @Mock
  private EmailFolderService            emailFolderService;

  @Mock
  private SettingService                settingService;

  @Mock
  private UploadService                 uploadService;

  @Spy
  @InjectMocks
  private EmailImportService            service;

  @TempDir
  Path                                  dir;

  private final Map<String, String>     settings  = new HashMap<>();

  private final List<NotificationContext> notified = new ArrayList<>();

  private final AtomicInteger           closedWith = new AtomicInteger(-1);

  private Folder                        folder;

  private final List<MimeMessage>       inFolder  = new ArrayList<>();

  private MockedStatic<RequestLifeCycle> requestLifeCycle;

  private MockedStatic<ExoContainerContext> containerContext;

  private MockedStatic<NotificationContextImpl> notifications;

  /**
   * Runs the import on the test's thread, keeps the stored state in memory, opens a
   * folder whose only message is {@code <old@example.com>}, and records notifications.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @BeforeEach
  void setUp() throws Exception {
    // The notification context class initialises from the container lookup: a container
    // is stated first, as the scheduled send's test does, or its initialisation fails.
    containerContext = org.mockito.Mockito.mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(mock(ExoContainer.class));
    requestLifeCycle = org.mockito.Mockito.mockStatic(RequestLifeCycle.class);
    notifications = org.mockito.Mockito.mockStatic(NotificationContextImpl.class);
    notifications.when(NotificationContextImpl::cloneInstance).thenAnswer(invocation -> {
      NotificationContext ctx = mock(NotificationContext.class, org.mockito.Answers.RETURNS_SELF);
      NotificationExecutor executor = mock(NotificationExecutor.class, org.mockito.Answers.RETURNS_SELF);
      lenient().when(ctx.getNotificationExecutor()).thenReturn(executor);
      lenient().when(ctx.makeCommand(any())).thenReturn(mock(NotificationCommand.class));
      notified.add(ctx);
      return ctx;
    });
    lenient().doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(0)).run();
      return null;
    }).when(service).scheduleRun(any(Runnable.class));
    lenient().doAnswer(invocation -> {
      settings.put(USER, ((SettingValue<?>) invocation.getArgument(3)).getValue().toString());
      return null;
    }).when(settingService).set(any(), any(), eq(EmailImportService.MAIL_IMPORT_STATE_KEY), any());
    lenient().when(settingService.get(any(), any(), eq(EmailImportService.MAIL_IMPORT_STATE_KEY)))
             .thenAnswer(invocation -> settings.containsKey(USER) ? SettingValue.create(settings.get(USER)) : null);
    folder = mock(Folder.class);
    inFolder.add(parse(MAIL_OLD));
    // The folder's search, as a server runs it: the term matched against what it holds.
    lenient().when(folder.search(any())).thenAnswer(invocation -> {
      SearchTerm term = invocation.getArgument(0);
      return inFolder.stream().filter(term::match).toArray(Message[]::new);
    });
    // What is appended is then in the folder, for the next run's search.
    lenient().doAnswer(invocation -> {
      inFolder.add((MimeMessage) ((Message[]) invocation.getArgument(0))[0]);
      return null;
    }).when(folder).appendMessages(any());
    lenient().when(emailBoxService.openImportTarget(USER, FOLDER)).thenAnswer(invocation -> new MailImportTarget(folder, closedWith::set));
  }

  /**
   * Releases the static stubs.
   */
  @AfterEach
  void tearDown() {
    requestLifeCycle.close();
    containerContext.close();
    notifications.close();
  }

  /**
   * A run reads every kind of file into the folder: a mail the folder holds is skipped,
   * a mail met twice without a Message-ID is added once, what is not a mail is refused,
   * the read state comes from the source (read when it says nothing), the date is the
   * mail's own; then the uploads are gone, the state says SUCCESS with the counts, the
   * folder was closed with the number added, and the user is notified.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aRunReadsEveryKindOfFileIntoTheFolder() throws Exception {
    String noId = "From: c@example.com\r\nDate: Wed, 3 Jan 2024 12:00:00 +0000\r\nSubject: no id\r\n\r\nz\r\n";
    String mbox = "From a Mon Jan  1 10:00:00 2024\n" + MAIL_OLD + "\n"
        + "From b Mon Jan  1 10:00:00 2024\n" + "X-Mozilla-Status: 0000\r\n" + MAIL_NEW + "\n"
        + "From c Mon Jan  1 10:00:00 2024\n" + noId + "\n"
        + "From d Mon Jan  1 10:00:00 2024\n" + noId + "\n";
    upload("u1", "one.eml", MAIL_NEW.replace("<new@", "<eml@").getBytes(StandardCharsets.US_ASCII));
    upload("u2", "box.mbox", mbox.getBytes(StandardCharsets.US_ASCII));
    upload("u3", "mails.zip", zip(Map.of("a.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))));
    long importDirsBefore = importDirs();

    MailImportState started = service.startImport(USER, FOLDER, List.of("u1", "u2", "u3"));

    assertEquals(SyncStatus.IN_PROGRESS, started.getStatus());
    MailImportState state = service.getImportState(USER);
    assertEquals(SyncStatus.SUCCESS, state.getStatus());
    assertEquals(3, state.getAdded());
    assertEquals(2, state.getSkipped());
    assertEquals(1, state.getRefused());
    assertEquals(Map.of(MailImportRefusal.NOT_A_MAIL.name(), 1L), state.getRefusals());
    assertNull(state.getMessageCode());
    assertEquals(state.getTotalBytes(), state.getProcessedBytes());
    List<MimeMessage> appended = inFolder.subList(1, inFolder.size());
    assertEquals(3, appended.size());
    assertTrue(appended.get(0).isSet(Flags.Flag.SEEN), "a source that says nothing is imported read");
    assertFalse(appended.get(1).isSet(Flags.Flag.SEEN), "Thunderbird's unread bit is kept");
    assertFalse(appended.get(1).isSet(Flags.Flag.RECENT));
    assertNull(appended.get(1).getReceivedDate());
    assertEquals(1704193200000L, appended.get(1).getSentDate().getTime(), "APPEND dates the mail by its own Date header");
    assertEquals(3, closedWith.get());
    verify(uploadService).removeUploadResource("u1");
    verify(uploadService).removeUploadResource("u2");
    verify(uploadService).removeUploadResource("u3");
    assertEquals(importDirsBefore, importDirs(), "the run's directory is deleted when it ends");
    assertEquals(1, notified.size());
    verify(notified.get(0)).append(MailImportFinishedNotificationPlugin.ADDED, "3");
    verify(notified.get(0)).append(MailImportFinishedNotificationPlugin.SKIPPED, "2");
    verify(notified.get(0)).append(MailImportFinishedNotificationPlugin.REFUSED, "1");
    verify(notified.get(0)).append(MailImportFinishedNotificationPlugin.STATUS, "SUCCESS");
  }

  /**
   * Importing the same file twice adds nothing the second time.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void importingTwiceAddsNothing() throws Exception {
    upload("u1", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    service.startImport(USER, FOLDER, List.of("u1"));
    assertEquals(1, service.getImportState(USER).getAdded());
    upload("u2", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));

    service.startImport(USER, FOLDER, List.of("u2"));

    MailImportState state = service.getImportState(USER);
    assertEquals(0, state.getAdded());
    assertEquals(1, state.getSkipped());
    verify(folder, times(1)).appendMessages(any());
  }

  /**
   * What the request can refuse, it refuses on the spot, deleting the uploads: no
   * upload, too many files, an upload gone, files too large, a folder the caller may not
   * write in -- a delegate without the insert right on that shared folder.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theRequestRefusesWhatCannotRun() throws Exception {
    assertEquals(EmailImportService.IMPORT_UPLOAD_MISSING,
                 assertThrows(IllegalArgumentException.class, () -> service.startImport(USER, FOLDER, List.of())).getMessage());
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i <= EmailImportService.MAX_IMPORT_FILES; i++) {
      tooMany.add("u" + i);
    }
    assertEquals(EmailImportService.IMPORT_TOO_MANY_FILES,
                 assertThrows(IllegalArgumentException.class, () -> service.startImport(USER, FOLDER, tooMany)).getMessage());


    assertEquals(EmailImportService.IMPORT_UPLOAD_MISSING,
                 assertThrows(IllegalArgumentException.class, () -> service.startImport(USER, FOLDER, List.of("gone"))).getMessage());


    File big = dir.resolve("big.mbox").toFile();
    try (RandomAccessFile file = new RandomAccessFile(big, "rw")) {
      file.setLength(EmailImportService.MAX_IMPORT_TOTAL_BYTES + 1);
    }
    UploadResource bigUpload = mock(UploadResource.class);
    when(bigUpload.getStoreLocation()).thenReturn(big.getAbsolutePath());
    when(uploadService.getUploadResource("big")).thenReturn(bigUpload);
    assertEquals(EmailImportService.IMPORT_TOO_LARGE,
                 assertThrows(IllegalArgumentException.class, () -> service.startImport(USER, FOLDER, List.of("big"))).getMessage());


    doThrow(new MailboxRightMissingException(MailboxRights.INSERT)).when(emailBoxService).checkImportTarget(USER, "CUSTOM:9");
    assertThrows(MailboxRightMissingException.class, () -> service.startImport(USER, "CUSTOM:9", List.of("w9")));

    // An upload id is not bound to its user: a refused request deletes none it named.
    verify(uploadService, never()).removeUploadResource(anyString());
    verify(service, never()).scheduleRun(any());
    assertTrue(notified.isEmpty());
  }

  /**
   * One import per user at a time: a run another node keeps fresh refuses a new one; a
   * run whose node stopped writing for too long is reported interrupted and no longer
   * holds the user's imports.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void oneImportAtATimeUntilARunGoesStale() throws Exception {
    MailImportState running = new MailImportState();
    running.setStatus(SyncStatus.IN_PROGRESS);
    running.setUpdatedDate(System.currentTimeMillis());
    settings.put(USER, JsonUtils.toJsonString(running));
    upload("u1", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));

    IllegalStateException conflict = assertThrows(IllegalStateException.class, () -> service.startImport(USER, FOLDER, List.of("u1")));
    assertEquals(EmailImportService.IMPORT_ALREADY_RUNNING, conflict.getMessage());
    verify(uploadService, never()).removeUploadResource("u1");
    assertEquals(SyncStatus.IN_PROGRESS, service.getImportState(USER).getStatus());

    running.setUpdatedDate(System.currentTimeMillis() - EmailImportService.STALE_AFTER_MS - 1);
    settings.put(USER, JsonUtils.toJsonString(running));
    MailImportState stale = service.getImportState(USER);
    assertEquals(SyncStatus.FAILURE, stale.getStatus());
    assertEquals(EmailImportService.IMPORT_INTERRUPTED, stale.getMessageCode());
    assertEquals(EmailImportService.MAX_MAIL_BYTES, stale.getMaxMailBytes());

    upload("u2", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    service.startImport(USER, FOLDER, List.of("u2"));
    assertEquals(SyncStatus.SUCCESS, service.getImportState(USER).getStatus());
  }

  /**
   * A run that starts long after its request -- its thread late, its node busy -- first
   * writes its state, so no node reads it as dead while it goes; and it reads the files
   * it took out of the upload service even when the uploads are gone meanwhile (the
   * session that made them ended).
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aLateRunWritesItsStateFirstAndOwnsItsFiles() throws Exception {
    List<Runnable> runs = new ArrayList<>();
    doAnswer(invocation -> runs.add(invocation.getArgument(0))).when(service).scheduleRun(any(Runnable.class));
    Path uploaded = upload("u1", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    service.startImport(USER, FOLDER, List.of("u1"));
    assertFalse(Files.exists(uploaded), "the run took the file out of the upload service");
    MailImportState stale = JsonUtils.fromJsonString(settings.get(USER), MailImportState.class);
    stale.setUpdatedDate(System.currentTimeMillis() - EmailImportService.STALE_AFTER_MS - 1);
    settings.put(USER, JsonUtils.toJsonString(stale));
    long[] updatedWhenOpened = new long[1];
    when(emailBoxService.openImportTarget(USER, FOLDER)).thenAnswer(invocation -> {
      updatedWhenOpened[0] = JsonUtils.fromJsonString(settings.get(USER), MailImportState.class).getUpdatedDate();
      return new MailImportTarget(folder, closedWith::set);
    });

    runs.get(0).run();

    assertTrue(System.currentTimeMillis() - updatedWhenOpened[0] < EmailImportService.STALE_AFTER_MS);
    assertEquals(1, service.getImportState(USER).getAdded());
  }

  /**
   * A run taken but never started -- the threads shut down under it -- leaves no
   * directory behind.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aRunNotStartedLeavesNoDirectory() throws Exception {
    doThrow(new java.util.concurrent.RejectedExecutionException("shut down")).when(service).scheduleRun(any(Runnable.class));
    upload("u1", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    long before = importDirs();

    assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> service.startImport(USER, FOLDER, List.of("u1")));

    assertEquals(before, importDirs());
  }

  /**
   * A mail server that refuses mail after mail stops the run after a few in a row, each
   * counted as refused by the server.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aServerRefusingEveryMailStopsTheRun() throws Exception {
    doThrow(new MessagingException("NO quota")).when(folder).appendMessages(any());
    upload("u1", "box.mbox", mbox(30).getBytes(StandardCharsets.US_ASCII));

    service.startImport(USER, FOLDER, List.of("u1"));

    MailImportState state = service.getImportState(USER);
    assertEquals(SyncStatus.SUCCESS, state.getStatus());
    assertEquals(EmailImportService.IMPORT_SERVER_REFUSING, state.getMessageCode());
    assertEquals(EmailImportService.SERVER_REFUSALS_TO_STOP, state.getRefused());
    assertEquals(Map.of(MailImportRefusal.SERVER_REFUSED.name(), (long) EmailImportService.SERVER_REFUSALS_TO_STOP), state.getRefusals());
  }

  /**
   * A connection lost mid-run ends it as a failure, the uploads still deleted and the
   * user still told.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aConnectionLostFailsTheRun() throws Exception {
    doThrow(new StoreClosedException(null, "gone")).when(folder).appendMessages(any());
    upload("u1", "box.mbox", mbox(3).getBytes(StandardCharsets.US_ASCII));

    service.startImport(USER, FOLDER, List.of("u1"));

    MailImportState state = service.getImportState(USER);
    assertEquals(SyncStatus.FAILURE, state.getStatus());
    assertEquals(EmailImportService.IMPORT_FAILED, state.getMessageCode());
    verify(folder, times(1)).appendMessages(any());
    verify(uploadService).removeUploadResource("u1");
    verify(notified.get(0)).append(MailImportFinishedNotificationPlugin.STATUS, "FAILURE");
  }

  /**
   * The run stops at the mail cap, with what was added staying added and the report
   * saying why.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theRunStopsAtTheMailCap() throws Exception {
    doNothing().when(folder).appendMessages(any());
    upload("u1", "box.mbox", mbox(EmailImportService.MAX_IMPORT_MAILS + 1).getBytes(StandardCharsets.US_ASCII));

    service.startImport(USER, FOLDER, List.of("u1"));

    MailImportState state = service.getImportState(USER);
    assertEquals(EmailImportService.MAX_IMPORT_MAILS, state.getAdded());
    assertEquals(EmailImportService.IMPORT_TOO_MANY_MAILS, state.getMessageCode());
  }

  /**
   * A folder gone, or a right lost, between the request and the run ends the run as a
   * failure that says which, and nothing is appended.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aFolderGoneOrARightLostFailsTheRun() throws Exception {
    when(emailBoxService.openImportTarget(USER, FOLDER)).thenReturn(null);
    upload("u1", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    service.startImport(USER, FOLDER, List.of("u1"));
    assertEquals(EmailImportService.IMPORT_FOLDER_MISSING, service.getImportState(USER).getMessageCode());

    when(emailBoxService.openImportTarget(USER, FOLDER)).thenThrow(new MailboxRightMissingException(MailboxRights.INSERT));
    upload("u2", "one.eml", MAIL_NEW.getBytes(StandardCharsets.US_ASCII));
    service.startImport(USER, FOLDER, List.of("u2"));
    MailImportState state = service.getImportState(USER);
    assertEquals(SyncStatus.FAILURE, state.getStatus());
    assertEquals(EmailImportService.IMPORT_ACCESS_LOST, state.getMessageCode());
    verify(folder, never()).appendMessages(any());
    verify(uploadService).removeUploadResource("u2");
  }

  /**
   * The read state a source gives: Thunderbird's bit, mutt's Status, Gmail's Unread
   * label; read when the source says nothing.
   *
   * @throws Exception when a message cannot be parsed
   */
  @Test
  void theReadStateComesFromTheSource() throws Exception {
    assertTrue(MailImportRun.isSeen(mime("X-Mozilla-Status: 0001\r\n")));
    assertFalse(MailImportRun.isSeen(mime("X-Mozilla-Status: 0000\r\n")));
    assertTrue(MailImportRun.isSeen(mime("Status: RO\r\n")));
    assertFalse(MailImportRun.isSeen(mime("Status: O\r\n")));
    assertFalse(MailImportRun.isSeen(mime("X-Gmail-Labels: Inbox,Unread,Important\r\n")));
    assertTrue(MailImportRun.isSeen(mime("X-Gmail-Labels: Inbox,Important\r\n")));
    assertTrue(MailImportRun.isSeen(mime("")));
  }

  /**
   * Registers an upload of a file.
   *
   * @param uploadId the upload id
   * @param name the file's name
   * @param content its bytes
   * @return the file the upload holds
   * @throws IOException when it cannot be written
   */
  private Path upload(String uploadId, String name, byte[] content) throws IOException {
    Path path = dir.resolve(uploadId + "-" + name);
    Files.write(path, content);
    UploadResource resource = mock(UploadResource.class);
    lenient().when(resource.getStoreLocation()).thenReturn(path.toString());
    lenient().when(uploadService.getUploadResource(uploadId)).thenReturn(resource);
    return path;
  }

  /**
   * How many import directories the temporary directory holds.
   *
   * @return the count
   * @throws IOException when it cannot be listed
   */
  private static long importDirs() throws IOException {
    try (java.util.stream.Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return paths.filter(path -> path.getFileName().toString().startsWith("email-import-")).count();
    }
  }

  /**
   * A mail parsed from its source.
   *
   * @param raw the source
   * @return the message
   * @throws Exception when it cannot be parsed
   */
  private static MimeMessage parse(String raw) throws Exception {
    return new MimeMessage(javax.mail.Session.getInstance(new java.util.Properties()),
                           new java.io.ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
  }

  /**
   * An mbox of distinct mails.
   *
   * @param count how many
   * @return the mbox
   */
  private static String mbox(int count) {
    StringBuilder mbox = new StringBuilder();
    for (int i = 0; i < count; i++) {
      mbox.append("From x Mon Jan  1 10:00:00 2024\n")
          .append("From: a@example.com\nDate: Mon, 1 Jan 2024 10:00:00 +0000\nMessage-ID: <m")
          .append(i)
          .append("@example.com>\n\nbody\n\n");
    }
    return mbox.toString();
  }

  /**
   * A zip's bytes.
   *
   * @param entries the entries, by name
   * @return the bytes
   * @throws IOException never
   */
  private static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /**
   * A message with extra headers before a minimal mail.
   *
   * @param headers the extra header lines
   * @return the parsed message
   * @throws Exception when it cannot be parsed
   */
  private static MimeMessage mime(String headers) throws Exception {
    return new MimeMessage(javax.mail.Session.getInstance(new java.util.Properties()),
                           new java.io.ByteArrayInputStream((headers + MAIL_NEW).getBytes(StandardCharsets.US_ASCII)));
  }
}
