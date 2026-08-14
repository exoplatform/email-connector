/**
 * Copyright (C) 2025 eXo Platform SAS
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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * The two things about a draft row that only a real database can say, on
 * in-memory HSQLDB — the dialect of the dev rig.
 * <p>
 * It runs the SHIPPED Liquibase changelog rather than a schema generated from
 * the entities ({@code ddl-auto=none}, liquibase on), which is the whole point
 * and the difference from {@link EmailContactDAOTest}: the constraint that broke
 * the first save of every new draft ({@code MAIL_REMOTE_ID NOT NULL}) exists
 * only in the changelog. Hibernate would happily generate a nullable column from
 * {@code Long mailRemoteId} and the test would pass against a schema no
 * deployment has.
 * <p>
 * Nothing else in this module writes a row: every service and storage test mocks
 * the DAO, so both of the defects below lived under the mocks, where 724 green
 * tests could not see them.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailBoxDraftDAOTest {

  private static final String USERNAME = "alice";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the mail entities and their repository, over
   * Boot's auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A draft the user has typed but that has not reached the mail server yet: no
   * IMAP UID, because there is no message up there to have one.
   * <p>
   * This is the normal state of the newest thing anybody writes, not an edge
   * case, and against the schema before changeset 1.0.0-41 it fails on
   * {@code integrity constraint violation: NOT NULL check constraint … column:
   * MAIL_REMOTE_ID} — the 500 the composer's very first autosave returned.
   */
  @Test
  void aDraftWithNoServerCopyIsStoredWithNoImapUid() {
    EmailBoxEntity saved = emailBoxDAO.save(localOnlyDraft("draft-1"));
    entityManager.flush();
    entityManager.clear();

    EmailBoxEntity reloaded = emailBoxDAO.findById(saved.getId()).orElseThrow();
    assertNull(reloaded.getMailRemoteId(), "a draft that was never uploaded must not be made to carry a UID");
    assertEquals(MailFolder.DRAFTS, reloaded.getFolder());
    assertEquals(DraftState.LOCAL_ONLY, reloaded.getDraftState());
    assertEquals("draft-1", reloaded.getDraftLocalId());
  }

  /**
   * The same row read back the way every draft path reads it, with the session
   * then gone — which is the state the REST layer serialises in, the platform
   * having set {@code spring.jpa.open-in-view=false}.
   * <p>
   * The clear is what makes this a real test: it detaches everything, so the
   * attachments collection is whatever the query left it, exactly as it is in a
   * request whose transaction has already committed. Without the fetch join the
   * collection comes back uninitialised and the first read of it throws "failed
   * to lazily initialize a collection of role … no Session" — which is what the
   * draft save and the draft read both did.
   */
  @Test
  void aDraftIsReadBackWithItsAttachmentsAlreadyInitialised() {
    emailBoxDAO.save(localOnlyDraft("draft-2"));
    entityManager.flush();
    entityManager.clear();

    List<EmailBoxEntity> found = emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments(USERNAME, "draft-2", MailFolder.DRAFTS);
    assertEquals(1, found.size());
    EmailBoxEntity draft = found.get(0);
    assertTrue(Hibernate.isInitialized(draft.getAttachments()),
               "the draft's attachments must be fetched inside the transaction, or nothing can read them afterwards");
    // Drafts store no attachments today; the collection still has to be there to be
    // read, and an empty lazy bag throws on being touched exactly as a full one does.
    assertNotNull(draft.getAttachments());
    assertEquals(0, draft.getAttachments().size());
  }

  /**
   * A draft as the composer's first save writes it: the user's own text, filed in
   * DRAFTS, with no IMAP UID.
   *
   * @param draftLocalId the composer's handle on the draft
   * @return the row to store
   */
  private EmailBoxEntity localOnlyDraft(String draftLocalId) {
    Date now = new Date();
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(USERNAME);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setSubject("Half a sentence");
    draft.setBody("<p>and no more than that</p>");
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setRecent(false);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(now);
    return draft;
  }
}
