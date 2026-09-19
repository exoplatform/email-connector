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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Provider;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two-step transmission against a real JavaMail session whose "smtp" protocol is a
 * counting fake: which step a failure is reported in, and how many times a message
 * reaches the transport. No static mock -- the session resolves the transport the way
 * {@code Transport.send} does, through the first recipient's address type.
 */
public class SmtpTransmitterTest {

  private final SmtpTransmitter transmitter = new SmtpTransmitter();

  /**
   * Resets the fake's counters and behaviour.
   */
  @BeforeEach
  void resetTheFake() {
    CountingTransport.connects = 0;
    CountingTransport.sends = 0;
    CountingTransport.closes = 0;
    CountingTransport.connectFailure = null;
    CountingTransport.sendFailure = null;
  }

  /**
   * A message goes through connect, one send to every recipient, and close; its
   * pinned-by-saveChanges headers are written before the transport sees it.
   *
   * @throws Exception if the fake misbehaves
   */
  @Test
  void aMessageIsConnectedSentOnceAndClosed() throws Exception {
    MimeMessage message = message();
    transmitter.transmit(message);
    assertEquals(1, CountingTransport.connects);
    assertEquals(1, CountingTransport.sends, "sent exactly once");
    assertEquals(1, CountingTransport.closes);
    assertEquals(2, CountingTransport.lastRecipients.length, "to every recipient, To and Cc");
  }

  /**
   * A failure to connect is reported as such, and nothing was sent.
   *
   * @throws Exception if the fake misbehaves
   */
  @Test
  void aConnectFailureIsReportedAsConnectAndSendsNothing() throws Exception {
    CountingTransport.connectFailure = new AuthenticationFailedException("535");
    SmtpTransmitter.TransmissionException failure = assertThrows(SmtpTransmitter.TransmissionException.class,
                                                                 () -> transmitter.transmit(message()));
    assertEquals(SmtpTransmitter.Phase.CONNECT, failure.getPhase());
    assertSame(CountingTransport.connectFailure, failure.getCause());
    assertEquals(0, CountingTransport.sends);
  }

  /**
   * A failure once connected is reported as a send failure, the transport closed
   * anyway.
   *
   * @throws Exception if the fake misbehaves
   */
  @Test
  void aFailureOnceConnectedIsReportedAsSend() throws Exception {
    CountingTransport.sendFailure = new MessagingException("Exception reading response");
    SmtpTransmitter.TransmissionException failure = assertThrows(SmtpTransmitter.TransmissionException.class,
                                                                 () -> transmitter.transmit(message()));
    assertEquals(SmtpTransmitter.Phase.SEND, failure.getPhase());
    assertEquals(1, CountingTransport.sends);
    assertEquals(1, CountingTransport.closes);
  }

  /**
   * A message without a recipient never reaches a transport.
   *
   * @throws Exception if the fake misbehaves
   */
  @Test
  void aMessageWithoutRecipientIsRefusedBeforeConnecting() throws Exception {
    MimeMessage message = new MimeMessage(session());
    message.setText("nobody");
    SmtpTransmitter.TransmissionException failure = assertThrows(SmtpTransmitter.TransmissionException.class,
                                                                 () -> transmitter.transmit(message));
    assertEquals(SmtpTransmitter.Phase.PREPARE, failure.getPhase());
    assertEquals(0, CountingTransport.connects);
  }

  /**
   * A two-recipient message on a session whose smtp protocol is the fake.
   *
   * @return the message
   * @throws MessagingException if it cannot be built
   */
  private MimeMessage message() throws MessagingException {
    MimeMessage message = new MimeMessage(session());
    message.setFrom(new InternetAddress("alice@example.org"));
    message.setRecipient(Message.RecipientType.TO, new InternetAddress("bob@example.org"));
    message.setRecipient(Message.RecipientType.CC, new InternetAddress("carol@example.org"));
    message.setSubject("s");
    message.setText("body");
    return message;
  }

  /**
   * A session whose smtp protocol is {@link CountingTransport}.
   *
   * @return the session
   */
  private Session session() {
    Session session = Session.getInstance(new Properties());
    try {
      session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", CountingTransport.class.getName(), "test", "1"));
    } catch (javax.mail.NoSuchProviderException e) {
      throw new IllegalStateException(e);
    }
    return session;
  }

  /**
   * A transport that counts what it is asked to do and fails on demand.
   */
  public static class CountingTransport extends Transport {

    static int                connects;

    static int                sends;

    static int                closes;

    static Address[]          lastRecipients;

    static MessagingException connectFailure;

    static MessagingException sendFailure;

    /**
     * @param session the session
     * @param urlname the url
     */
    public CountingTransport(Session session, URLName urlname) {
      super(session, urlname);
    }

    /**
     * Counts a connection, or fails it.
     *
     * @return true
     * @throws MessagingException the configured failure
     */
    @Override
    protected boolean protocolConnect(String host, int port, String user, String password) throws MessagingException {
      connects++;
      if (connectFailure != null) {
        throw connectFailure;
      }
      return true;
    }

    /**
     * Counts a send, or fails it after counting (a failure once the data may be out).
     *
     * @throws MessagingException the configured failure
     */
    @Override
    public void sendMessage(Message message, Address[] addresses) throws MessagingException {
      sends++;
      lastRecipients = addresses;
      if (sendFailure != null) {
        throw sendFailure;
      }
    }

    /**
     * Counts a close.
     *
     * @throws MessagingException never
     */
    @Override
    public synchronized void close() throws MessagingException {
      closes++;
      super.close();
    }
  }
}
