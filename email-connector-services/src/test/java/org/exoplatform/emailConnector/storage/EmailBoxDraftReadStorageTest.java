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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The READ path over rows that are not copies of delivered mail.
 * <p>
 * A draft is the first row this table has ever held that was written here rather
 * than fetched from a server, and it is therefore the first one that can
 * legitimately lack things every previous row was guaranteed to have: a body, a
 * subject, recipients, a sender, an IMAP UID, a Message-ID. Each of those was an
 * "of course it is there" the mapper was written against, and the first one to be
 * proved wrong took the entire folder listing down with a 500 — one bodyless draft,
 * every message in the folder unreadable.
 * <p>
 * Same rig as {@link EmailBoxDraftStorageTest} — in-memory HSQLDB on the SHIPPED
 * Liquibase changelog, {@code ddl-auto=none}, and the ambient test transaction taken
 * away with {@link Propagation#NOT_SUPPORTED} so the sequence is the one a REST
 * request performs. It matters here for the same reason it mattered there: these are
 * defects of the mapper against a real row, and a mocked DAO hands the mapper
 * whatever the stub was told to return.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxDraftReadStorageTest {

  // One mailbox per test. Nothing rolls back here — taking the ambient transaction
  // away is the whole point of the rig — so a listing assertion would otherwise be
  // counting the rows every earlier test left behind.
  private static final String LISTING_USER    = "alice";

  private static final String EMPTY_DRAFT_USER = "bob";

  private static final String COMMA_NAME_USER  = "carol";

  private static final String NAMELESS_TO_USER = "dave";

  private static final String LISTED_RECIPIENTS_USER = "erin";

  private static final String LEGACY_NULL_NAME_USER = "frank";

  private static final String COMMA_RECIPIENT_USER = "grace";

  private static final String ECHOED_NULL_NAME_USER = "heidi";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private EmailBoxDAO         emailBoxDao;

  @MockBean
  private CategoryLinkService categoryLinkService;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Listing a folder that holds a draft with no body — a subject typed, nothing
   * written yet, which is how most drafts start and how some of them stay.
   * <p>
   * Before the fix this threw {@code NullPointerException: Cannot invoke
   * "String.length()" because "s" is null} out of {@code Jsoup.parse(null)} while
   * building the row's excerpt, and the whole listing came back 500: not the draft
   * missing from the list, the LIST missing. The same mapping serves the cached
   * search and the disconnect cleanup, so one such row broke those too.
   */
  @Test
  void aFolderListingSurvivesADraftWithNoBody() {
    emailBoxStorage.saveDraft(draft(LISTING_USER, "draft-bodyless", "Half a subject", null));
    emailBoxStorage.saveDraft(draft(LISTING_USER, "draft-written", "Something else", "<p>with words in it</p>"));

    List<Email> listed = assertDoesNotThrow(() -> emailBoxStorage.getEmails(LISTING_USER, MailFolder.DRAFTS),
                                            "one bodyless draft must not take the folder listing down with it");

    assertEquals(2, listed.size(), "both drafts belong in the listing, the empty one included");
    Email bodyless = listed.stream().filter(email -> "draft-bodyless".equals(email.getDraftLocalId())).findFirst().orElse(null);
    assertNotNull(bodyless);
    assertTrue(StringUtils.isBlank(bodyless.getContent().getExcerpt()), "an empty body has an empty excerpt, not a failure");
    Email written = listed.stream().filter(email -> "draft-written".equals(email.getDraftLocalId())).findFirst().orElse(null);
    assertNotNull(written);
    assertEquals("with words in it", written.getContent().getExcerpt(), "and a body that has words still reads as its text");
  }

  /**
   * The whole set of absences at once: no body, no subject, no recipients, no
   * sender, no IMAP UID, no Message-ID. A draft written elsewhere and imported can
   * arrive with every one of them missing (half-written mail legitimately has no
   * Subject and no From), and the row is then read on every listing.
   */
  @Test
  void aDraftMissingEverythingIsStillReadable() {
    Date now = new Date();
    Email empty = new Email();
    empty.setUserId(EMPTY_DRAFT_USER);
    empty.setFolder(MailFolder.DRAFTS);
    empty.setDraftLocalId("draft-empty");
    empty.setDraftState(DraftState.LOCAL_ONLY);
    empty.setDraftRevision(1L);
    empty.setDraftUpdatedDate(now);
    empty.setReceivedDate(now);
    empty.setRead(true);
    emailBoxStorage.saveDraft(empty);

    List<Email> listed = assertDoesNotThrow(() -> emailBoxStorage.getEmails(EMPTY_DRAFT_USER, MailFolder.DRAFTS));

    assertEquals(1, listed.size());
    Email read = listed.get(0);
    // The sender is what the list renders each row from, so it has to be answerable
    // even when the column holds nothing at all.
    assertNotNull(read.getSender(), "a row with no sender still has to come back as a row");
    assertTrue(StringUtils.isBlank(read.getSender().getAddress()));
    assertNotNull(emailBoxStorage.getDraftByLocalId(EMPTY_DRAFT_USER, "draft-empty"), "and the composer can still resume it");
  }

  /**
   * A sender whose display name contains a comma — "Doe, Jane", and every platform
   * profile name written that way, which is precisely what a locally-authored draft
   * stamps on itself.
   * <p>
   * The column is {@code name,address} and the mapper split it on the FIRST comma,
   * so such a sender came back addressed to the tail of their own name.
   */
  @Test
  void aSenderWhoseNameHasACommaKeepsItsAddress() {
    Email draft = draft(COMMA_NAME_USER, "draft-comma", "Reporting", "<p>text</p>");
    draft.setSender(new EmailSender("Doe, Jane", "jane@example.org", null, null));
    emailBoxStorage.saveDraft(draft);

    Email read = emailBoxStorage.getDraftByLocalId(COMMA_NAME_USER, "draft-comma");

    assertEquals("jane@example.org", read.getSender().getAddress(), "the address is everything after the LAST comma");
    assertEquals("Doe, Jane", read.getSender().getName());
  }

  /**
   * A recipient with no display name — which is every recipient of every draft, since
   * the composer sends the addresses alone and a half-typed address has no name yet.
   * <p>
   * Their name was stored as the four characters {@code null}, the result of
   * concatenating one, and handed straight back to the reader as what to call the
   * person.
   */
  @Test
  void aRecipientWithNoNameDoesNotComeBackNamedNull() {
    Email draft = draft(NAMELESS_TO_USER, "draft-nameless", "No name here", "<p>text</p>");
    draft.setTo(List.of(new EmailRecipient(null, "bob@example.org", null, false)));
    emailBoxStorage.saveDraft(draft);

    Email read = emailBoxStorage.getDraftByLocalId(NAMELESS_TO_USER, "draft-nameless");

    assertEquals(1, read.getTo().size());
    assertEquals("bob@example.org", read.getTo().get(0).getAddress());
    assertNotEquals("null", read.getTo().get(0).getName(), "a missing name is missing, it is not the word null");
  }

  /**
   * A draft in a folder LISTING comes back with its recipients, where a message in
   * the same listing does not.
   * <p>
   * The listing asks for rows without recipients, and rightly so for mail: a row
   * shows a sender, a subject and an excerpt, and parsing the rest for every message
   * of a folder buys nothing. A draft row is not only rendered though — it is RESUMED
   * from, straight out of the list, and the composer fills its recipient fields from
   * the row it is handed. Handed one without them it showed none, and its first
   * autosave wrote that emptiness back: opening a draft was how it lost the people it
   * was addressed to.
   */
  @Test
  void aDraftInAFolderListingCarriesItsRecipients() {
    Email draft = draft(LISTED_RECIPIENTS_USER, "draft-listed", "Ready to go", "<p>text</p>");
    draft.setCc(List.of(new EmailRecipient("Carol", "carol@example.org", null, false)));
    emailBoxStorage.saveDraft(draft);
    emailBoxStorage.createEmail(mail(LISTED_RECIPIENTS_USER));

    Email listedDraft = emailBoxStorage.getEmails(LISTED_RECIPIENTS_USER, MailFolder.DRAFTS).get(0);
    Email listedMail = emailBoxStorage.getEmails(LISTED_RECIPIENTS_USER, MailFolder.INBOX).get(0);

    assertEquals(1, listedDraft.getTo().size(), "a draft is listed with the people it is addressed to");
    assertEquals("bob@example.org", listedDraft.getTo().get(0).getAddress());
    assertEquals(1, listedDraft.getCc().size());
    assertEquals("carol@example.org", listedDraft.getCc().get(0).getAddress());
    // And nothing else about the listing moved: mail is still listed without them.
    assertNull(listedMail.getTo(), "a message in a listing is still read without its recipients");
  }

  /**
   * A row ALREADY WRITTEN with the word {@code null} where a display name goes —
   * {@code null,veronika@example.org}, read off the owner's own mailbox — comes back
   * with no name rather than with that word as one.
   * <p>
   * The write path stopped producing it, and that was left as the whole fix on the
   * reasoning that each row would be rewritten by its next save. Too optimistic: a
   * draft nobody saves again keeps the word for good, and the composer renders it as
   * the name of the person the mail is addressed to — a chip reading {@code null},
   * which is what the owner reported seeing. Written here through the DAO, because
   * the storage layer can no longer produce such a row and the point is precisely
   * that the ones on disk predate it.
   */
  @Test
  void aStoredNameOfTheWordNullIsReadAsNoNameAtAll() {
    EmailBoxEntity legacy = new EmailBoxEntity();
    legacy.setUserId(LEGACY_NULL_NAME_USER);
    legacy.setFolder(MailFolder.DRAFTS);
    legacy.setDraftLocalId("draft-legacy-null");
    legacy.setDraftState(DraftState.LOCAL_ONLY);
    legacy.setDraftRevision(1L);
    legacy.setDraftUpdatedDate(new Date());
    legacy.setReceivedDate(new Date());
    legacy.setRead(true);
    legacy.setSubject("Re: a reply written before the fix");
    legacy.setBody("<p>text</p>");
    legacy.setSender("null,alice@example.org");
    legacy.setTo("null,veronika@example.org");
    legacy.setCc("null,carol@example.org");
    emailBoxDao.save(legacy);

    Email read = emailBoxStorage.getDraftByLocalId(LEGACY_NULL_NAME_USER, "draft-legacy-null");

    assertNotNull(read);
    assertEquals("veronika@example.org", read.getTo().get(0).getAddress());
    assertEquals("veronika@example.org",
                 read.getTo().get(0).getName(),
                 "a recipient with no name is called by their address, not by the word null");
    assertEquals("carol@example.org", read.getCc().get(0).getName());
    assertEquals("alice@example.org", read.getSender().getName(), "and the same for the row's own sender");
  }

  /**
   * A RECIPIENT whose display name contains a comma keeps their address — the same
   * defect the sender reader was fixed for, on the other reader of the same written
   * form.
   * <p>
   * Both columns are written as {@code name,address}, and the two readers disagreed
   * about where the boundary is: the sender's splits on the last comma, the
   * recipients' had its own split on the FIRST. So "Doe, Jane" came back addressed to
   * the tail of her own name — and a name with a comma in it is exactly what the
   * contacts picker puts in a chip.
   */
  @Test
  void aRecipientWhoseNameHasACommaKeepsTheirAddress() {
    Email draft = draft(COMMA_RECIPIENT_USER, "draft-comma-recipient", "Reporting", "<p>text</p>");
    draft.setTo(List.of(new EmailRecipient("Doe, Jane", "jane@example.org", null, false)));
    emailBoxStorage.saveDraft(draft);

    Email read = emailBoxStorage.getDraftByLocalId(COMMA_RECIPIENT_USER, "draft-comma-recipient");

    assertEquals(1, read.getTo().size());
    assertEquals("jane@example.org", read.getTo().get(0).getAddress(), "the address is everything after the LAST comma");
    assertEquals("Doe, Jane", read.getTo().get(0).getName());
  }

  /**
   * The word is not written back when a client hands it in either.
   * <p>
   * Which it will: the composer fills its chips from the draft it resumed and saves
   * them again on the next keystroke, so a browser holding a page loaded before the
   * fix — or any other client of the draft API — sends back the name it was given. A
   * write path that trusted it would put a fresh {@code null} in the table for every
   * legacy row someone opens, and the fix would never converge.
   */
  @Test
  void theWordNullIsNotWrittenBackWhenAClientSendsIt() {
    Email draft = draft(ECHOED_NULL_NAME_USER, "draft-echoed-null", "Re: something", "<p>text</p>");
    draft.setTo(List.of(new EmailRecipient("null", "veronika@example.org", null, false)));
    emailBoxStorage.saveDraft(draft);

    EmailBoxEntity stored = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(ECHOED_NULL_NAME_USER, "draft-echoed-null")
                                       .get(0);

    assertEquals(",veronika@example.org", stored.getTo(), "a name that is the word null is no name, and is stored as none");
  }

  /**
   * An ordinary cached message, to hold the listing's unchanged half.
   *
   * @param userId the mailbox owner
   * @return the message to write
   */
  private Email mail(String userId) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(MailFolder.INBOX);
    email.setMailRemoteId(1L);
    email.setMailHeaderId("<delivered@example.org>");
    email.setSender(new EmailSender("Bob", "bob@example.org", null, null));
    email.setTo(List.of(new EmailRecipient("Alice", "alice@example.org", null, false)));
    email.setSubject("A delivered message");
    email.setContent(new EmailContent("<p>with a body</p>", null, null));
    email.setReceivedDate(new Date());
    return email;
  }

  /**
   * A draft as the composer saves it, minus whatever the test is about.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param subject the subject typed so far, may be null
   * @param body the text typed so far, may be null
   * @return the draft to write
   */
  private Email draft(String userId, String draftLocalId, String subject, String body) {
    Date now = new Date();
    Email draft = new Email();
    draft.setUserId(userId);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setSender(new EmailSender("Alice", "alice@example.org", null, null));
    draft.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
    draft.setSubject(subject);
    draft.setContent(new EmailContent(body, null, null));
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(now);
    return draft;
  }
}
