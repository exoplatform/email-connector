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

import java.io.IOException;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

import org.exoplatform.emailConnector.model.Email;

/**
 * Where {@code EmailBoxService#readRawEmails} and {@code EmailBoxService#readFolderRawEmails}
 * hand the messages of an export, one by one, as the mail server holds them (EXO-90845).
 * {@link #begin} is called once, after every access check passed and every folder was
 * found, and before any message: a caller answering over HTTP sets its headers there,
 * and every refusal before it keeps a status of its own.
 */
public interface RawEmailVisitor {

  /**
   * The export starts: every check passed.
   *
   * @param count how many messages follow, missing ones included
   * @throws IOException when the output cannot be opened
   */
  void begin(int count) throws IOException;

  /**
   * One message, fetched with PEEK so writing it does not mark it read.
   *
   * @param cached the caller's cached row for it; null for a folder-wide export, which
   *          reads the folder as the server holds it
   * @param message the message as the server holds it
   * @throws IOException when the output fails: the export stops there
   * @throws MessagingException when the message cannot be read
   */
  void message(Email cached, MimeMessage message) throws IOException, MessagingException;

  /**
   * A message the caller has in their cache, but that the mail server no longer holds
   * under that UID, or holds another message under it: it cannot be exported, and the
   * caller must say so rather than leave it out silently.
   *
   * @param cached the caller's cached row
   * @throws IOException when the output fails
   */
  void missing(Email cached) throws IOException;

  /**
   * The mail server failed once the export began, after {@code handed} of {@code count}
   * messages: the file must say it is incomplete, since the answer is under way and
   * cannot change its status. Called for a folder-wide export; a selection reports each
   * message through {@link #missing} instead.
   *
   * @param handed how many messages were handed over whole
   * @param count how many the export was to hold
   * @throws IOException when the output fails
   */
  void interrupted(int handed, int count) throws IOException;
}
