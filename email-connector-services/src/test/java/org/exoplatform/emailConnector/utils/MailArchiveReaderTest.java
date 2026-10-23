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
 */package org.exoplatform.emailConnector.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.exoplatform.emailConnector.model.MailImportRefusal;

/**
 * Reading the mails out of an imported file (EXO-90846). The file is hostile: these
 * tests feed it what an attacker would -- a zip bomb, entry names climbing out of the
 * archive, files that are not mail, a header without end, an mbox with unquoted
 * {@code From } lines -- and pin that each is refused or read for what it is, by the
 * bytes actually read, never by what the file claims.
 */
class MailArchiveReaderTest {

  private static final String MAIL_A = "From: a@example.com\r\nDate: Mon, 1 Jan 2024 10:00:00 +0000\r\nMessage-ID: <a@x>\r\n"
      + "Subject: A\r\n\r\nbody A\r\n";

  private static final String MAIL_B = "From: b@example.com\r\nDate: Tue, 2 Jan 2024 10:00:00 +0000\r\nSubject: B\r\n"
      + " folded\r\n\r\nbody B\r\n";

  private static final int    MAIL_CAP = 64 * 1024;

  @TempDir
  Path                        dir;

  /** A single .eml is one mail, as its bytes are. */
  @Test
  void anEmlIsOneMail() throws IOException {
    Recording sink = read(file("a.eml", MAIL_A.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MAIL_A), sink.mails);
    assertTrue(sink.refusals.isEmpty());
  }

  /**
   * What is not an RFC 822 message is refused, whatever its name: a PDF, an HTML page,
   * plain text, a header block without From or Date, a NUL in the headers, a folded
   * first line.
   */
  @Test
  void whatIsNotAMailIsRefused() throws IOException {
    for (String notAMail : List.of("%PDF-1.7\n%binary\n",
                                   "<html><script>alert(1)</script></html>\r\n",
                                   "just some text\r\n\r\nmore",
                                   "From: a@example.com\r\nSubject: no date\r\n\r\nx",
                                   "Date: Mon, 1 Jan 2024 10:00:00 +0000\r\nSubject: no from\r\n\r\nx",
                                   "From: a@example.com\r\nDate: x\r\nX-Evil: a\u0000b\r\n\r\nx",
                                   " From: a@example.com\r\nDate: x\r\n\r\nx",
                                   "")) {
      Recording sink = read(file("x.eml", notAMail.getBytes(StandardCharsets.ISO_8859_1)));
      assertTrue(sink.mails.isEmpty(), notAMail);
      assertEquals(List.of(MailImportRefusal.NOT_A_MAIL), sink.refusals, notAMail);
    }
  }

  /** A header block with no end within the header limit is refused as too large, unread further. */
  @Test
  void aHugeHeaderIsRefused() throws IOException {
    String huge = "From: a@example.com\r\nDate: x\r\nX-Huge: " + "a".repeat(5000) + "\r\n\r\nbody";
    Recording sink = read(file("h.eml", huge.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MailImportRefusal.TOO_LARGE), sink.refusals);
  }

  /** A mail over the per-mail cap is refused as too large. */
  @Test
  void aMailOverTheCapIsRefused() throws IOException {
    String big = MAIL_A + "x".repeat(MAIL_CAP);
    Recording sink = read(file("big.eml", big.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MailImportRefusal.TOO_LARGE), sink.refusals);
  }

  /**
   * An mbox is split on its From_ lines, the empty line before each removed, one
   * {@code >} removed from every quoted {@code >+From } line, and a {@code From } line
   * that does not follow an empty line read as body text -- a malformed file is read
   * whole rather than cut into fragments.
   */
  @Test
  void anMboxIsSplitAndUnquoted() throws IOException {
    String mbox = "From a@example.com Mon Jan  1 10:00:00 2024\n"
        + "From: a@example.com\nDate: Mon, 1 Jan 2024 10:00:00 +0000\n\n>From the body\n>>From quoted\nFrom unquoted mid-paragraph\n\n"
        + "From b@example.com Tue Jan  2 10:00:00 2024\n"
        + "From: b@example.com\nDate: Tue, 2 Jan 2024 10:00:00 +0000\n\nsecond\n\n";
    Recording sink = read(file("f.mbox", mbox.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of("From: a@example.com\nDate: Mon, 1 Jan 2024 10:00:00 +0000\n\nFrom the body\n>From quoted\nFrom unquoted mid-paragraph\n",
                         "From: b@example.com\nDate: Tue, 2 Jan 2024 10:00:00 +0000\n\nsecond\n"),
                 sink.mails);
  }

  /** An mbox written with CRLF line endings is read the same. */
  @Test
  void aCrlfMboxIsRead() throws IOException {
    String mbox = "From x Mon Jan  1 10:00:00 2024\r\n" + MAIL_A + "\r\n" + "From y Mon Jan  1 10:00:00 2024\r\n" + MAIL_B + "\r\n";
    Recording sink = read(file("f.mbox", mbox.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MAIL_A, MAIL_B), sink.mails);
  }

  /**
   * In an mbox, a message that is not a mail and one that is too large are each refused,
   * and the reading goes on with the next message.
   */
  @Test
  void anMboxRefusesItsBadMessagesOneByOne() throws IOException {
    String mbox = "From x Mon Jan  1 10:00:00 2024\nnot a mail at all\n\n"
        + "From y Mon Jan  1 10:00:00 2024\n" + MAIL_A.replace("\r\n", "\n") + "x".repeat(MAIL_CAP) + "\n\n"
        + "From z Mon Jan  1 10:00:00 2024\n" + MAIL_B.replace("\r\n", "\n") + "\n";
    Recording sink = read(file("f.mbox", mbox.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MailImportRefusal.NOT_A_MAIL, MailImportRefusal.TOO_LARGE), sink.refusals);
    assertEquals(List.of(MAIL_B.replace("\r\n", "\n")), sink.mails);
  }

  /** A sink that says stop is obeyed: nothing after its answer is read. */
  @Test
  void theSinkCanStopTheReading() throws IOException {
    String mbox = "From x Mon Jan  1 10:00:00 2024\n" + MAIL_A + "\nFrom y Mon Jan  1 10:00:00 2024\n" + MAIL_B + "\n";
    Recording sink = new Recording(1);
    reader().read(file("f.mbox", mbox.getBytes(StandardCharsets.US_ASCII)), sink);
    assertEquals(1, sink.mails.size());
  }

  /**
   * A zip of .eml is read entry by entry; directories and the resource entries macOS
   * adds are passed over unreported; a nested archive is not a mail.
   */
  @Test
  void aZipOfEmlIsRead() throws IOException {
    File zip = zip(Map.of("mails/", new byte[0],
                          "mails/a.eml", MAIL_A.getBytes(StandardCharsets.US_ASCII),
                          "b.eml", MAIL_B.getBytes(StandardCharsets.US_ASCII),
                          "__MACOSX/mails/._a.eml", new byte[] { 0, 5, 22, 7 },
                          "mails/._b.eml", new byte[] { 0, 5, 22, 7 },
                          "inner.zip", zipBytes(Map.of("c.eml", MAIL_A.getBytes(StandardCharsets.US_ASCII)))));
    Recording sink = read(zip);
    assertEquals(2, sink.mails.size());
    assertTrue(sink.mails.containsAll(List.of(MAIL_A, MAIL_B)));
    assertEquals(List.of(MailImportRefusal.NOT_A_MAIL), sink.refusals);
  }

  /** Entry names that climb out of the archive are refused unread. */
  @Test
  void entryNamesClimbingOutAreRefused() throws IOException {
    byte[] mail = MAIL_A.getBytes(StandardCharsets.US_ASCII);
    File zip = zip(Map.of("../evil.eml", mail, "/etc/evil.eml", mail, "a/../../evil.eml", mail, "dir\\evil.eml", mail,
                          "C:evil.eml", mail, "ok.eml", mail));
    Recording sink = read(zip);
    assertEquals(1, sink.mails.size());
    assertEquals(5, sink.refusals.size());
    assertTrue(sink.refusals.stream().allMatch(MailImportRefusal.UNSAFE_NAME::equals));
    assertFalse(MailArchiveReader.isSafeEntryName("a/\u0001.eml"));
    assertTrue(MailArchiveReader.isSafeEntryName("a/b..c.eml"));
  }

  /**
   * A zip bomb -- an entry inflating a thousand times beyond what it weighs -- stops the
   * whole archive, counted on the bytes actually inflated, before it reaches memory.
   */
  @Test
  void aZipBombStopsTheArchive() throws IOException {
    byte[] zeros = new byte[4 * 1024 * 1024];
    File zip = zip(Map.of("a.eml", MAIL_A.getBytes(StandardCharsets.US_ASCII), "bomb.eml", zeros));
    Recording sink = new Recording(Integer.MAX_VALUE);
    String cut = new MailArchiveReader(8 * 1024 * 1024, 4096, 100, 100, 1L << 30).read(zip, sink);
    assertEquals(MailArchiveReader.ZIP_BOMB, cut);
  }

  /** A zip with more entries than an import reads is not read at all. */
  @Test
  void tooManyEntriesStopTheArchiveUnread() throws IOException {
    java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 11; i++) {
      entries.put(i + ".eml", MAIL_A.getBytes(StandardCharsets.US_ASCII));
    }
    Recording sink = new Recording(Integer.MAX_VALUE);
    String cut = new MailArchiveReader(MAIL_CAP, 4096, 10, 100, 1L << 30).read(zip(entries), sink);
    assertEquals(MailArchiveReader.ZIP_TOO_MANY_ENTRIES, cut);
    assertTrue(sink.mails.isEmpty());
  }

  /** A zip inflating past the total an import reads stops there. */
  @Test
  void aZipLargerThanTheTotalStops() throws IOException {
    Recording sink = new Recording(Integer.MAX_VALUE);
    String cut = new MailArchiveReader(MAIL_CAP, 4096, 10, 100, 150).read(zip(Map.of("a.eml", MAIL_A.getBytes(StandardCharsets.US_ASCII),
                                                                                    "b.eml", MAIL_B.getBytes(StandardCharsets.US_ASCII))),
                                                                     sink);
    assertEquals(MailArchiveReader.ZIP_TOO_LARGE, cut);
  }

  /** An entry over the per-mail cap is refused as too large, and the archive goes on. */
  @Test
  void aZipEntryOverTheCapIsRefused() throws IOException {
    byte[] big = (MAIL_A + "x".repeat(MAIL_CAP)).getBytes(StandardCharsets.US_ASCII);
    Recording sink = read(zip(new java.util.TreeMap<>(Map.of("a.eml", big, "b.eml", MAIL_B.getBytes(StandardCharsets.US_ASCII)))));
    assertEquals(List.of(MailImportRefusal.TOO_LARGE), sink.refusals);
    assertEquals(List.of(MAIL_B), sink.mails);
  }

  /** A file that says it is a zip and is not one is refused as unreadable. */
  @Test
  void aBrokenZipIsUnreadable() throws IOException {
    Recording sink = new Recording(Integer.MAX_VALUE);
    String cut = reader().read(file("x.zip", "PK\u0003\u0004 not really a zip".getBytes(StandardCharsets.ISO_8859_1)), sink);
    assertEquals(MailArchiveReader.ARCHIVE_UNREADABLE, cut);
    assertEquals(List.of(MailImportRefusal.NOT_A_MAIL), sink.refusals);
  }

  /** The kind is the content's, not the name's: an .mbox name on a single mail reads it as one. */
  @Test
  void theKindIsToldByTheContent() throws IOException {
    Recording sink = read(file("misnamed.mbox", MAIL_A.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(List.of(MAIL_A), sink.mails);
    assertNull(reader().read(file("empty.mbox", new byte[0]), new Recording(Integer.MAX_VALUE)));
  }

  /**
   * Reads a file with the test limits.
   *
   * @param file the file
   * @return what the sink got
   * @throws IOException never
   */
  private Recording read(File file) throws IOException {
    Recording sink = new Recording(Integer.MAX_VALUE);
    reader().read(file, sink);
    return sink;
  }

  /**
   * A reader with small limits.
   *
   * @return the reader
   */
  private static MailArchiveReader reader() {
    return new MailArchiveReader(MAIL_CAP, 4096, 100, 100, 1L << 30);
  }

  /**
   * Writes a file in the test directory.
   *
   * @param name its name
   * @param content its bytes
   * @return the file
   * @throws IOException never
   */
  private File file(String name, byte[] content) throws IOException {
    Path path = dir.resolve(name);
    Files.write(path, content);
    return path.toFile();
  }

  /**
   * Writes a zip in the test directory.
   *
   * @param entries the entries, by name
   * @return the file
   * @throws IOException never
   */
  private File zip(Map<String, byte[]> entries) throws IOException {
    File file = dir.resolve("archive-" + System.nanoTime() + ".zip").toFile();
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(zipBytes(entries));
    }
    return file;
  }

  /**
   * A zip's bytes.
   *
   * @param entries the entries, by name
   * @return the bytes
   * @throws IOException never
   */
  private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
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
   * Records what the reader hands over, and stops after a number of mails.
   */
  private static final class Recording implements MailArchiveSink {

    private final List<String>            mails    = new ArrayList<>();

    private final List<MailImportRefusal> refusals = new ArrayList<>();

    private final int                     stopAfter;

    /**
     * @param stopAfter how many mails before answering stop
     */
    private Recording(int stopAfter) {
      this.stopAfter = stopAfter;
    }

    /**
     * Records a mail.
     *
     * @param message its bytes
     * @return whether to go on
     */
    @Override
    public boolean mail(byte[] message) {
      mails.add(new String(message, StandardCharsets.ISO_8859_1));
      return mails.size() < stopAfter;
    }

    /**
     * Records a refusal.
     *
     * @param reason why
     * @return true
     */
    @Override
    public boolean refused(MailImportRefusal reason) {
      refusals.add(reason);
      return true;
    }
  }
}
