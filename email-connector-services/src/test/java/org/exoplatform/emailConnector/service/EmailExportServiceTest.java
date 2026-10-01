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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.ExportCheck;
import org.exoplatform.emailConnector.model.MailImportRefusal;
import org.exoplatform.emailConnector.model.RawEmailRef;
import org.exoplatform.emailConnector.utils.MailArchiveReader;
import org.exoplatform.emailConnector.utils.MailArchiveSink;

/**
 * The two export formats (EXO-90845): a selection as a .zip of .eml files, a folder as
 * an mboxrd file. Who may read is {@link EmailBoxService}'s and is mocked here; what is
 * pinned is the files: entry names safe and unique, a message the server lost named in
 * the zip rather than left out, the cap refused before anything is read, and an mbox
 * that the import reads back to the very messages that went in.
 */
@ExtendWith(MockitoExtension.class)
class EmailExportServiceTest {

  private static final String USER = "user";

  private static final String RAW  = "From: Alice <alice@example.com>\r\nDate: Mon, 1 Jan 2024 10:00:00 +0000\r\n"
      + "Message-ID: <m@x>\r\nSubject: Hi\r\n\r\nFrom the start\r\n>From quoted\r\nend\r\n";

  @Mock
  private EmailBoxService     emailBoxService;

  @Mock
  private EmailFolderService  emailFolderService;

  @InjectMocks
  private EmailExportService  emailExportService;

  @TempDir
  Path                        dir;

  /** The list's selection keys are read, a folder key holding a colon included. */
  @Test
  void selectionKeysAreRead() {
    assertEquals(List.of(new RawEmailRef("INBOX", 12L), new RawEmailRef("CUSTOM:3", 4242L)),
                 EmailExportService.parseSelection(List.of("INBOX:12", "CUSTOM:3:4242")));
  }

  /** A draft key, a key without UID, a non-numeric or non-positive UID, or no key at all is refused. */
  @Test
  void aMalformedSelectionIsRefused() {
    for (List<String> bad : List.of(List.<String> of(),
                                    List.of("INBOX:DRAFT-abc"),
                                    List.of("INBOX:"),
                                    List.of(":12"),
                                    List.of("12"),
                                    List.of("INBOX:0"),
                                    List.of("INBOX:-3"))) {
      IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> EmailExportService.parseSelection(bad));
      assertEquals(EmailExportService.EXPORT_INVALID_SELECTION, refused.getMessage());
    }
  }

  /**
   * Past the cap the zip is refused before anything is read, and the check says so
   * without checking each message.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theZipCapIsRefusedBeforeAnythingIsRead() throws Exception {
    List<String> selection = new ArrayList<>();
    for (int i = 1; i <= EmailExportService.MAX_ZIP_MAILS + 1; i++) {
      selection.add("INBOX:" + i);
    }
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> emailExportService.writeZip(USER, selection, (name, size) -> {
                                                      throw new AssertionError("never opened");
                                                    }));
    assertEquals(EmailBoxService.EXPORT_TOO_MANY, refused.getMessage());
    verify(emailBoxService, never()).readRawEmails(anyString(), anyList(), any());

    ExportCheck check = emailExportService.checkZip(USER, selection);
    assertEquals(EmailExportService.MAX_ZIP_MAILS + 1, check.getCount());
    assertEquals(EmailExportService.MAX_ZIP_MAILS, check.getMax());
    verify(emailBoxService, never()).getExportableEmails(anyString(), anyList());
  }

  /**
   * The check answers nothing when a message is not the caller's, and the count and the
   * cap otherwise.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theZipCheckRunsTheExportsChecks() throws Exception {
    when(emailBoxService.getExportableEmails(eq(USER), anyList())).thenReturn(null);
    assertNull(emailExportService.checkZip(USER, List.of("INBOX:1")));
    when(emailBoxService.getExportableEmails(eq(USER), anyList())).thenReturn(List.of(new Email()));
    assertEquals(new ExportCheck(1, EmailExportService.MAX_ZIP_MAILS), emailExportService.checkZip(USER, List.of("INBOX:1")));
  }

  /**
   * The zip holds one .eml per message, named after its subject and date, made safe and
   * unique without regard to case; a message the server lost is named in the report
   * entry, which no message can take the name of.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void theZipHoldsOneSafeUniqueEmlPerMessageAndReportsTheMissing() throws Exception {
    Date date = new Date(1704103200000L);
    when(emailBoxService.readRawEmails(eq(USER), anyList(), any())).thenAnswer(invocation -> {
      RawEmailVisitor visitor = invocation.getArgument(2);
      visitor.begin(4);
      visitor.message(row("Report", date), mime(RAW));
      visitor.message(row("report", date), mime(RAW));
      visitor.message(row("../../not-exported.txt\r\nX: y", null), mime(RAW));
      visitor.missing(row("Gone\r\nInjected line", date));
      return true;
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertTrue(emailExportService.writeZip(USER, List.of("INBOX:1", "INBOX:2", "SENT:3", "INBOX:4"), (name, size) -> out));

    Map<String, String> entries = unzip(out.toByteArray());
    assertEquals(List.of("Report 2024-01-01 1000.eml",
                         "report 2024-01-01 1000 (2).eml",
                         "_.._not-exported.txt__X_ y.eml",
                         EmailExportService.NOT_EXPORTED_ENTRY),
                 new ArrayList<>(entries.keySet()));
    assertEquals(RAW, entries.get("Report 2024-01-01 1000.eml"));
    String report = entries.get(EmailExportService.NOT_EXPORTED_ENTRY);
    assertTrue(report.contains("2024-01-01 1000 UTC  Gone  Injected line\r\n"), report);
  }

  /**
   * A selection that is not the caller's writes nothing.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void aSelectionNotTheCallersWritesNothing() throws Exception {
    when(emailBoxService.readRawEmails(eq(USER), anyList(), any())).thenReturn(false);
    assertFalse(emailExportService.writeZip(USER, List.of("INBOX:1"), (name, size) -> {
      throw new AssertionError("never opened");
    }));
  }

  /**
   * An exported mbox, read back by the import's reader, gives back the very messages
   * that went in -- line endings aside, which mbox stores as LF -- including the lines
   * that start with {@code From } and {@code >From }; each message is preceded by its
   * From_ line.
   *
   * @throws Exception when a mock cannot be stubbed
   */
  @Test
  void anExportedMboxReadsBackToTheSameMessages() throws Exception {
    String second = RAW.replace("<m@x>", "<n@x>").replace("Hi", "Again");
    when(emailBoxService.readFolderRawEmails(eq(USER), eq("INBOX"), eq(EmailExportService.MAX_MBOX_MAILS), any())).thenAnswer(invocation -> {
      RawEmailVisitor visitor = invocation.getArgument(3);
      visitor.begin(2);
      visitor.message(null, mime(RAW));
      visitor.message(null, mime(second));
      return true;
    });
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String[] hint = new String[1];

    assertTrue(emailExportService.writeMbox(USER, "INBOX", (name, size) -> {
      hint[0] = name;
      return out;
    }));

    assertEquals("Inbox", hint[0]);
    String mbox = out.toString(StandardCharsets.US_ASCII);
    assertTrue(mbox.startsWith("From alice@example.com Mon Jan  1 10:00:00 2024\n"), mbox);
    assertTrue(mbox.contains("\n>From the start\n>>From quoted\n"), mbox);
    File file = dir.resolve("export.mbox").toFile();
    Files.write(file.toPath(), out.toByteArray());
    List<String> readBack = new ArrayList<>();
    new MailArchiveReader(1 << 20, 1 << 16, 10, 100, 1L << 30).read(file, new MailArchiveSink() {
      /**
       * Keeps a mail.
       *
       * @param message its bytes
       * @return true
       */
      @Override
      public boolean mail(byte[] message) {
        readBack.add(new String(message, StandardCharsets.US_ASCII));
        return true;
      }

      /**
       * Fails on any refusal.
       *
       * @param reason why
       * @return never
       */
      @Override
      public boolean refused(MailImportRefusal reason) {
        throw new AssertionError(reason);
      }
    });
    assertEquals(List.of(RAW.replace("\r\n", "\n"), second.replace("\r\n", "\n")), readBack);
  }

  /**
   * The From_ line: the Return-Path, else the first From address, else MAILER-DAEMON for
   * a sender with white space or none; the received date, else the sent one, in UTC with
   * a space-padded day.
   *
   * @throws Exception when a message cannot be parsed
   */
  @Test
  void theFromLineNamesTheEnvelopeSender() throws Exception {
    assertEquals("From bounce@x.org Mon Jan  1 10:00:00 2024\n",
                 new String(EmailExportService.fromLine(mime("Return-Path: <bounce@x.org>\r\n" + RAW)), StandardCharsets.US_ASCII));
    assertEquals("From alice@example.com Mon Jan  1 10:00:00 2024\n",
                 new String(EmailExportService.fromLine(mime("Return-Path: <>\r\n" + RAW)), StandardCharsets.US_ASCII));
    assertEquals("From MAILER-DAEMON Thu Jan  1 00:00:00 1970\n",
                 new String(EmailExportService.fromLine(mime("From: \"a b\" <a b@x>\r\nSubject: x\r\n\r\nx")), StandardCharsets.US_ASCII));
  }

  /**
   * An mbox is named after the folder: a built-in by its English name, a folder of the
   * user's own as they named it, the key when the registry has no name.
   */
  @Test
  void anMboxIsNamedAfterItsFolder() {
    assertEquals("Sent", emailExportService.mboxName(USER, "SENT"));
    EmailFolder folder = new EmailFolder();
    folder.setDisplayName("Invoices 2024");
    when(emailFolderService.getFolderByKey(USER, "CUSTOM:3")).thenReturn(folder);
    assertEquals("Invoices 2024", emailExportService.mboxName(USER, "CUSTOM:3"));
    when(emailFolderService.getFolderByKey(USER, "CUSTOM:4")).thenThrow(new IllegalArgumentException("unknown"));
    assertEquals("CUSTOM:4", emailExportService.mboxName(USER, "CUSTOM:4"));
  }

  /** Names are de-duplicated without regard to case. */
  @Test
  void namesAreUniqueWithoutRegardToCase() {
    Set<String> used = new HashSet<>();
    assertEquals("a.eml", EmailExportService.uniqueName("a.eml", used));
    assertEquals("A (2).eml", EmailExportService.uniqueName("A.eml", used));
    assertEquals("a (3).eml", EmailExportService.uniqueName("a.eml", used));
  }

  /**
   * A cached row.
   *
   * @param subject its subject
   * @param date its received date
   * @return the row
   */
  private static Email row(String subject, Date date) {
    Email email = new Email();
    email.setSubject(subject);
    email.setReceivedDate(date);
    return email;
  }

  /**
   * A message parsed from its source.
   *
   * @param raw the source
   * @return the message
   * @throws Exception when it cannot be parsed
   */
  private static MimeMessage mime(String raw) throws Exception {
    return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
  }

  /**
   * A zip's entries, in order, as text.
   *
   * @param zip the zip's bytes
   * @return the entries by name
   * @throws Exception when it cannot be read
   */
  private static Map<String, String> unzip(byte[] zip) throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
