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
package org.exoplatform.emailConnector.utils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.exoplatform.emailConnector.model.MailImportRefusal;

/**
 * Reads the mails out of a file a user uploaded for an import (EXO-90846): one
 * {@code .eml}, a {@code .zip} of them, or an {@code .mbox}. The file is hostile until
 * proven otherwise, so this class only ever reads bytes and checks them: nothing is
 * extracted to disk, rendered, decoded beyond the header block, or executed.
 * <p>
 * The kind is told by the file's first bytes, never by its name: {@code PK} is a zip,
 * {@code From } an mbox, anything else a single message. Every mail handed on is an
 * RFC 822 message by {@link #isRfc822}, within {@code maxMailBytes}, and a zip is read
 * under four guards before and while each entry inflates: its number of entries, each
 * entry's size and compression ratio, and the archive's total inflated size -- what an
 * archive claims about its sizes is never trusted, only the bytes actually read are
 * counted. An entry name that climbs out of the archive is refused unread.
 */
public class MailArchiveReader {

  /** Message code: the zip holds more entries than an import reads. */
  public static final String   ZIP_TOO_MANY_ENTRIES = "emailConnector.import.zipTooManyEntries";

  /** Message code: a zip entry inflates far beyond what it weighs -- a zip bomb. */
  public static final String   ZIP_BOMB             = "emailConnector.import.zipBomb";

  /** Message code: the zip inflates to more than an import reads in total. */
  public static final String   ZIP_TOO_LARGE        = "emailConnector.import.zipTooLarge";

  /** Message code: the file says it is a zip, and is not a readable one. */
  public static final String   ARCHIVE_UNREADABLE   = "emailConnector.import.archiveUnreadable";

  // Below this many inflated bytes an entry is never called a bomb, whatever its ratio:
  // a short text message compresses very well and costs nothing.
  private static final long    RATIO_FLOOR_BYTES    = 1024L * 1024;

  private static final int     READ_CHUNK           = 64 * 1024;

  private static final byte[]  MBOX_SEPARATOR       = "From ".getBytes(StandardCharsets.US_ASCII);

  // RFC 5322 section 3.6.8: a field name is printable US-ASCII but the colon, and the
  // obsolete syntax (section 4.5) allows white space before the colon.
  private static final Pattern HEADER_FIELD         = Pattern.compile("^[\\x21-\\x39\\x3B-\\x7E]+[ \\t]*:.*", Pattern.DOTALL);

  private final int            maxMailBytes;

  private final int            maxHeaderBytes;

  private final int            maxZipEntries;

  private final int            maxZipRatio;

  private final long           maxZipTotalBytes;

  /**
   * @param maxMailBytes the most one mail may weigh
   * @param maxHeaderBytes the most one mail's header block may weigh
   * @param maxZipEntries the most entries a zip may hold, directories included
   * @param maxZipRatio the most an entry may inflate, as a multiple of what it weighs
   * @param maxZipTotalBytes the most a zip may inflate to, all entries together
   */
  public MailArchiveReader(int maxMailBytes, int maxHeaderBytes, int maxZipEntries, int maxZipRatio, long maxZipTotalBytes) {
    this.maxMailBytes = maxMailBytes;
    this.maxHeaderBytes = maxHeaderBytes;
    this.maxZipEntries = maxZipEntries;
    this.maxZipRatio = maxZipRatio;
    this.maxZipTotalBytes = maxZipTotalBytes;
  }

  /**
   * Reads the mails of one uploaded file into the sink.
   *
   * @param file the uploaded file
   * @param sink where each mail and each refusal go
   * @return the message code of what cut the file short (a zip guard), or null when it
   *         was read to its end or the sink stopped it
   * @throws IOException when the file cannot be read
   */
  public String read(File file, MailArchiveSink sink) throws IOException {
    byte[] start = new byte[MBOX_SEPARATOR.length];
    int read;
    try (InputStream in = new FileInputStream(file)) {
      read = in.readNBytes(start, 0, start.length);
    }
    if (read >= 2 && start[0] == 'P' && start[1] == 'K') {
      return readZip(file, sink);
    }
    if (read == MBOX_SEPARATOR.length && startsWith(start, MBOX_SEPARATOR)) {
      try (InputStream in = new BufferedInputStream(new FileInputStream(file), READ_CHUNK)) {
        readMbox(in, sink);
      }
      return null;
    }
    if (file.length() > maxMailBytes) {
      sink.refused(MailImportRefusal.TOO_LARGE);
      return null;
    }
    byte[] message;
    try (InputStream in = new FileInputStream(file)) {
      message = in.readNBytes(maxMailBytes + 1);
    }
    offer(message, sink);
    return null;
  }

  /**
   * Reads an mbox: mboxrd, and the mboxo and mboxcl files Thunderbird and Apple Mail
   * write, which differ only in how they quote. A message starts at a line beginning
   * with {@code From } that is the file's first line or follows an empty line -- a
   * {@code From } line anywhere else is body text, which is how a malformed file that
   * forgot to quote one is still read whole. In the body, one {@code >} is removed from
   * every line reading {@code >+From }, the inverse of mboxrd quoting; the empty line
   * the format puts after each message is removed with it.
   *
   * @param in the file, from its first byte
   * @param sink where each mail and each refusal go
   * @throws IOException when the file cannot be read
   */
  void readMbox(InputStream in, MailArchiveSink sink) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    boolean inMessage = false;
    boolean oversized = false;
    boolean previousBlank = true;
    int length;
    while ((length = readLine(in, line, maxMailBytes + 1)) >= 0) {
      byte[] bytes = line.toByteArray();
      boolean separator = previousBlank && startsWith(bytes, MBOX_SEPARATOR);
      previousBlank = isBlankLine(bytes, length);
      if (separator) {
        if (inMessage && !flushMboxMessage(message, oversized, sink)) {
          return;
        }
        inMessage = true;
        oversized = false;
        message.reset();
        continue;
      }
      if (!inMessage || oversized) {
        continue;
      }
      int offset = isQuotedFrom(bytes) ? 1 : 0;
      if (length > bytes.length || message.size() + bytes.length - offset > maxMailBytes) {
        oversized = true;
        message.reset();
        continue;
      }
      message.write(bytes, offset, bytes.length - offset);
    }
    if (inMessage) {
      flushMboxMessage(message, oversized, sink);
    }
  }

  /**
   * Hands one message of an mbox to the sink, without the empty line that ends it in the
   * file.
   *
   * @param message the message's bytes, separator line excluded
   * @param oversized whether it outgrew the limit on the way
   * @param sink where it goes
   * @return the sink's answer: whether to go on
   */
  private boolean flushMboxMessage(ByteArrayOutputStream message, boolean oversized, MailArchiveSink sink) {
    if (oversized) {
      return sink.refused(MailImportRefusal.TOO_LARGE);
    }
    byte[] bytes = message.toByteArray();
    int end = bytes.length;
    if (end >= 2 && bytes[end - 1] == '\n' && bytes[end - 2] == '\n') {
      end--;
    } else if (end >= 4 && bytes[end - 1] == '\n' && bytes[end - 2] == '\r' && bytes[end - 3] == '\n') {
      end -= 2;
    }
    byte[] trimmed = end == bytes.length ? bytes : Arrays.copyOf(bytes, end);
    return offer(trimmed, sink);
  }

  /**
   * Reads a zip of {@code .eml} files under its guards: the entry count before anything
   * else, then entry by entry the name, the inflated size, the ratio and the running
   * total, counted on the bytes actually inflated. A guard that trips stops the whole
   * archive: an archive built to exhaust the server is not read further for the few
   * mails it might also hold. Directories, and the {@code __MACOSX} and {@code ._}
   * resource entries macOS adds to every zip it makes, are passed over unreported.
   *
   * @param file the zip
   * @param sink where each mail and each refusal go
   * @return the message code of the guard that stopped the archive, or null
   * @throws IOException when the file cannot be read
   */
  private String readZip(File file, MailArchiveSink sink) throws IOException {
    ZipFile zip;
    try {
      zip = new ZipFile(file, StandardCharsets.UTF_8);
    } catch (ZipException e) {
      sink.refused(MailImportRefusal.NOT_A_MAIL);
      return ARCHIVE_UNREADABLE;
    }
    try (zip) {
      if (zip.size() > maxZipEntries) {
        return ZIP_TOO_MANY_ENTRIES;
      }
      long total = 0;
      Set<String> names = new HashSet<>();
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || isMacResource(name)) {
          continue;
        }
        if (!isSafeEntryName(name) || !names.add(name.toLowerCase(Locale.ROOT))) {
          if (!sink.refused(MailImportRefusal.UNSAFE_NAME)) {
            return null;
          }
          continue;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        long inflated = 0;
        boolean oversized = false;
        try (InputStream in = zip.getInputStream(entry)) {
          byte[] chunk = new byte[READ_CHUNK];
          int count;
          while ((count = in.read(chunk)) >= 0) {
            inflated += count;
            total += count;
            if (total > maxZipTotalBytes) {
              return ZIP_TOO_LARGE;
            }
            long compressed = entry.getCompressedSize();
            if (inflated > RATIO_FLOOR_BYTES && (compressed <= 0 || inflated > compressed * maxZipRatio)) {
              return ZIP_BOMB;
            }
            if (inflated > maxMailBytes) {
              oversized = true;
              break;
            }
            content.write(chunk, 0, count);
          }
        } catch (ZipException e) {
          sink.refused(MailImportRefusal.NOT_A_MAIL);
          return ARCHIVE_UNREADABLE;
        }
        boolean goOn = oversized ? sink.refused(MailImportRefusal.TOO_LARGE) : offer(content.toByteArray(), sink);
        if (!goOn) {
          return null;
        }
      }
      return null;
    }
  }

  /**
   * Hands one candidate mail to the sink: as a mail when it is an RFC 822 message within
   * the limits, as a refusal otherwise.
   *
   * @param message the candidate's bytes
   * @param sink where it goes
   * @return the sink's answer: whether to go on
   */
  private boolean offer(byte[] message, MailArchiveSink sink) {
    if (message.length > maxMailBytes) {
      return sink.refused(MailImportRefusal.TOO_LARGE);
    }
    if (headerEnd(message, maxHeaderBytes) < 0 && message.length > maxHeaderBytes) {
      return sink.refused(MailImportRefusal.TOO_LARGE);
    }
    if (!isRfc822(message, maxHeaderBytes)) {
      return sink.refused(MailImportRefusal.NOT_A_MAIL);
    }
    return sink.mail(message);
  }

  /**
   * Whether bytes are an RFC 822 message, read from the header block alone: a header
   * block that ends with an empty line within {@code maxHeaderBytes} (or a message that
   * is a header block only), made of field lines and their folded continuations, with
   * no NUL byte, and holding the two fields RFC 5322 section 3.6 makes mandatory,
   * {@code From} and {@code Date}. The body is never looked at.
   *
   * @param message the candidate's bytes
   * @param maxHeaderBytes the most the header block may weigh
   * @return true for an RFC 822 message
   */
  public static boolean isRfc822(byte[] message, int maxHeaderBytes) {
    int end = headerEnd(message, maxHeaderBytes);
    if (end < 0) {
      if (message.length > maxHeaderBytes) {
        return false;
      }
      end = message.length;
    }
    boolean hasFrom = false;
    boolean hasDate = false;
    int lineStart = 0;
    boolean first = true;
    while (lineStart < end) {
      int lineEnd = lineStart;
      while (lineEnd < end && message[lineEnd] != '\n') {
        if (message[lineEnd] == 0) {
          return false;
        }
        lineEnd++;
      }
      int contentEnd = lineEnd > lineStart && message[lineEnd - 1] == '\r' ? lineEnd - 1 : lineEnd;
      if (contentEnd == lineStart) {
        return false;
      }
      byte firstByte = message[lineStart];
      if (firstByte == ' ' || firstByte == '\t') {
        if (first) {
          return false;
        }
      } else {
        String line = new String(message, lineStart, contentEnd - lineStart, StandardCharsets.ISO_8859_1);
        if (!HEADER_FIELD.matcher(line).matches()) {
          return false;
        }
        String name = line.substring(0, line.indexOf(':')).trim().toLowerCase(Locale.ROOT);
        hasFrom |= "from".equals(name);
        hasDate |= "date".equals(name);
      }
      first = false;
      lineStart = lineEnd + 1;
    }
    return hasFrom && hasDate;
  }

  /**
   * Where a message's header block ends: the index of the line break that starts the
   * empty line after it, looked for within the first {@code limit} bytes.
   *
   * @param message the message's bytes
   * @param limit how far to look
   * @return the index, or -1 when there is no empty line within the limit
   */
  static int headerEnd(byte[] message, int limit) {
    int max = Math.min(message.length, limit);
    for (int i = 0; i < max; i++) {
      if (message[i] != '\n') {
        continue;
      }
      if (i + 1 < message.length && message[i + 1] == '\n') {
        return i + 1;
      }
      if (i + 2 < message.length && message[i + 1] == '\r' && message[i + 2] == '\n') {
        return i + 1;
      }
    }
    return -1;
  }

  /**
   * Reads one line, its LF included, keeping at most {@code keep} of its bytes.
   *
   * @param in the stream
   * @param line where the kept bytes go, reset first
   * @param keep how many bytes to keep at most
   * @return the line's whole length, or -1 at the end of the stream
   * @throws IOException when the stream cannot be read
   */
  private static int readLine(InputStream in, ByteArrayOutputStream line, int keep) throws IOException {
    line.reset();
    int length = 0;
    int b;
    while ((b = in.read()) >= 0) {
      length++;
      if (line.size() < keep) {
        line.write(b);
      }
      if (b == '\n') {
        return length;
      }
    }
    return length == 0 ? -1 : length;
  }

  /**
   * Whether a line reads {@code >+From }: a quoted {@code From } line of an mboxrd body.
   *
   * @param line the line
   * @return true when one {@code >} is to be removed
   */
  private static boolean isQuotedFrom(byte[] line) {
    int quotes = 0;
    while (quotes < line.length && line[quotes] == '>') {
      quotes++;
    }
    if (quotes == 0 || line.length - quotes < MBOX_SEPARATOR.length) {
      return false;
    }
    for (int i = 0; i < MBOX_SEPARATOR.length; i++) {
      if (line[quotes + i] != MBOX_SEPARATOR[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a line is empty but for its line ending.
   *
   * @param line the line's kept bytes
   * @param length its whole length
   * @return true for an empty line
   */
  private static boolean isBlankLine(byte[] line, int length) {
    return length == 1 && line[0] == '\n' || length == 2 && line[0] == '\r' && line[1] == '\n';
  }

  /**
   * Whether bytes start with a prefix.
   *
   * @param bytes the bytes
   * @param prefix the prefix
   * @return true when they do
   */
  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a zip entry is one of the resource entries macOS adds to the zips it makes.
   *
   * @param name the entry's name
   * @return true for {@code __MACOSX/...} and {@code ._name}
   */
  private static boolean isMacResource(String name) {
    String last = name.substring(name.lastIndexOf('/') + 1);
    return name.startsWith("__MACOSX/") || last.startsWith("._");
  }

  /**
   * Whether a zip entry name stays inside the archive: no absolute path, no drive, no
   * backslash, no {@code ..} segment, no control character. Nothing is ever extracted
   * to disk, so such a name harms nothing here; it is refused because no tool a user
   * zips mail with writes one, and an archive that does was made to be hostile.
   *
   * @param name the entry's name
   * @return true for a name that stays inside
   */
  static boolean isSafeEntryName(String name) {
    if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
      return false;
    }
    for (String segment : name.split("/", -1)) {
      if ("..".equals(segment)) {
        return false;
      }
    }
    return name.chars().noneMatch(Character::isISOControl);
  }
}
