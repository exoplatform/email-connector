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
package org.exoplatform.emailConnector.service;

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailContactStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;

/**
 * The per-user contact store: browse/search with the letter index the drawer's
 * A–Z rail runs on, manual CRUD with the source-dependent delete rules, and the
 * automatic collection from the user's own cached mail. Privacy is structural:
 * every method takes the acting username and every query is scoped by it —
 * there is no admin view and no cross-user read anywhere in this class.
 */
@Service
public class EmailContactService {

  private static final Log        LOG                         = ExoLogger.getLogger(EmailContactService.class);

  /** Message code answered as a 409 when an address already has a visible row. */
  public static final String      CONTACT_ALREADY_EXISTS      = "emailConnector.contacts.alreadyExists";

  /** Message code answered as a 400 for an unusable address. */
  public static final String      CONTACT_INVALID_EMAIL       = "emailConnector.contacts.invalidEmail";

  /** Message code answered as a 400 for an unknown source filter. */
  public static final String      CONTACT_INVALID_SOURCE      = "emailConnector.contacts.invalidSource";

  /** Message code answered as a 400 when editing a CardDAV row (read-only in v1). */
  public static final String      CONTACT_CARDDAV_READ_ONLY   = "emailConnector.contacts.carddavReadOnly";

  /**
   * Message code answered as a 400 when editing a directory-linked row: its
   * truth lives in the platform profile, not in the stored fallback fields.
   */
  public static final String      CONTACT_DIRECTORY_READ_ONLY = "emailConnector.contacts.directoryReadOnly";

  /**
   * Message code answered as a 400 when a photo is written onto a row whose
   * picture is not ours to own — a directory link, whose avatar is the platform
   * profile's.
   */
  public static final String      CONTACT_PHOTO_NOT_EDITABLE  = "emailConnector.contacts.photoNotEditable";

  /** The chips' "collected" filter value. */
  public static final String      SOURCE_FILTER_COLLECTED     = "collected";

  /** The chips' "address book" filter value: the curated rows (manual + CardDAV). */
  public static final String      SOURCE_FILTER_ADDRESS_BOOK  = "addressBook";

  /** The suggestion window a recipient field gets when it asks for none (limit &lt;= 0). */
  public static final int         SUGGEST_DEFAULT_LIMIT       = 10;

  /** The hard cap on a suggestion window, whatever the caller asks for. */
  public static final int         SUGGEST_MAX_LIMIT           = 25;

  // The one-time backfill marker, per user, in the same settings scope as the rest
  // of the add-on. Its ABSENCE is what routes collection through the backfill: sync
  // groups are skipped until the whole-cache pass ran, so nothing is counted twice.
  private static final String     CONTACTS_BACKFILL_DONE_KEY  = "emailContactsBackfillDone";

  private static final List<String> ADDRESS_BOOK_SOURCES      = List.of(EmailContactSource.MANUAL, EmailContactSource.CARDDAV);

  @Autowired
  private EmailContactStorage     emailContactStorage;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private SettingService          settingService;

  // Resolves DIRECTORY rows' display fields from the platform profile at read
  // time; identity lookups by username ride the social identity cache, which is
  // what makes per-row resolution affordable on a list page.
  @Autowired
  private IdentityManager         identityManager;

  /**
   * One page of the user's visible contacts, sorted alphabetically, with the
   * letter-index map and the filtered total. "All" (no source) means the whole
   * local store — every row is the user's own; the platform directory is never
   * browsed, only imported from, so "my contacts" is unambiguously true.
   * Directory-linked rows come out resolved: their name, avatar and address are
   * read live from the platform profile, the stored fields being only the
   * fallback for a profile that is gone.
   *
   * @param username the store owner
   * @param source null/"all" for the whole store, "collected", or "addressBook"
   * @param query free text matched against names and addresses, or null
   * @param offset row offset, a multiple of limit
   * @param limit page size
   * @return the page, never null
   */
  public EmailContactPage getContacts(String username, String source, String query, int offset, int limit) {
    EmailContactPage page =
                          emailContactStorage.getContacts(username,
                                                          resolveSources(source),
                                                          query,
                                                          Math.max(offset, 0),
                                                          sanitizeLimit(limit));
    if (page != null && page.getContacts() != null) {
      page.getContacts().forEach(contact -> {
        resolveDirectoryContact(contact);
        applyStoredPhoto(contact);
      });
    }
    return page;
  }

  /**
   * One visible contact by id, enriched at read time with the platform profile
   * when the address is a colleague's — the avatar is about recognizing the
   * person in this platform, so the directory wins on it. Answers null for a
   * missing row, a suppressed row, or somebody else's row alike: an id must
   * never reveal whether it exists in another user's store.
   *
   * @param id the contact id
   * @param username the store owner
   * @return the contact, or null when there is nothing this user can see
   */
  public EmailContact getContact(long id, String username) {
    EmailContact contact = emailContactStorage.getContactById(id);
    if (contact == null || !StringUtils.equals(contact.getUserId(), username) || contact.isSuppressed()) {
      return null;
    }
    enrichForDisplay(contact);
    return contact;
  }

  /**
   * The compose field's type-ahead: one ranked, de-duplicated recipient list
   * merging the caller's own contact store with the platform's people
   * directory, so the field is useful on the very first mail a user writes —
   * before their store has collected anybody.
   * <p>
   * <b>Ranking</b>, in one sentence: the store first, ordered by usefulness
   * (see {@code EmailContactStorage.SUGGEST_SORT} — most-corresponded-with
   * first, then most recent, then alphabetical), and the directory-only
   * matches after it, in the directory's own name order. Store rows lead
   * because they encode this user's own history, which no directory can know;
   * within them, an address the user has written to twenty times outranks an
   * address they were once cc'd on, which is what {@code SEEN_COUNT} is for.
   * <p>
   * <b>A query that IS an address leads the answer</b>, resolved to the person
   * reachable there rather than left to the two name-based halves — see
   * {@link #appendExactAddressMatch(String, String, Map)} for why neither of
   * them could answer it.
   * <p>
   * <b>De-duplication</b> is by normalized address, and the store wins: a
   * colleague who is also a collected contact appears once, as their contact
   * row, with the platform profile supplying the live name, avatar and profile
   * link through the same read-time enrichment every other contact read uses.
   * <p>
   * <b>A blank term answers the top of the store</b> rather than nothing: the
   * field is most often opened to write to somebody written to before, and
   * offering those few immediately is the whole value of having counters. It
   * cannot degenerate into "list everyone" — the same {@code limit} caps it,
   * and the directory is not queried at all without a term (a directory query
   * with no term is the company address book, which this endpoint has no
   * business dumping into a type-ahead).
   * <p>
   * Accepted cost: enriching each store row resolves its platform profile by
   * address, one lookup per answered row. It is what makes a colleague's face
   * appear next to a merely-collected address, and the hard cap on
   * {@code limit} is what keeps a keystroke's worth of them affordable.
   *
   * @param username the store owner and the acting user, never suggested to
   *          themselves
   * @param query what the user typed, or null/blank for their top contacts
   * @param limit how many suggestions to answer, clamped to a sane window
   * @return the ranked suggestions, never null
   */
  public List<EmailContactSuggestion> suggestRecipients(String username, String query, int limit) {
    int size = sanitizeSuggestLimit(limit);
    Map<String, EmailContactSuggestion> byAddress = new LinkedHashMap<>();
    appendExactAddressMatch(username, query, byAddress);
    // Same window guard as the directory half below: an exact match that already
    // filled the window (the compose field's chip resolution asks for exactly one)
    // makes the store's LIKE a query whose answer could not be shown.
    if (byAddress.size() < size) {
      for (EmailContact contact : emailContactStorage.suggestContacts(username, query, size)) {
        // Enrich before keying, never after: for a DIRECTORY row enrichment
        // replaces the stored address with the live profile one, so keying on the
        // stored value would file the entry under an address the emitted
        // suggestion no longer carries. appendDirectoryMatches then looks the same
        // person up by their live address, misses, and adds them a second time.
        enrichForDisplay(contact);
        String address = EmailContactUtils.normalizeAddress(contact.getPrimaryEmail());
        if (address == null || byAddress.containsKey(address)) {
          continue;
        }
        byAddress.put(address, toSuggestion(contact));
      }
    }
    if (StringUtils.isNotBlank(query) && byAddress.size() < size) {
      appendDirectoryMatches(username, query.trim(), size, byAddress);
    }
    return byAddress.values().stream().limit(size).toList();
  }

  /**
   * Resolves a query that IS an address to the person reachable there, ahead of
   * any name matching.
   * <p>
   * Neither of the two name-based halves can answer this. The store's is a LIKE
   * over the name and address columns, so it finds a colleague already collected
   * and nobody else; the directory's searches names only, the way the platform's
   * own people search does. The address of a colleague the user has never
   * written to therefore matched nothing at all, the compose field fell back to
   * committing a bare address, and the recipient stayed an address where the
   * platform knew the person — EXO-89250.
   * <p>
   * It leads the list because an exact address is the least ambiguous thing a
   * user can type: having typed the whole of it, that is who they meant. The
   * store is consulted first for the same reason it wins the de-duplication
   * everywhere else — a row carries this user's own name for the person, and
   * their own picture for them, both of which outrank the directory's.
   * <p>
   * Gated on {@link EmailContactUtils#isCompleteAddress(String)} rather than on
   * the mere presence of an {@code @}: the directory half costs an organisation
   * query, and a domain still being typed would spend one per keystroke.
   *
   * @param username the acting user, never suggested to themselves
   * @param query the raw typed text
   * @param byAddress the merged list so far, mutated in place
   */
  private void appendExactAddressMatch(String username, String query, Map<String, EmailContactSuggestion> byAddress) {
    String address = EmailContactUtils.normalizeAddress(query);
    if (address == null || StringUtils.isBlank(username) || !EmailContactUtils.isCompleteAddress(address)) {
      return;
    }
    EmailContact stored = emailContactStorage.getContactByAddress(username, address);
    if (stored != null && !stored.isSuppressed()) {
      enrichForDisplay(stored);
      EmailContactSuggestion suggestion = toSuggestion(stored);
      // A directory-linked row whose owner has since hidden their profile address
      // comes back blank from the enrichment -- "hidden means hidden" is a property
      // of that chokepoint. Answering it anyway would offer a chip that addresses
      // nobody, so the query goes unanswered and the address the user typed stands
      // as they typed it, unnamed. Falling through to the directory here would
      // resolve the same person and hand back the address its owner just hid.
      if (suggestion.getAddress() != null) {
        // Keyed by the suggestion's OWN address, not the typed one: the enrichment
        // may have rewritten a directory row's address to the live profile's, and
        // the store loop keys by that same normalized primaryEmail -- filing this
        // entry under the typed address would let the loop answer the person twice.
        byAddress.put(suggestion.getAddress(), suggestion);
      }
      return;
    }
    Profile profile = EmailConnectorUtils.getUserProfileByEmail(address);
    if (profile == null) {
      return;
    }
    // Dropped for the same reason the directory half drops it: the store never
    // holds its owner either, so suggesting yourself would be the one
    // inconsistency between the halves.
    String platformUsername = profile.getIdentity() == null ? null : profile.getIdentity().getRemoteId();
    if (StringUtils.isBlank(platformUsername) || StringUtils.equals(platformUsername, username)) {
      return;
    }
    byAddress.put(address,
                  new EmailContactSuggestion(address, profile.getFullName(), profile.getAvatarUrl(), true, profile.getUrl()));
  }

  /**
   * Appends the platform users matching the term whose address the store did
   * not already answer — the half of the list that makes an empty store usable.
   * The directory is searched by name only (which is how the platform's own
   * people search behaves), and the acting user is dropped from it: the store
   * never holds its owner either, so suggesting yourself would be the one
   * inconsistency between the two halves.
   *
   * @param username the acting user, excluded from the results
   * @param term the typed text, already trimmed and known non-blank
   * @param size how many suggestions the answer holds in all
   * @param byAddress the merged list so far, mutated in place
   */
  private void appendDirectoryMatches(String username, String term, int size, Map<String, EmailContactSuggestion> byAddress) {
    for (Identity identity : searchDirectory(term, size)) {
      if (byAddress.size() >= size) {
        return;
      }
      String platformUsername = identity == null ? null : identity.getRemoteId();
      if (StringUtils.isBlank(platformUsername) || StringUtils.equals(platformUsername, username)) {
        continue;
      }
      Profile profile = getDirectoryProfile(platformUsername);
      String address = profile == null ? null : EmailContactUtils.normalizeAddress(profile.getEmail());
      if (address == null || byAddress.containsKey(address)) {
        continue;
      }
      byAddress.put(address,
                    new EmailContactSuggestion(address, profile.getFullName(), profile.getAvatarUrl(), true, profile.getUrl()));
    }
  }

  /**
   * The platform users whose name matches the term, or an empty list when the
   * directory cannot be reached. A failing directory must not fail the whole
   * suggestion: the store's own answer is still perfectly usable, and a
   * recipient field that errors out mid-typing is worse than one that offers
   * fewer names.
   *
   * @param term the typed text
   * @param limit how many identities to ask for
   * @return the matching identities, never null
   */
  private List<Identity> searchDirectory(String term, int limit) {
    try {
      ProfileFilter filter = new ProfileFilter();
      filter.setName(term);
      List<Identity> identities = identityManager.getIdentitiesByProfileFilter(OrganizationIdentityProvider.NAME, filter, 0, limit);
      return identities == null ? List.of() : identities;
    } catch (Exception e) {
      LOG.debug("Could not search the platform directory for recipient suggestions matching {}", term, e);
      return List.of();
    }
  }

  /**
   * Narrows an enriched contact to what a recipient chip renders. A contact is
   * reported as a platform user exactly when the read-time enrichment found a
   * profile behind its address, which is the same fact its profile link
   * expresses — so the two can never disagree.
   *
   * @param contact the enriched store row
   * @return the suggestion
   */
  private EmailContactSuggestion toSuggestion(EmailContact contact) {
    return new EmailContactSuggestion(EmailContactUtils.normalizeAddress(contact.getPrimaryEmail()),
                                      contact.getDisplayName(),
                                      contact.getAvatarUrl(),
                                      StringUtils.isNotBlank(contact.getProfileUrl()),
                                      contact.getProfileUrl());
  }

  /**
   * The one read-time enrichment of a stored contact, shared by every read that
   * shows a person rather than a row: a directory-linked contact resolves
   * through its identity link (exact and cheap), any other resolves its
   * platform profile by address, and the picture the user set by hand is
   * applied last so it outranks a profile that merely shares the address.
   *
   * @param contact the contact being answered, mutated in place
   */
  private void enrichForDisplay(EmailContact contact) {
    if (EmailContactSource.DIRECTORY.equals(contact.getSource())) {
      resolveDirectoryContact(contact);
    } else {
      enrichWithProfile(contact);
    }
    applyStoredPhoto(contact);
  }

  /**
   * The photo the user set on one of their own contacts, bytes and mimetype
   * together. Ownership is checked exactly as everywhere else in this class and
   * the same answer — null — covers a missing row, a suppressed one, somebody
   * else's, and one that simply has no photo: a photo URL must not become a way
   * to probe another user's store. Deliberately does NOT go through
   * {@link #getContact}, whose profile enrichment costs a directory query this
   * read has no use for and which every avatar in a list would pay.
   *
   * @param id the contact id
   * @param username the store owner
   * @return the stored photo, or null when this user has no photo to read here
   */
  public FileItem getContactPhoto(long id, String username) {
    EmailContact contact = emailContactStorage.getContactById(id);
    if (contact == null || !StringUtils.equals(contact.getUserId(), username) || contact.isSuppressed()) {
      return null;
    }
    return emailContactStorage.getPhotoFileItem(contact.getPhotoFileId());
  }

  /**
   * Creates a manual contact. When the address already carries a SUPPRESSED row
   * — a collected contact the user deleted, then later chose to add by hand —
   * the row is un-suppressed and updated from what they typed, and the call
   * behaves as a normal create: answering "already exists" about a row the user
   * cannot see would be a dead end. A VISIBLE row with the same address is a
   * real conflict.
   *
   * A {@code photoUploadId} on the body sets the new contact's picture in the
   * same round-trip, per the three-state contract documented on the field.
   *
   * @param contact what the user typed (names, address, phone, organization)
   * @param username the store owner
   * @return the created (or revived) contact
   * @throws IllegalArgumentException with {@link #CONTACT_INVALID_EMAIL} for an
   *           unusable address
   * @throws IllegalStateException with {@link #CONTACT_ALREADY_EXISTS} when a
   *           visible row already carries the address
   */
  public EmailContact createContact(EmailContact contact, String username) {
    String address = EmailContactUtils.normalizeAddress(contact == null ? null : contact.getPrimaryEmail());
    if (address == null) {
      throw new IllegalArgumentException(CONTACT_INVALID_EMAIL);
    }
    EmailContact existing = emailContactStorage.getContactByAddress(username, address);
    if (existing != null && !existing.isSuppressed()) {
      throw new IllegalStateException(CONTACT_ALREADY_EXISTS);
    }
    if (existing != null) {
      // The tombstone comes back to life as what the user just authored.
      applyAuthoredFields(existing, contact);
      existing.setSource(EmailContactSource.MANUAL);
      existing.setSuppressed(false);
      applyPhoto(existing, contact.getPhotoUploadId());
      return answer(emailContactStorage.updateContact(existing));
    }
    EmailContact created = new EmailContact();
    created.setUserId(username);
    created.setSource(EmailContactSource.MANUAL);
    created.setPrimaryEmail(address);
    applyAuthoredFields(created, contact);
    created.setSeenCount(0);
    applyPhoto(created, contact.getPhotoUploadId());
    return answer(emailContactStorage.createContact(created));
  }

  /**
   * Updates a contact the user can see. Manual and collected rows are editable
   * (a collected row keeps its source — collection never overwrites a name a
   * user set, it only backfills empty ones). CardDAV rows are read-only in v1
   * (they would resurrect at the next address book sync), and directory-linked
   * rows are read-only always: their truth is the platform profile, and editing
   * the stored fallback would change nothing the user sees. Changing the
   * address re-checks uniqueness; when the new address belongs to a suppressed
   * tombstone, the tombstone is absorbed rather than reported as a conflict the
   * user cannot see. A {@code photoUploadId} on the body sets, replaces or
   * clears the picture, per the three-state contract documented on the field —
   * so one save carries every edit the form made.
   *
   * @param id the contact id
   * @param contact the fields to apply
   * @param username the store owner
   * @return the updated contact, or null when there is nothing this user can see
   * @throws IllegalArgumentException with {@link #CONTACT_INVALID_EMAIL},
   *           {@link #CONTACT_CARDDAV_READ_ONLY},
   *           {@link #CONTACT_DIRECTORY_READ_ONLY} or
   *           {@link #CONTACT_PHOTO_NOT_EDITABLE}
   * @throws IllegalStateException with {@link #CONTACT_ALREADY_EXISTS} when the
   *           new address collides with another visible row
   */
  public EmailContact updateContact(long id, EmailContact contact, String username) {
    EmailContact existing = emailContactStorage.getContactById(id);
    if (existing == null || !StringUtils.equals(existing.getUserId(), username) || existing.isSuppressed()) {
      return null;
    }
    if (EmailContactSource.CARDDAV.equals(existing.getSource())) {
      throw new IllegalArgumentException(CONTACT_CARDDAV_READ_ONLY);
    }
    if (EmailContactSource.DIRECTORY.equals(existing.getSource())) {
      throw new IllegalArgumentException(CONTACT_DIRECTORY_READ_ONLY);
    }
    String address = EmailContactUtils.normalizeAddress(contact == null ? null : contact.getPrimaryEmail());
    if (address == null) {
      throw new IllegalArgumentException(CONTACT_INVALID_EMAIL);
    }
    if (!address.equals(existing.getPrimaryEmail())) {
      EmailContact other = emailContactStorage.getContactByAddress(username, address);
      if (other != null && !other.getId().equals(existing.getId())) {
        if (!other.isSuppressed()) {
          throw new IllegalStateException(CONTACT_ALREADY_EXISTS);
        }
        deleteContactRow(other);
      }
      existing.setPrimaryEmail(address);
    }
    applyAuthoredFields(existing, contact);
    applyPhoto(existing, contact.getPhotoUploadId());
    return answer(emailContactStorage.updateContact(existing));
  }

  /**
   * Deletes a contact, with the rule its source imposes. MANUAL and DIRECTORY
   * rows delete for real — both exist by an explicit user act, so an explicit
   * delete undoes it cleanly (a re-import re-links, and later mail from that
   * person re-collects them as COLLECTED, which is the honest state). A
   * COLLECTED row is SUPPRESSED instead: deleting it outright would only see it
   * re-collected on the next mail from that person, so the tombstone is what
   * makes the removal stick. A CARDDAV row is also suppressed — locally hidden —
   * because a local delete would resurrect at the next sync.
   *
   * @param id the contact id
   * @param username the store owner
   * @return the resulting contact — {@code suppressed=true} says it was hidden,
   *         {@code suppressed=false} says the row is gone — or null when there
   *         was nothing this user could see
   */
  public EmailContact deleteOrSuppressContact(long id, String username) {
    EmailContact existing = emailContactStorage.getContactById(id);
    if (existing == null || !StringUtils.equals(existing.getUserId(), username) || existing.isSuppressed()) {
      return null;
    }
    if (EmailContactSource.MANUAL.equals(existing.getSource()) || EmailContactSource.DIRECTORY.equals(existing.getSource())) {
      deleteContactRow(existing);
      existing.setSuppressed(false);
      return existing;
    }
    existing.setSuppressed(true);
    return emailContactStorage.updateContact(existing);
  }

  /**
   * Un-suppresses a contact — the undo of {@link #deleteOrSuppressContact} on a
   * collected row, wired to the toast shown right after a suppression.
   *
   * @param id the contact id
   * @param username the store owner
   * @return the restored contact, or null when no such row belongs to this user
   */
  public EmailContact restoreContact(long id, String username) {
    EmailContact existing = emailContactStorage.getContactById(id);
    if (existing == null || !StringUtils.equals(existing.getUserId(), username)) {
      return null;
    }
    if (!existing.isSuppressed()) {
      return existing;
    }
    existing.setSuppressed(false);
    return emailContactStorage.updateContact(existing);
  }

  /**
   * Collects contacts from a group of freshly-synced inbox messages — the
   * NEW_EMAILS_SYNCED consumer's delegate. Zero IMAP I/O by construction:
   * everything it reads was just written to the local cache. Skipped entirely
   * until the one-time backfill ran, so the backfill's whole-cache pass (which
   * includes these very messages) does not count anything twice.
   *
   * @param username the mailbox owner
   * @param mailRemoteIds the new messages' IMAP UIDs, INBOX-scoped
   */
  public void collectFromSyncedEmails(String username, List<Long> mailRemoteIds) {
    if (StringUtils.isBlank(username) || mailRemoteIds == null || mailRemoteIds.isEmpty() || !isBackfillDone(username)) {
      return;
    }
    String ownAddress = getOwnAddress(username);
    WrittenTo writtenTo = getWrittenTo(username);
    int collected = 0;
    for (Email email : emailBoxStorage.getContactSourceEmails(username, MailFolder.INBOX, mailRemoteIds)) {
      collected += collectInboxSender(username, email, ownAddress, writtenTo) ? 1 : 0;
    }
    LOG.debug("Contact collection for user {}: {} of {} new inbox messages produced a contact upsert",
              username,
              collected,
              mailRemoteIds.size());
  }

  /**
   * Collects the recipients of a mail the user just sent — the strongest
   * contact signal there is: these are people the user chose to write to. The
   * caller passes To and Cc only; Bcc is deliberately never collected (it is
   * the least relationship-like field). Runs regardless of the backfill flag:
   * a send is an explicit user act, not cache replay, so it cannot be
   * double-counted by the backfill (which reads the SENT folder cache, where
   * this message lands too — the one acceptable overlap, on the side of
   * over-counting the people the user writes to).
   *
   * @param username the sender
   * @param recipients the To and Cc recipients of the sent mail
   */
  public void collectFromSentRecipients(String username, List<EmailRecipient> recipients) {
    if (StringUtils.isBlank(username) || recipients == null || recipients.isEmpty()) {
      return;
    }
    String ownAddress = getOwnAddress(username);
    Date now = new Date();
    for (EmailRecipient recipient : recipients) {
      if (recipient != null) {
        collect(username, recipient.getName(), recipient.getAddress(), now, ownAddress);
      }
    }
  }

  /**
   * The one-time whole-cache pass: INBOX senders and SENT recipients of every
   * cached message, through the same rules as live collection, so the user
   * opens the Contacts drawer for the first time and it is already populated.
   * Guarded by a per-user settings flag — not by a row count, which a fast sync
   * group could set before the sent mail was ever read. Pure DB work, bounded
   * by the per-user cache cap.
   *
   * @param username the mailbox owner
   */
  public void backfillFromCacheIfNeeded(String username) {
    if (StringUtils.isBlank(username) || isBackfillDone(username)) {
      return;
    }
    String ownAddress = getOwnAddress(username);
    // Sent mail is read FIRST: everyone the user wrote to is a contact outright, and
    // the domains they wrote to are what the inbox pass then judges senders against.
    // Reading the inbox first would judge it against nothing and collect nobody.
    int sentCollected = 0;
    WrittenTo writtenTo = new WrittenTo();
    List<Email> sentEmails = emailBoxStorage.getContactSourceEmails(username, MailFolder.SENT, null);
    for (Email email : sentEmails) {
      Date seenDate = email.getReceivedDate() == null ? new Date() : email.getReceivedDate();
      for (EmailRecipient recipient : recipientsOf(email)) {
        writtenTo.add(EmailContactUtils.normalizeAddress(recipient.getAddress()));
        sentCollected += collect(username, recipient.getName(), recipient.getAddress(), seenDate, ownAddress) ? 1 : 0;
      }
    }
    int inboxCollected = 0;
    List<Email> inboxEmails = emailBoxStorage.getContactSourceEmails(username, MailFolder.INBOX, null);
    for (Email email : inboxEmails) {
      inboxCollected += collectInboxSender(username, email, ownAddress, writtenTo) ? 1 : 0;
    }
    markBackfillDone(username);
    LOG.info("Contact backfill for user {}: {} inbox messages and {} sent messages read, {} sender upserts, {} recipient upserts",
             username,
             inboxEmails.size(),
             sentEmails.size(),
             inboxCollected,
             sentCollected);
  }

  /**
   * Applies the one inbox message's collection rules and upserts its (original)
   * author when they pass. The rules, in order: an auto-submitted message
   * (RFC 3834, Precedence bulk/junk) was typed by nobody — skip; a message that
   * travelled bulk machinery (List-Unsubscribe) WITHOUT a postable discussion
   * list behind it (no List-Post) is a newsletter blast — skip; a postable list
   * message is colleagues talking — collect the original author
   * (X-Original-Sender) rather than the list address From was rewritten to.
   *
   * @param username the mailbox owner
   * @param email the light cached message
   * @param ownAddress the user's own normalized address, never collected
   * @return true when an upsert happened
   */
  private boolean collectInboxSender(String username, Email email, String ownAddress, WrittenTo writtenTo) {
    if (email == null || email.getSender() == null || email.isAutoSubmitted()
        || (email.isHasListUnsubscribe() && !email.isHasListPost())) {
      return false;
    }
    String name = email.getSender().getName();
    String address = email.getSender().getAddress();
    if (email.isHasListId() && StringUtils.isNotBlank(email.getOriginalSender())) {
      // The list rewrote From to itself; the human is in X-Original-Sender, and the
      // best available name is the "Jane Doe via the-list" prefix, when present.
      address = email.getOriginalSender();
      name = EmailContactUtils.authorNameFromListSender(name);
    }
    // The strongest signal available, and a free one: has the user ever written to
    // this organisation? Someone they have answered is unmistakably a person, while
    // an address that has only ever written AT them is exactly what a transactional
    // sender looks like -- and those are the ones the header rules cannot catch,
    // because a booking confirmation carries no list headers and is not marked
    // auto-submitted. This is a rule about the user's own behaviour rather than a
    // list of words, so it does not need maintaining as senders invent new names.
    // The cost, accepted deliberately: a genuine first-time correspondent is not
    // collected until the user replies to them.
    String normalized = EmailContactUtils.normalizeAddress(address);
    if (!writtenTo.admits(normalized)) {
      return false;
    }
    Date seenDate = email.getReceivedDate() == null ? new Date() : email.getReceivedDate();
    return collect(username, name, address, seenDate, ownAddress);
  }

  /**
   * The domains the user has written to, read from their sent mail.
   * <p>
   * Sent mail is the record of who the user actually deals with, so it is what
   * decides which inbox senders are people. Recomputed per collection run rather
   * than cached: it is one indexed read of a bounded folder, and a stale copy would
   * silently stop collecting the correspondents of somebody the user just started
   * writing to.
   *
   * @param username the mailbox owner
   * @return the lower-cased domains, never null
   */
  private WrittenTo getWrittenTo(String username) {
    WrittenTo writtenTo = new WrittenTo();
    for (Email sent : emailBoxStorage.getContactSourceEmails(username, MailFolder.SENT, null)) {
      for (EmailRecipient recipient : recipientsOf(sent)) {
        writtenTo.add(EmailContactUtils.normalizeAddress(recipient.getAddress()));
      }
    }
    return writtenTo;
  }

  /**
   * Who the user has written to, as the collection rule needs to ask about them:
   * the organisations, and — where the organisation means nothing — the people.
   *
   * At a company domain, having written to anyone there says the user deals with
   * that organisation, and a sender from it is worth keeping. At a consumer mail
   * provider it says nothing at all: one mail to a friend at gmail.com would
   * otherwise admit every gmail.com sender there is. So addresses at those
   * providers are remembered individually and only they are admitted.
   */
  private static final class WrittenTo {

    private final Set<String> domains   = new HashSet<>();

    private final Set<String> addresses = new HashSet<>();

    /**
     * Records one address the user wrote to.
     *
     * @param normalizedAddress the normalized recipient address, may be null
     */
    private void add(String normalizedAddress) {
      String domain = EmailContactUtils.domainOf(normalizedAddress);
      if (domain == null) {
        return;
      }
      if (EmailContactUtils.isFreemailDomain(domain)) {
        addresses.add(normalizedAddress);
      } else {
        domains.add(domain);
      }
    }

    /**
     * Whether a sender at this address has earned collection.
     *
     * @param normalizedAddress the normalized sender address, may be null
     * @return true when the user has written to that organisation, or to that
     *         very person when the organisation is a consumer provider
     */
    private boolean admits(String normalizedAddress) {
      String domain = EmailContactUtils.domainOf(normalizedAddress);
      if (domain == null) {
        return false;
      }
      return EmailContactUtils.isFreemailDomain(domain) ? addresses.contains(normalizedAddress) : domains.contains(domain);
    }
  }

  /**
   * The collection upsert, keyed by the normalized address: a new address
   * becomes a COLLECTED row; a known one bumps its counters; a SUPPRESSED one is
   * left exactly as it is — the user removed it, so collection must not touch it
   * (not even the counters). Names are only ever backfilled into an EMPTY
   * display name of a COLLECTED row: manual and CardDAV names are stronger
   * sources and are never downgraded by whatever a sender's mail client emitted.
   *
   * @param username the store owner
   * @param name the display name as the mail carried it, may be null
   * @param address the raw address
   * @param seenDate when the mail placing this address in front of the user was received
   * @param ownAddress the user's own normalized address, never collected
   * @return true when a row was created or updated
   */
  private boolean collect(String username, String name, String address, Date seenDate, String ownAddress) {
    String normalized = EmailContactUtils.normalizeAddress(address);
    if (normalized == null || normalized.equals(ownAddress) || EmailContactUtils.isNoReplyAddress(normalized)) {
      return false;
    }
    // A "name" that is just the address again is transport noise, not a name.
    String cleanName = StringUtils.isNotBlank(name) && !StringUtils.equalsIgnoreCase(name.trim(), normalized) ? name.trim()
                                                                                                              : null;
    EmailContact existing = emailContactStorage.getContactByAddress(username, normalized);
    if (existing == null) {
      EmailContact created = new EmailContact();
      created.setUserId(username);
      created.setSource(EmailContactSource.COLLECTED);
      created.setPrimaryEmail(normalized);
      created.setDisplayName(cleanName);
      created.setSeenCount(1);
      created.setLastSeenDate(seenDate);
      emailContactStorage.createContact(created);
      return true;
    }
    if (existing.isSuppressed()) {
      return false;
    }
    existing.setSeenCount(existing.getSeenCount() + 1);
    if (existing.getLastSeenDate() == null || (seenDate != null && seenDate.after(existing.getLastSeenDate()))) {
      existing.setLastSeenDate(seenDate);
    }
    if (StringUtils.isBlank(existing.getDisplayName()) && cleanName != null
        && EmailContactSource.COLLECTED.equals(existing.getSource())) {
      existing.setDisplayName(cleanName);
    }
    emailContactStorage.updateContact(existing);
    return true;
  }

  /**
   * Applies a request's photo intent onto the row about to be saved, and owns
   * the rule about whose picture may be written at all: a DIRECTORY row's
   * avatar IS the platform profile's, and a local copy of it would silently
   * drift from the colleague's real one, so the write is refused here rather
   * than only in the UI. (The public entry points already stop directory and
   * CardDAV rows earlier; this guard is what makes the rule a property of the
   * service instead of a property of the order its callers happen to check
   * things in.)
   * <p>
   * The three states of {@code photoUploadId} are the field's documented
   * contract: null keeps, empty clears, anything else replaces. The previous
   * file is always disposed of — replaced in place, or deleted — so a store
   * never accumulates pictures no row points at.
   *
   * @param stored the row being written
   * @param photoUploadId the request's photo intent
   * @throws IllegalArgumentException with {@link #CONTACT_PHOTO_NOT_EDITABLE}
   *           when the row's picture is not this add-on's to own
   */
  private void applyPhoto(EmailContact stored, String photoUploadId) {
    if (photoUploadId == null) {
      return;
    }
    if (EmailContactSource.DIRECTORY.equals(stored.getSource())) {
      throw new IllegalArgumentException(CONTACT_PHOTO_NOT_EDITABLE);
    }
    Long currentFileId = stored.getPhotoFileId();
    if (StringUtils.isBlank(photoUploadId)) {
      emailContactStorage.deletePhotoFile(currentFileId);
      stored.setPhotoFileId(null);
      return;
    }
    stored.setPhotoFileId(emailContactStorage.savePhotoFileItem(currentFileId, photoUploadId));
  }

  /**
   * Points a contact's avatar at the picture the user stored for it, when there
   * is one. Called LAST, after any profile enrichment, because a photo the user
   * chose by hand outranks a profile that merely happens to share the address —
   * and because filling the existing {@code avatarUrl} is what makes the list,
   * the card and the compose autocomplete show it without any of them knowing
   * this feature exists. Versioned on the row's update date rather than on the
   * file id: replacing a photo reuses the file id, so the id alone would leave
   * every client showing the old picture.
   *
   * @param contact the contact being answered, mutated in place
   */
  private void applyStoredPhoto(EmailContact contact) {
    if (contact == null || contact.getPhotoFileId() == null || contact.getPhotoFileId() <= 0
        || EmailContactSource.DIRECTORY.equals(contact.getSource())) {
      return;
    }
    long version = contact.getUpdatedDate() == null ? contact.getPhotoFileId() : contact.getUpdatedDate().getTime();
    contact.setAvatarUrl(String.format("/email-connector/rest/contacts/%s/photo?v=%s", contact.getId(), version));
  }

  /**
   * The stored row as the caller should see it: with its photo URL resolved, so
   * a save answers something the client can render straight away instead of
   * having to re-read.
   *
   * @param contact the saved row
   * @return the same contact, enriched
   */
  private EmailContact answer(EmailContact contact) {
    applyStoredPhoto(contact);
    return contact;
  }

  /**
   * Hard-deletes a row and the picture it owned — the one place a contact
   * disappears for real, so the one place its file can be cleaned up.
   *
   * @param contact the row to remove
   */
  private void deleteContactRow(EmailContact contact) {
    emailContactStorage.deletePhotoFile(contact.getPhotoFileId());
    emailContactStorage.deleteContact(contact.getId());
  }

  /**
   * Copies the user-authored fields of a request body onto a stored contact —
   * and only those: sources, counters and suppression are this service's to
   * manage, never the caller's.
   *
   * @param target the stored contact being written
   * @param authored what the user typed, may be null
   */
  private void applyAuthoredFields(EmailContact target, EmailContact authored) {
    if (authored == null) {
      return;
    }
    target.setGivenName(StringUtils.trimToNull(authored.getGivenName()));
    target.setFamilyName(StringUtils.trimToNull(authored.getFamilyName()));
    target.setPhones(authored.getPhones());
    target.setOrganization(StringUtils.trimToNull(authored.getOrganization()));
    target.setTitle(StringUtils.trimToNull(authored.getTitle()));
    // The display name is not simply overwritten by what the request carries,
    // because a collected contact HAS one and the form does not offer it: the name
    // came from the mail header as a single string, while the form only knows the
    // given/family pair. Saving that form -- to set a picture, say -- used to send
    // both halves empty and erase the name the mail had given, leaving an address
    // where "Amelie Deguerry" had been. So: naming the person explicitly wins,
    // whether as the pair or as the whole; saying nothing about the name leaves it
    // alone.
    String authoredDisplayName = StringUtils.trimToNull(authored.getDisplayName());
    if (StringUtils.isNotBlank(target.getGivenName()) || StringUtils.isNotBlank(target.getFamilyName())) {
      target.setDisplayName(StringUtils.trim(StringUtils.trimToEmpty(target.getGivenName()) + " "
          + StringUtils.trimToEmpty(target.getFamilyName())));
    } else if (authoredDisplayName != null) {
      target.setDisplayName(authoredDisplayName);
    }
  }

  /**
   * The To and Cc recipients of a cached sent message, Bcc deliberately absent —
   * it is not even read from the cache.
   *
   * @param email the light cached message
   * @return the recipients, never null
   */
  private List<EmailRecipient> recipientsOf(Email email) {
    return java.util.stream.Stream.concat(email.getTo() == null ? java.util.stream.Stream.<EmailRecipient> empty()
                                                                : email.getTo().stream(),
                                          email.getCc() == null ? java.util.stream.Stream.<EmailRecipient> empty()
                                                                : email.getCc().stream())
                                  .toList();
  }

  /**
   * The user's own mailbox address, normalized, so collection never files the
   * user in their own contacts. Null when no mailbox is bound.
   *
   * @param username the mailbox owner
   * @return the normalized own address, or null
   */
  private String getOwnAddress(String username) {
    try {
      return EmailContactUtils.normalizeAddress(userEmailSettingService.getUserEmailSetting(username).getEmailAddress());
    } catch (Exception e) {
      LOG.debug("Could not resolve the mailbox address of user {} for contact collection", username, e);
      return null;
    }
  }

  /**
   * Resolves the platform profile behind a contact's address, when there is
   * one, onto the DTO: directory avatar and profile link win at read time (the
   * row itself is never written from the directory).
   *
   * @param contact the contact being answered
   */
  private void enrichWithProfile(EmailContact contact) {
    Profile profile = EmailConnectorUtils.getUserProfileByEmail(contact.getPrimaryEmail());
    if (profile != null) {
      contact.setAvatarUrl(profile.getAvatarUrl());
      contact.setProfileUrl(profile.getUrl());
    }
  }

  /**
   * Resolves a directory-linked row for display: name, avatar, profile link
   * and address are read LIVE from the linked platform profile, overriding the
   * stored fallback — so a renamed colleague shows their current name and a
   * changed address is composed to correctly. When the profile is gone
   * (identity deleted or unknown), the row silently falls back to the stored
   * snapshot: the person the user knew, as last seen. No-op on any other
   * source.
   *
   * @param contact the contact being answered, mutated in place
   */
  private void resolveDirectoryContact(EmailContact contact) {
    if (contact == null || !EmailContactSource.DIRECTORY.equals(contact.getSource())
        || StringUtils.isBlank(contact.getPlatformUsername())) {
      return;
    }
    Profile profile = getDirectoryProfile(contact.getPlatformUsername());
    if (profile == null) {
      return;
    }
    if (StringUtils.isNotBlank(profile.getFullName())) {
      contact.setDisplayName(profile.getFullName());
    }
    contact.setAvatarUrl(profile.getAvatarUrl());
    contact.setProfileUrl(profile.getUrl());
    String liveAddress = EmailContactUtils.normalizeAddress(profile.getEmail());
    if (liveAddress != null) {
      contact.setPrimaryEmail(liveAddress);
    }
  }

  /**
   * The live profile of a platform username, or null when the identity does
   * not exist, is deleted, or cannot be read — the caller's cue to fall back
   * to stored data. Lookups by username ride the social identity cache.
   *
   * @param platformUsername the platform identity
   * @return the profile, or null
   */
  private Profile getDirectoryProfile(String platformUsername) {
    try {
      Identity identity = identityManager.getOrCreateUserIdentity(platformUsername);
      if (identity == null || identity.isDeleted()) {
        return null;
      }
      return identity.getProfile();
    } catch (Exception e) {
      LOG.debug("Could not resolve the platform profile of {}", platformUsername, e);
      return null;
    }
  }

  /**
   * Maps the REST-facing source filter onto the stored discriminators: null/all
   * = the whole local store, "collected" = the collected rows, "addressBook" =
   * the curated rows (manual + CardDAV).
   *
   * @param source the filter value
   * @return the discriminator list, or null for the whole store
   * @throws IllegalArgumentException with {@link #CONTACT_INVALID_SOURCE} for
   *           anything else
   */
  private List<String> resolveSources(String source) {
    if (StringUtils.isBlank(source) || "all".equalsIgnoreCase(source)) {
      return null;
    }
    if (SOURCE_FILTER_COLLECTED.equalsIgnoreCase(source)) {
      return List.of(EmailContactSource.COLLECTED);
    }
    if (SOURCE_FILTER_ADDRESS_BOOK.equalsIgnoreCase(source)) {
      return ADDRESS_BOOK_SOURCES;
    }
    throw new IllegalArgumentException(CONTACT_INVALID_SOURCE);
  }

  /**
   * Clamps the page size to something the drawer actually asks for.
   *
   * @param limit the requested page size
   * @return a size between 1 and 500
   */
  private int sanitizeLimit(int limit) {
    return Math.min(Math.max(limit, 1), 500);
  }

  /**
   * Clamps a suggestion window. The cap is deliberately much tighter than the
   * browse one: a type-ahead is read at a glance, every answered row costs a
   * profile resolution, and the request runs on every keystroke — so an
   * unbounded {@code limit} here would be a way to turn a recipient field into
   * a directory dump.
   *
   * @param limit the requested number of suggestions, zero or less for the default
   * @return a size between 1 and {@link #SUGGEST_MAX_LIMIT}
   */
  private int sanitizeSuggestLimit(int limit) {
    return limit <= 0 ? SUGGEST_DEFAULT_LIMIT : Math.min(limit, SUGGEST_MAX_LIMIT);
  }

  /**
   * Whether the one-time backfill already ran for this user.
   *
   * @param username the mailbox owner
   * @return true when the marker is set
   */
  private boolean isBackfillDone(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                               CONTACTS_BACKFILL_DONE_KEY);
    return value != null && Boolean.parseBoolean(String.valueOf(value.getValue()));
  }

  /**
   * Marks the one-time backfill as done for this user.
   *
   * @param username the mailbox owner
   */
  private void markBackfillDone(String username) {
    settingService.set(Context.USER.id(username),
                       EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                       CONTACTS_BACKFILL_DONE_KEY,
                       SettingValue.create("true"));
  }
}
