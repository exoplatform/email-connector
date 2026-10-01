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
import java.io.OutputStream;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.exoplatform.emailConnector.exception.ExportInterruptedException;
import org.exoplatform.emailConnector.exception.ExportOutputClosedException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.utils.GuardedOutputStream;
import org.exoplatform.emailConnector.utils.MboxrdOutputStream;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Writes the messages of a folder into an mbox, as the mail server hands them over.
 */
class MboxExportVisitor implements RawEmailVisitor {

  private static final Log     LOG           = ExoLogger.getLogger(MboxExportVisitor.class);

  private static final int     OUTPUT_BUFFER = 64 * 1024;

  private final RawEmailSink   sink;

  private final String         folder;

  private OutputStream         out;

  private MboxrdOutputStream   mbox;

  /**
   * @param sink where the file goes
   * @param folder the folder's name, handed to the sink as the name hint
   */
  MboxExportVisitor(RawEmailSink sink, String folder) {
    this.sink = sink;
    this.folder = folder;
  }

  /**
   * Opens the file on the sink's stream, buffered, behind the mboxrd quoting.
   *
   * @param count how many messages follow
   * @throws IOException when the stream cannot be opened
   */
  @Override
  public void begin(int count) throws IOException {
    out = new BufferedOutputStream(new GuardedOutputStream(sink.open(folder, -1)), OUTPUT_BUFFER);
    mbox = new MboxrdOutputStream(out);
  }

  /**
   * Writes one message: its From_ line, then its lines quoted.
   *
   * @param cached null: a folder export reads the folder, not the cache
   * @param message the message as the server holds it
   * @throws IOException when the output fails ({@link ExportOutputClosedException})
   * @throws MessagingException never
   * @throws ExportInterruptedException when the server fails mid-copy: an mbox has no
   *           place to say a message is cut short, so the download must fail instead
   */
  @Override
  public void message(Email cached, MimeMessage message) throws IOException, MessagingException {
    out.write(EmailExportService.fromLine(message));
    mbox.startMessage();
    try {
      message.writeTo(mbox);
    } catch (ExportOutputClosedException e) {
      throw e;
    } catch (MessagingException | IOException e) {
      throw new ExportInterruptedException("A message stopped mid-copy into an mbox export", e);
    }
    mbox.endMessage();
  }

  /**
   * Nothing: a folder-wide export reads what the folder holds.
   *
   * @param cached unused
   */
  @Override
  public void missing(Email cached) {
    // A folder-wide export reads what the folder holds: nothing is ever missing.
  }

  /**
   * Flushes what is left.
   */
  void finish() {
    try {
      out.flush();
    } catch (IOException e) {
      LOG.debug("An mbox export stopped before its end", e);
    }
  }
}
