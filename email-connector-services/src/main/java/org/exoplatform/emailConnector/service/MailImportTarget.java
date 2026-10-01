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

import java.util.Set;
import java.util.function.IntConsumer;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang3.StringUtils;

/**
 * A folder of the caller's mailbox, open for an import (EXO-90846), made by
 * {@code EmailBoxService#openImportTarget} once every check passed. It knows the
 * Message-IDs the folder held when it was opened, and learns each one it appends, so a
 * mail already there -- or met twice in the same import -- is told apart before it is
 * sent. Holds the caller's connection until {@link #close()}.
 * <p>
 * Not thread-safe: one import run owns it.
 */
public class MailImportTarget implements AutoCloseable {

  private final Folder      folder;

  private final Set<String> knownMessageIds;

  private final IntConsumer closer;

  private int               appended;

  private boolean           closed;

  /**
   * @param folder the folder, resolved on the caller's own connection
   * @param knownMessageIds the normalised Message-IDs the folder holds, mutable
   * @param closer what closing does, given how many messages were appended
   */
  MailImportTarget(Folder folder, Set<String> knownMessageIds, IntConsumer closer) {
    this.folder = folder;
    this.knownMessageIds = knownMessageIds;
    this.closer = closer;
  }

  /**
   * Whether the folder already holds a message with this Message-ID, compared the way
   * the add-on compares them everywhere ({@code EmailBoxService#sameMessageId}).
   *
   * @param messageId the Message-ID, as the message spells it; blank answers false
   * @return true when a message with that id is there
   */
  public boolean contains(String messageId) {
    String normalized = EmailBoxService.normalizeMessageId(messageId);
    return normalized != null && knownMessageIds.contains(normalized);
  }

  /**
   * Appends one message to the folder, with its flags and its date: IMAP APPEND sends
   * the message's own bytes, its flags ({@code \Recent} cannot be set by a client), and as
   * internal date its received date, else the date of its {@code Date} header -- which,
   * for a message parsed from a file, is the second, so the message sorts where it was
   * sent.
   *
   * @param message the message, parsed from the imported file and never modified
   * @throws MessagingException when the mail server refuses it, or the connection is gone
   */
  public void append(MimeMessage message) throws MessagingException {
    folder.appendMessages(new Message[] { message });
    appended++;
    String normalized = EmailBoxService.normalizeMessageId(message.getMessageID());
    if (StringUtils.isNotBlank(normalized)) {
      knownMessageIds.add(normalized);
    }
  }

  /**
   * How many messages were appended so far.
   *
   * @return the count
   */
  public int getAppended() {
    return appended;
  }

  /**
   * Closes the connection, once, and queues the folder's re-read when mail was added.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      closer.accept(appended);
    }
  }
}
