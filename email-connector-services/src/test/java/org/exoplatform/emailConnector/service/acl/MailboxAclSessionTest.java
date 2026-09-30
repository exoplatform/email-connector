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
package org.exoplatform.emailConnector.service.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import javax.mail.MessagingException;
import javax.mail.Store;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsChannel;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

/**
 * The session a delegation operation runs on: the store is opened once and lazily, a
 * missing transport or credential is a coded refusal, and closing never throws.
 */
class MailboxAclSessionTest {

  private static final String USERNAME = "alice";

  /**
   * The store is opened on first use only, then reused; closing it forgets it, and a
   * second close does nothing.
   */
  @Test
  void theStoreIsOpenedOnceAndClosedOnce() throws Exception {
    Store store = mock(Store.class);
    AtomicInteger opens = new AtomicInteger();
    MailboxAclSession session = new MailboxAclSession(null, USERNAME, "alice@example.org", () -> {
      opens.incrementAndGet();
      return store;
    }, null);
    assertFalse(session.hasOpenStore());
    assertSame(store, session.store());
    assertSame(store, session.store());
    assertEquals(1, opens.get());
    assertTrue(session.hasOpenStore());

    session.close();
    session.close();
    verify(store, times(1)).close();
    assertFalse(session.hasOpenStore());
  }

  /**
   * A failing close is swallowed, and the store is forgotten all the same.
   */
  @Test
  void aFailingCloseIsSwallowed() throws Exception {
    Store store = mock(Store.class);
    doThrow(new MessagingException("gone")).when(store).close();
    MailboxAclSession session = new MailboxAclSession(null, USERNAME, null, () -> store, null);
    session.store();
    session.close();
    assertFalse(session.hasOpenStore());
  }

  /**
   * No IMAP transport is {@link MailboxAclException#NOT_IMAP}; a store that cannot be
   * opened, for a mail or a credentials reason, is
   * {@link MailboxAclException#UNREACHABLE}.
   */
  @Test
  void aStoreThatCannotBeOpenedIsACodedRefusal() {
    MailboxAclSession noTransport = new MailboxAclSession(null, USERNAME, null, null, null);
    assertEquals(MailboxAclException.NOT_IMAP, assertThrows(MailboxAclException.class, noTransport::store).getCode());

    MailboxAclSession mailFailure = new MailboxAclSession(null, USERNAME, null, () -> {
      throw new MessagingException("refused");
    }, null);
    assertEquals(MailboxAclException.UNREACHABLE, assertThrows(MailboxAclException.class, mailFailure::store).getCode());

    MailboxAclSession credentialsFailure = new MailboxAclSession(null, USERNAME, null, () -> {
      throw new ConnectorCredentialsException("no material");
    }, null);
    assertEquals(MailboxAclException.UNREACHABLE,
                 assertThrows(MailboxAclException.class, credentialsFailure::store).getCode());
  }

  /**
   * The HTTP authorization comes from the resolver; no resolver, or one that cannot
   * resolve, is {@link MailboxAclException#UNREACHABLE}.
   */
  @Test
  void theHttpAuthorizationIsResolvedOrRefused() throws Exception {
    MailboxAclSession session = new MailboxAclSession(null, USERNAME, null, null, () -> "Bearer token");
    assertEquals("Bearer token", session.httpAuthorization());
    assertEquals(USERNAME, session.username());

    MailboxAclSession noResolver = new MailboxAclSession(null, USERNAME, null, null, null);
    assertEquals(MailboxAclException.UNREACHABLE,
                 assertThrows(MailboxAclException.class, noResolver::httpAuthorization).getCode());

    MailboxAclSession failing = new MailboxAclSession(null, USERNAME, null, null, () -> {
      throw new ConnectorCredentialsException("no material");
    });
    assertEquals(MailboxAclException.UNREACHABLE, assertThrows(MailboxAclException.class, failing::httpAuthorization).getCode());
  }

  /**
   * A refusal reported through the session reaches its handler for the channel given,
   * and the handler decides whether one more attempt is worth it.
   */
  @Test
  void aRefusalReachesTheHandlerAndItDecidesTheRetry() {
    MailCredentialsRefusal refusal = mock(MailCredentialsRefusal.class);
    when(refusal.retriesAfterRefusal()).thenReturn(true);
    MailboxAclSession session = new MailboxAclSession(null, USERNAME, null, null, null, null, refusal);

    session.invalidateCredentials(ConnectorCredentialsChannel.IMAP);
    verify(refusal).invalidate(ConnectorCredentialsChannel.IMAP);
    assertTrue(session.retriesAfterRefusal());

    when(refusal.retriesAfterRefusal()).thenReturn(false);
    assertFalse(session.retriesAfterRefusal(), "the handler's no is the session's no");
  }

  /**
   * Without the platform's credentials contract, a refusal is reported to nobody and
   * never retried: nothing could renew the material.
   */
  @Test
  void withoutAHandlerARefusalIsNeverRetried() {
    MailboxAclSession session = new MailboxAclSession(null, USERNAME, null, null, null, null);

    session.invalidateCredentials(ConnectorCredentialsChannel.IMAP);
    assertFalse(session.retriesAfterRefusal());
  }
}
