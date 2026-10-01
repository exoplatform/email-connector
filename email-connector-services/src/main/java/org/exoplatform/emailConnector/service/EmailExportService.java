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

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.ExportCheck;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.RawEmailRef;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Exports mail as files (EXO-90845): the messages ticked in the mailbox list as a
 * {@code .zip} of {@code .eml} files, and a whole folder as one {@code .mbox}.
 * <p>
 * Who may read what is {@link EmailBoxService}'s, by the rules of the {@code .eml}
 * download (EXO-90842): this class owns the two file formats only. Both are streamed to
 * the output as the mail server hands each message over, never held whole, and both
 * read with PEEK, so an export never marks mail read.
 */
@Service
public class EmailExportService {

  /** Message code answered as a 400 for a selection the list could not have produced. */
  public static final String             EXPORT_INVALID_SELECTION = "emailConnector.export.invalidSelection";

  /**
   * The most messages one {@code .zip} holds. The selection travels in the query string
   * of a plain download link, one {@code mails} parameter per folder carrying that
   * folder's UIDs comma-separated, colons and commas left unescaped: at most 11 bytes per
   * UID (ten digits and a comma) plus about 30 per folder, so 200 UIDs in a handful of
   * folders take under 2.5 KB of a request line Tomcat bounds at 8 KB together with the
   * headers. A larger set is a folder's, which the {@code .mbox} export serves.
   */
  public static final int                MAX_ZIP_MAILS            = 200;

  /**
   * The most messages one {@code .mbox} holds: a folder larger than this is a mailbox
   * migration, for which the mail server's own tools are the right ones.
   */
  public static final int                MAX_MBOX_MAILS           = 20000;

  /** The name of the zip entry listing the messages that could not be exported. */
  public static final String             NOT_EXPORTED_ENTRY       = "not-exported.txt";

  private static final Log               LOG                      = ExoLogger.getLogger(EmailExportService.class);

  // The asctime() date of an mbox From_ line, always in UTC: "Thu Jan  1 00:00:00 1970".
  private static final DateTimeFormatter FROM_LINE_DATE           = DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy",
                                                                                                Locale.US)
                                                                                     .withZone(ZoneOffset.UTC);

  // The date a zip entry is named with, in UTC: the file system refuses ":" on Windows.
  private static final DateTimeFormatter ENTRY_NAME_DATE          = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US)
                                                                                     .withZone(ZoneOffset.UTC);

  private static final String            MAILER_DAEMON            = "MAILER-DAEMON";

  // The file names of the built-in folders' exports.
  private static final Map<String, String> BUILT_IN_FOLDER_NAMES  = Map.of(MailFolder.INBOX,
                                                                           "Inbox",
                                                                           MailFolder.SENT,
                                                                           "Sent",
                                                                           MailFolder.ARCHIVE,
                                                                           "Archive",
                                                                           MailFolder.DRAFTS,
                                                                           "Drafts",
                                                                           MailFolder.JUNK,
                                                                           "Spam",
                                                                           MailFolder.TRASH,
                                                                           "Trash");

  @Autowired
  private EmailBoxService                emailBoxService;

  @Autowired
  private EmailFolderService             emailFolderService;

  /**
   * What a {@code .zip} of the selection would hold, for the check the drawer makes
   * before it starts the download: the count, and the cap it may not pass. Past the cap
   * nothing else is checked; below it, every message is checked as the export would.
   *
   * @param username the caller
   * @param selection the selection keys, {@code <folder>:<uid>} as the list makes them
   * @return the check, or null when one of the messages is not the caller's
   * @throws IllegalAccessException when the caller may not read a folder named
   * @throws IllegalArgumentException {@link #EXPORT_INVALID_SELECTION} for an empty or
   *           malformed selection
   */
  public ExportCheck checkZip(String username, List<String> selection) throws IllegalAccessException {
    List<RawEmailRef> refs = parseSelection(selection);
    if (refs.size() <= MAX_ZIP_MAILS && emailBoxService.getExportableEmails(username, refs) == null) {
      return null;
    }
    return new ExportCheck(refs.size(), MAX_ZIP_MAILS);
  }

  /**
   * Writes the selection as a {@code .zip}, one {@code .eml} per message, named after
   * its subject and its date, made unique. The output is opened through the sink once
   * every message was checked and every folder found, so a refusal keeps its own status.
   * A message the mail server no longer holds is named in a {@value #NOT_EXPORTED_ENTRY}
   * entry rather than left out silently.
   *
   * @param username the caller
   * @param selection the selection keys, {@code <folder>:<uid>} as the list makes them
   * @param sink where the zip goes, opened once
   * @return true when the zip was written, false when a message is not the caller's
   * @throws IllegalAccessException when the caller may not read a folder named
   * @throws IllegalArgumentException {@link #EXPORT_INVALID_SELECTION} for an empty or
   *           malformed selection, {@link EmailBoxService#EXPORT_TOO_MANY} past
   *           {@link #MAX_ZIP_MAILS}
   * @throws IllegalStateException when the mail server could not be read before the
   *           download began
   */
  public boolean writeZip(String username, List<String> selection, RawEmailSink sink) throws IllegalAccessException {
    List<RawEmailRef> refs = parseSelection(selection);
    if (refs.size() > MAX_ZIP_MAILS) {
      throw new IllegalArgumentException(EmailBoxService.EXPORT_TOO_MANY);
    }
    ZipExportVisitor visitor = new ZipExportVisitor(sink);
    boolean written = emailBoxService.readRawEmails(username, refs, visitor);
    if (written) {
      visitor.finish();
    }
    return written;
  }

  /**
   * What a folder's {@code .mbox} would hold, for the check the drawer makes before it
   * starts the download.
   *
   * @param username the caller
   * @param folder the folder key
   * @return the check, or null when the caller has no such folder
   * @throws IllegalAccessException when the caller may not read that folder
   * @throws IllegalStateException when the mail server could not be read
   */
  public ExportCheck checkMbox(String username, String folder) throws IllegalAccessException {
    int count = emailBoxService.countFolderRawEmails(username, folder);
    return count < 0 ? null : new ExportCheck(count, MAX_MBOX_MAILS);
  }

  /**
   * Writes a whole folder as one mboxrd file, oldest message first: each message behind
   * its {@code From_} line -- the envelope sender, else {@code MAILER-DAEMON}, and the
   * date the server received it, in UTC -- with its lines quoted by
   * {@code MboxrdOutputStream}. The sink is opened with the folder's name as the name
   * hint ({@link #mboxName}) once the folder is found and counted.
   *
   * @param username the caller
   * @param folder the folder key
   * @param sink where the file goes, opened once
   * @return true when the file was written, false when the caller has no such folder
   * @throws IllegalAccessException when the caller may not read that folder
   * @throws IllegalArgumentException {@link EmailBoxService#EXPORT_TOO_MANY} past
   *           {@link #MAX_MBOX_MAILS}
   * @throws IllegalStateException when the mail server could not be read before the
   *           download began
   */
  public boolean writeMbox(String username, String folder, RawEmailSink sink) throws IllegalAccessException {
    MboxExportVisitor visitor = new MboxExportVisitor(sink, mboxName(username, StringUtils.defaultIfBlank(folder, MailFolder.INBOX)));
    boolean written = emailBoxService.readFolderRawEmails(username, folder, MAX_MBOX_MAILS, visitor);
    if (written) {
      visitor.finish();
    }
    return written;
  }

  /**
   * The name an exported folder's file takes, before it is made safe: a built-in folder's
   * English name, a folder of the user's own as they named it, the key itself when the
   * registry does not answer.
   *
   * @param username the caller
   * @param folderKey the folder key
   * @return the name
   */
  String mboxName(String username, String folderKey) {
    if (MailFolder.isCustom(folderKey)) {
      try {
        EmailFolder folder = emailFolderService.getFolderByKey(username, folderKey);
        if (folder != null && StringUtils.isNotBlank(folder.getDisplayName())) {
          return folder.getDisplayName();
        }
      } catch (RuntimeException e) {
        LOG.debug("Folder {} of user {} has no name to export under", folderKey, username, e);
      }
      return folderKey;
    }
    return BUILT_IN_FOLDER_NAMES.getOrDefault(folderKey, folderKey);
  }

  /**
   * Reads the selection the mailbox list sends -- {@code <folder>:<uid>[,<uid>...]}, the
   * folder key itself possibly holding a colon ({@code CUSTOM:12:4242,4243}) -- into
   * references, one per UID. A draft key, or anything that is not one, refuses the whole
   * selection: the list never offers a draft for export.
   *
   * @param selection the keys
   * @return the references, in the order given
   * @throws IllegalArgumentException {@link #EXPORT_INVALID_SELECTION}
   */
  static List<RawEmailRef> parseSelection(List<String> selection) {
    if (selection == null || selection.isEmpty()) {
      throw new IllegalArgumentException(EXPORT_INVALID_SELECTION);
    }
    List<RawEmailRef> refs = new ArrayList<>();
    for (String key : selection) {
      int separator = key == null ? -1 : key.lastIndexOf(':');
      if (separator <= 0 || separator == key.length() - 1) {
        throw new IllegalArgumentException(EXPORT_INVALID_SELECTION);
      }
      String folder = key.substring(0, separator);
      for (String id : key.substring(separator + 1).split(",", -1)) {
        long uid;
        try {
          uid = Long.parseLong(id);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(EXPORT_INVALID_SELECTION);
        }
        if (uid <= 0) {
          throw new IllegalArgumentException(EXPORT_INVALID_SELECTION);
        }
        refs.add(new RawEmailRef(folder, uid));
      }
    }
    return refs;
  }

  /**
   * The name of one message's entry in the zip: its subject made safe, a space, the date
   * it was received in UTC, {@code .eml}.
   *
   * @param cached the message's cached row
   * @return the name, before de-duplication
   */
  static String entryName(Email cached) {
    String base = EmailConnectorUtils.safeFileName(cached.getSubject(), EmailConnectorUtils.EML_DEFAULT_NAME, "");
    Date date = cached.getReceivedDate();
    return date == null ? base + ".eml" : base + " " + ENTRY_NAME_DATE.format(date.toInstant()) + ".eml";
  }

  /**
   * A name not yet used in the zip, compared without case as Windows and macOS compare
   * file names: {@code name.eml}, then {@code name (2).eml}, {@code name (3).eml}...
   *
   * @param name the wanted name, ending in {@code .eml}
   * @param used the names already used, lower-cased; the name returned is added
   * @return the unique name
   */
  static String uniqueName(String name, Set<String> used) {
    String candidate = name;
    String stem = StringUtils.removeEnd(name, ".eml");
    for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
      candidate = stem + " (" + n + ").eml";
    }
    return candidate;
  }

  /**
   * The {@code From_} line that opens a message in an mbox, LF included: the envelope
   * sender -- the {@code Return-Path}, else the first {@code From} address, else
   * {@code MAILER-DAEMON}, and never with white space, which would shift the date -- and
   * the date the server received the message, else the one it says it was sent, in UTC.
   *
   * @param message the message
   * @return the line's bytes
   */
  static byte[] fromLine(MimeMessage message) {
    String sender = null;
    Date date = null;
    try {
      String returnPath = message.getHeader("Return-Path", null);
      if (StringUtils.isNotBlank(returnPath)) {
        sender = StringUtils.strip(returnPath.trim(), "<>");
      }
      if (StringUtils.isBlank(sender)) {
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress address) {
          sender = address.getAddress();
        }
      }
      date = message.getReceivedDate();
      if (date == null) {
        date = message.getSentDate();
      }
    } catch (MessagingException | RuntimeException e) {
      LOG.debug("A message's envelope could not be read for its From_ line", e);
    }
    if (StringUtils.isBlank(sender) || !StringUtils.isAsciiPrintable(sender) || StringUtils.containsWhitespace(sender)) {
      sender = MAILER_DAEMON;
    }
    String line = "From " + sender + " " + FROM_LINE_DATE.format((date == null ? new Date(0) : date).toInstant()) + "\n";
    return line.getBytes(StandardCharsets.US_ASCII);
  }

  /**
   * One line of the {@value #NOT_EXPORTED_ENTRY} entry: the message's subject and date,
   * control characters removed so a subject cannot add lines of its own.
   *
   * @param cached the message's cached row
   * @return the line, CRLF included
   */
  static String notExportedLine(Email cached) {
    String subject = StringUtils.defaultString(cached.getSubject()).replaceAll("\\p{Cc}", " ").trim();
    Date date = cached.getReceivedDate();
    return (date == null ? "" : ENTRY_NAME_DATE.format(date.toInstant()) + " UTC  ") + subject + "\r\n";
  }
}
