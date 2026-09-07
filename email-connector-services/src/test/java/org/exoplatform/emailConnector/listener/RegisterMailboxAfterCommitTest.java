/*
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.dao.EmailSyncStateDAO;
import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;

/**
 * Pins the transaction propagation of {@code EmailBoxService.registerMailboxForSync}.
 * <p>
 * The method is only ever called from an {@code @TransactionalEventListener(AFTER_COMMIT)},
 * which by definition runs once the transaction that carried the settings write has
 * committed. Writing from there needs a transaction of its own, and only
 * {@link Propagation#REQUIRES_NEW} starts one.
 * <p>
 * This is a mechanism test, deliberately built out of the same parts rather than out
 * of {@code EmailBoxService} itself: that bean pulls in the whole addon, while what
 * broke here is the propagation on an AFTER_COMMIT callback and nothing else. The real
 * storage and the real database are used, so the write either lands or it does not --
 * which is exactly what the shipped {@code EmailBoxSyncListenerTest} cannot see: it
 * mocks the service and so pins that the call is made, never that the row appears.
 * That blind spot is how a fix that does not work reached a merged PR (EXO-90042 ->
 * EXO-90059).
 * <p>
 * {@link Propagation#NOT_SUPPORTED} on the test method is what makes the publishing
 * transaction real: without it the test's own transaction would wrap everything and
 * never commit, so AFTER_COMMIT would never fire.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
@Import({ RegisterMailboxAfterCommitTest.Registrar.class, RegisterMailboxAfterCommitTest.Listener.class,
    RegisterMailboxAfterCommitTest.Publisher.class })
public class RegisterMailboxAfterCommitTest {

  private static final String USER = "afterCommitUser";

  @Autowired
  private EmailSyncStateDAO   emailSyncStateDAO;

  @Autowired
  private Publisher           publisher;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void aMailboxConnectedInACommittedTransactionIsRegisteredForSync() {
    emailSyncStateDAO.deleteAll();

    publisher.connectMailbox(USER);

    EmailSyncStateEntity registered = emailSyncStateDAO.findById(USER).orElse(null);
    assertNotNull(registered,
                  "the AFTER_COMMIT listener's write must land: without a transaction of its own it fails with "
                      + "TransactionRequiredException and the mailbox is never picked up by the dispatcher");
    assertEquals(USER, registered.getUserId());

    emailSyncStateDAO.deleteAll();
  }

  /**
   * Stands in for {@code EmailBoxService.registerMailboxForSync} -- same annotation,
   * same write.
   */
  @Component
  static class Registrar {

    @Autowired
    private EmailSyncStateDAO emailSyncStateDAO;

    /**
     * Writes the sync-state row, in a transaction of its own.
     *
     * @param username the mailbox owner
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerMailboxForSync(String username) {
      EmailSyncStateEntity entity = new EmailSyncStateEntity();
      entity.setUserId(username);
      entity.setCreatedDate(new Date());
      entity.setLastActivityDate(new Date());
      emailSyncStateDAO.save(entity);
    }
  }

  /**
   * Stands in for {@code EmailBoxSyncListener}: the AFTER_COMMIT phase is the whole
   * point of the test.
   */
  @Component
  static class Listener {

    @Autowired
    private Registrar registrar;

    /**
     * Registers the mailbox once the connecting transaction has committed.
     *
     * @param username the mailbox owner, carried by the event
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMailboxConnected(String username) {
      registrar.registerMailboxForSync(username);
    }
  }

  /**
   * Stands in for the settings write that publishes the event.
   */
  @Component
  static class Publisher {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * Publishes the connection event from inside a transaction, so that the
     * AFTER_COMMIT listener fires when it commits.
     *
     * @param username the mailbox owner
     */
    @Transactional
    public void connectMailbox(String username) {
      eventPublisher.publishEvent(username);
    }
  }

  @Configuration
  @EntityScan(basePackageClasses = EmailSyncStateEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailSyncStateDAO.class)
  static class JpaSliceConfiguration {
  }
}
