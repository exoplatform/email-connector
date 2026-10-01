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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Writes the messages of a selection into a zip, as the mail server hands them over.
 */
class ZipExportVisitor implements RawEmailVisitor {

  private static final Log     LOG           = ExoLogger.getLogger(ZipExportVisitor.class);

  private static final int     OUTPUT_BUFFER = 64 * 1024;

  private final RawEmailSink   sink;

  private final Set<String>    usedNames   = new HashSet<>();

  private final List<String>   notExported = new ArrayList<>();

  private ZipOutputStream      zip;

  /**
   * @param sink where the zip goes
   */
  ZipExportVisitor(RawEmailSink sink) {
    this.sink = sink;
  }

  /**
   * Opens the zip on the sink's stream, buffered.
   *
   * @param count how many messages follow
   * @throws IOException when the stream cannot be opened
   */
  @Override
  public void begin(int count) throws IOException {
    zip = new ZipOutputStream(new BufferedOutputStream(sink.open(null, -1), OUTPUT_BUFFER), StandardCharsets.UTF_8);
    // The report entry's name is taken first, so no message can be named like it.
    usedNames.add(EmailExportService.NOT_EXPORTED_ENTRY);
  }

  /**
   * Writes one message as its own {@code .eml} entry, named after its subject and date.
   *
   * @param cached the message's cached row
   * @param message the message as the server holds it
   * @throws IOException when the output fails
   * @throws MessagingException never; a message the server drops mid-copy is reported
   */
  @Override
  public void message(Email cached, MimeMessage message) throws IOException, MessagingException {
    ZipEntry entry = new ZipEntry(EmailExportService.uniqueName(EmailExportService.entryName(cached), usedNames));
    if (cached.getReceivedDate() != null) {
      entry.setTime(cached.getReceivedDate().getTime());
    }
    zip.putNextEntry(entry);
    try {
      message.writeTo(zip);
    } catch (MessagingException e) {
      // The server dropped the message mid-copy: its entry is cut short, and named in
      // the report as such.
      LOG.debug("A message stopped mid-copy into a zip export", e);
      notExported.add(EmailExportService.notExportedLine(cached));
    } finally {
      zip.closeEntry();
    }
  }

  /**
   * Notes a message the server no longer holds, for the report entry.
   *
   * @param cached the message's cached row
   */
  @Override
  public void missing(Email cached) {
    notExported.add(EmailExportService.notExportedLine(cached));
  }

  /**
   * Writes the report entry when a message could not be exported, and ends the zip.
   *
   * @throws IllegalStateException never; an output failure here is a download the
   *           browser abandoned, logged at debug
   */
  void finish() {
    try {
      if (!notExported.isEmpty()) {
        zip.putNextEntry(new ZipEntry(EmailExportService.NOT_EXPORTED_ENTRY));
        StringBuilder report = new StringBuilder("These emails could not be exported: the mail server no longer holds them.\r\n\r\n");
        notExported.forEach(report::append);
        zip.write(report.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.finish();
      zip.flush();
    } catch (IOException e) {
      LOG.debug("A zip export stopped before its end", e);
    }
  }
}
