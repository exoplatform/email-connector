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

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;

import org.springframework.stereotype.Component;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Puts a message on the wire in the two steps a scheduled send needs to tell apart,
 * where {@code Transport.send} does them as one.
 * <p>
 * What happened before a failure decides what may be done after it, and only the
 * caller of these two steps can know: a failure to CONNECT means the mail server
 * never saw the message, so trying again later cannot deliver it twice; a failure
 * while SENDING may come after the server accepted it, so trying again could. The
 * sequence is exactly {@code Transport.send(message)}'s -- save the changes (which
 * is where a pinned Message-ID is written), take the transport of the first
 * recipient's address type, connect with the session's own authenticator, send to
 * every recipient, close -- so the message that goes out is the one the interactive
 * send would have put out.
 * <p>
 * A component rather than a static helper so a test can count transmissions: the
 * guarantee a scheduled send makes is about how many times this is called.
 */
@Component
public class SmtpTransmitter {

  private static final Log LOG = ExoLogger.getLogger(SmtpTransmitter.class);

  /** Which step a transmission failed in. */
  public enum Phase {
    /** Before any connection: the message could not be finalised. */
    PREPARE,
    /** Opening and authenticating the SMTP session: nothing reached the server. */
    CONNECT,
    /** Once the session is open: the server may have accepted the message. */
    SEND
  }

  /**
   * A failure of one step of a transmission.
   */
  public static class TransmissionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Phase       phase;

    /**
     * @param phase the step that failed
     * @param cause the failure
     */
    public TransmissionException(Phase phase, Exception cause) {
      super(phase + " failed", cause);
      this.phase = phase;
    }

    /**
     * @return the step that failed
     */
    public Phase getPhase() {
      return phase;
    }
  }

  /**
   * Transmits a message: prepare, connect, send, close.
   *
   * @param message the message, built on the session it is to be sent over
   * @throws TransmissionException naming the step that failed
   */
  public void transmit(MimeMessage message) throws TransmissionException {
    Address[] recipients;
    try {
      message.saveChanges();
      recipients = message.getAllRecipients();
    } catch (MessagingException | RuntimeException e) {
      throw new TransmissionException(Phase.PREPARE, e);
    }
    if (recipients == null || recipients.length == 0) {
      throw new TransmissionException(Phase.PREPARE, new SendFailedException("No recipient addresses"));
    }
    Transport transport = null;
    try {
      transport = message.getSession().getTransport(recipients[0]);
      transport.connect();
    } catch (MessagingException | RuntimeException e) {
      closeQuietly(transport);
      throw new TransmissionException(Phase.CONNECT, e);
    }
    try {
      transport.sendMessage(message, recipients);
    } catch (MessagingException | RuntimeException e) {
      throw new TransmissionException(Phase.SEND, e);
    } finally {
      closeQuietly(transport);
    }
  }

  /**
   * Closes a transport, whatever state it is in. A failed QUIT after an accepted
   * message changes nothing the server said, and one after a failure adds nothing.
   *
   * @param transport the transport, may be null
   */
  private void closeQuietly(Transport transport) {
    if (transport == null) {
      return;
    }
    try {
      transport.close();
    } catch (MessagingException | RuntimeException e) {
      LOG.debug("Could not close the SMTP transport cleanly", e);
    }
  }
}
