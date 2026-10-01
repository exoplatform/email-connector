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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import javax.mail.Flags;
import javax.mail.FolderClosedException;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.StoreClosedException;
import javax.mail.internet.MimeMessage;
import javax.mail.util.SharedByteArrayInputStream;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.MailImportRefusal;
import org.exoplatform.emailConnector.model.MailImportState;
import org.exoplatform.emailConnector.utils.MailArchiveSink;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Where the reader hands each mail of a run: counted, checked against the folder's
 * Message-IDs, and appended. A mail without a Message-ID cannot be recognised in the
 * folder; it is recognised within the run by its bytes, so a file holding it twice
 * adds it once.
 */
class MailImportRun implements MailArchiveSink {

  private static final Log      LOG            = ExoLogger.getLogger(MailImportRun.class);

  private final String          username;

  private final MailImportTarget target;

  private final MailImportState state;

  private final Runnable        checkpoint;

  private final Session         session        = Session.getInstance(new Properties());

  private final Set<String>     seenDigests    = new HashSet<>();

  private int                   mails;

  private int                   serverRefusalsInARow;

  private boolean               stopped;

  private boolean               connectionLost;

  /**
   * @param username the user
   * @param target the open folder
   * @param state the run's state, mutated as mails land
   * @param checkpoint stores the state, called every few mails
   */
  MailImportRun(String username, MailImportTarget target, MailImportState state, Runnable checkpoint) {
    this.username = username;
    this.target = target;
    this.state = state;
    this.checkpoint = checkpoint;
  }

  /**
   * Whether the run stopped: a limit, the mail server refusing, the connection lost.
   *
   * @return true once stopped
   */
  boolean isStopped() {
    return stopped;
  }

  /**
   * Whether the connection to the mail server was lost on the way.
   *
   * @return true when it was
   */
  boolean isConnectionLost() {
    return connectionLost;
  }

  /**
   * Adds one mail to the folder, unless the folder holds its Message-ID or the run met
   * it already: parsed (its headers only), given the read state its source says, and
   * appended. A mail that does not parse is refused as not a mail; one the mail server
   * refuses counts as such, and enough of those in a row stop the run.
   *
   * @param message the mail's bytes
   * @return whether to go on reading
   */
  @Override
  public boolean mail(byte[] message) {
    if (!admit()) {
      return false;
    }
    MimeMessage mimeMessage;
    String messageId;
    try {
      mimeMessage = new MimeMessage(session, new SharedByteArrayInputStream(message));
      messageId = mimeMessage.getMessageID();
      mimeMessage.setFlags(new Flags(Flags.Flag.SEEN), isSeen(mimeMessage));
    } catch (MessagingException e) {
      LOG.debug("A mail of the import of user {} could not be parsed", username, e);
      count(MailImportRefusal.NOT_A_MAIL);
      return advance();
    }
    boolean known = StringUtils.isNotBlank(messageId) ? target.contains(messageId) : !seenDigests.add(digest(message));
    if (known) {
      state.setSkipped(state.getSkipped() + 1);
      return advance();
    }
    try {
      target.append(mimeMessage);
      state.setAdded(state.getAdded() + 1);
      serverRefusalsInARow = 0;
      return advance();
    } catch (StoreClosedException | FolderClosedException e) {
      LOG.warn("The connection of user {} closed during a mail import", username, e);
      connectionLost = true;
      stopped = true;
      return false;
    } catch (MessagingException e) {
      LOG.debug("A mail of the import of user {} was refused by the mail server", username, e);
      count(MailImportRefusal.SERVER_REFUSED);
      if (++serverRefusalsInARow >= EmailImportService.SERVER_REFUSALS_TO_STOP) {
        state.setMessageCode(EmailImportService.IMPORT_SERVER_REFUSING);
        stopped = true;
        return false;
      }
      return advance();
    }
  }

  /**
   * Counts a mail the reader refused.
   *
   * @param reason why
   * @return whether to go on reading
   */
  @Override
  public boolean refused(MailImportRefusal reason) {
    if (!admit()) {
      return false;
    }
    count(reason);
    return advance();
  }

  /**
   * Whether one more mail may be read: the mail cap, checked as the mail AFTER the cap
   * arrives, so a file of exactly the cap is not reported cut short.
   *
   * @return false once the cap is reached, the run then stopped
   */
  private boolean admit() {
    if (stopped) {
      return false;
    }
    if (mails >= EmailImportService.MAX_IMPORT_MAILS) {
      state.setMessageCode(EmailImportService.IMPORT_TOO_MANY_MAILS);
      stopped = true;
      return false;
    }
    mails++;
    return true;
  }

  /**
   * Counts one refused mail under its reason.
   *
   * @param reason why
   */
  private void count(MailImportRefusal reason) {
    state.setRefused(state.getRefused() + 1);
    state.getRefusals().merge(reason.name(), 1L, Long::sum);
  }

  /**
   * The per-mail bookkeeping: the periodic stored state the poll's progress and the
   * other nodes' freshness check read.
   *
   * @return true, to go on
   */
  private boolean advance() {
    if (mails % EmailImportService.STATE_WRITE_EVERY == 0) {
      checkpoint.run();
    }
    return true;
  }

  /**
   * Whether the source of a mail says it was read. An mbox written by mutt or mboxo tools
   * carries {@code Status: RO} (R for read); Thunderbird's carries
   * {@code X-Mozilla-Status}, whose lowest bit is "read"; Gmail's Takeout carries
   * {@code X-Gmail-Labels}, where an unread mail is labelled {@code Unread}. A mail
   * carrying none of them is imported as read (see {@code EmailImportService}).
   *
   * @param message the parsed mail
   * @return true to add it with {@code \Seen}
   * @throws MessagingException when its headers cannot be read
   */
  static boolean isSeen(MimeMessage message) throws MessagingException {
    String mozillaStatus = message.getHeader("X-Mozilla-Status", null);
    if (StringUtils.isNotBlank(mozillaStatus)) {
      try {
        return (Integer.parseInt(mozillaStatus.trim(), 16) & 0x0001) != 0;
      } catch (NumberFormatException e) {
        LOG.debug("Unreadable X-Mozilla-Status {}", mozillaStatus, e);
      }
    }
    String status = message.getHeader("Status", null);
    if (status != null) {
      return status.toUpperCase(Locale.ROOT).contains("R");
    }
    String gmailLabels = message.getHeader("X-Gmail-Labels", null);
    if (gmailLabels != null) {
      for (String label : gmailLabels.split(",")) {
        if ("unread".equalsIgnoreCase(label.trim())) {
          return false;
        }
      }
      return true;
    }
    return true;
  }

  /**
   * A mail's SHA-256, to recognise it within one run when it has no Message-ID.
   *
   * @param message the mail's bytes
   * @return the digest in hexadecimal
   */
  private static String digest(byte[] message) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from the JVM", e);
    }
  }
}
