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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.exoplatform.social.core.manager.IdentityManager;

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
    if (EmailContactSource.DIRECTORY.equals(contact.getSource())) {
      // A directory-linked row resolves through its identity link — exact and
      // cheap — never through the by-email organization query.
      resolveDirectoryContact(contact);
    } else {
      enrichWithProfile(contact);
    }
    applyStoredPhoto(contact);
    return contact;
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
   * Imports platform colleagues as contacts — as LINKS, not copies: each row
   * stores the platform username, and name/avatar/address resolve live from
   * the profile at every read, so a rename or a departure never rots into a
   * stale snapshot; the profile fields captured here are only the fallback
   * (and the sort key) for a profile that later disappears. Dedupe is by
   * identity first, then by address: importing somebody already collected (or
   * hand-typed, or previously removed) upgrades that one row in place to a
   * directory link, keeping its counters — never a second row for the same
   * person. Unknown usernames and profiles without an address are skipped, not
   * errors: the picker's list may lag the directory.
   *
   * @param platformUsernames the platform usernames to import
   * @param username the store owner
   * @return the imported (created or upgraded) contacts, resolved for display
   */
  public List<EmailContact> importDirectoryContacts(List<String> platformUsernames, String username) {
    List<EmailContact> imported = new ArrayList<>();
    if (platformUsernames == null) {
      return imported;
    }
    for (String platformUsername : new LinkedHashSet<>(platformUsernames)) {
      if (StringUtils.isBlank(platformUsername)) {
        continue;
      }
      Profile profile = getDirectoryProfile(platformUsername.trim());
      if (profile == null) {
        LOG.debug("Directory import for user {} skipped {}: no such platform profile", username, platformUsername);
        continue;
      }
      String address = EmailContactUtils.normalizeAddress(profile.getEmail());
      if (address == null) {
        LOG.debug("Directory import for user {} skipped {}: the profile has no usable email", username, platformUsername);
        continue;
      }
      EmailContact contact = linkDirectoryContact(username, platformUsername.trim(), address, profile);
      resolveDirectoryContact(contact);
      imported.add(contact);
    }
    return imported;
  }

  /**
   * Creates or upgrades the ONE row a platform identity resolves to: looked up
   * by identity link first (its profile email may have changed since a previous
   * import), then by any of its addresses (the same person may already be
   * collected, hand-typed, or suppressed). An existing row is upgraded in place
   * — source, link, fallback name — with its counters kept and its tombstone
   * lifted, an import being as explicit an act as the manual add that revives
   * one.
   *
   * @param username the store owner
   * @param platformUsername the platform identity
   * @param address the profile's normalized address
   * @param profile the resolved profile
   * @return the stored contact
   */
  private EmailContact linkDirectoryContact(String username, String platformUsername, String address, Profile profile) {
    EmailContact existing = emailContactStorage.getContactByPlatformUsername(username, platformUsername);
    if (existing == null) {
      existing = emailContactStorage.getContactByAddress(username, address);
    }
    if (existing == null) {
      EmailContact created = new EmailContact();
      created.setUserId(username);
      created.setSource(EmailContactSource.DIRECTORY);
      created.setPlatformUsername(platformUsername);
      created.setPrimaryEmail(address);
      created.setDisplayName(StringUtils.trimToNull(profile.getFullName()));
      created.setSeenCount(0);
      return emailContactStorage.createContact(created);
    }
    if (EmailContactSource.DIRECTORY.equals(existing.getSource()) && platformUsername.equals(existing.getPlatformUsername())
        && !existing.isSuppressed()) {
      return existing;
    }
    existing.setSource(EmailContactSource.DIRECTORY);
    existing.setPlatformUsername(platformUsername);
    if (StringUtils.isNotBlank(profile.getFullName())) {
      // Refresh the fallback snapshot (and through it the sort key): the live
      // profile is what renders anyway.
      existing.setDisplayName(profile.getFullName().trim());
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
    Set<String> writtenToDomains = getWrittenToDomains(username);
    int collected = 0;
    for (Email email : emailBoxStorage.getContactSourceEmails(username, MailFolder.INBOX, mailRemoteIds)) {
      collected += collectInboxSender(username, email, ownAddress, writtenToDomains) ? 1 : 0;
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
    Set<String> writtenToDomains = new HashSet<>();
    List<Email> sentEmails = emailBoxStorage.getContactSourceEmails(username, MailFolder.SENT, null);
    for (Email email : sentEmails) {
      Date seenDate = email.getReceivedDate() == null ? new Date() : email.getReceivedDate();
      for (EmailRecipient recipient : recipientsOf(email)) {
        String normalized = EmailContactUtils.normalizeAddress(recipient.getAddress());
        String domain = EmailContactUtils.domainOf(normalized);
        if (domain != null) {
          writtenToDomains.add(domain);
        }
        sentCollected += collect(username, recipient.getName(), recipient.getAddress(), seenDate, ownAddress) ? 1 : 0;
      }
    }
    int inboxCollected = 0;
    List<Email> inboxEmails = emailBoxStorage.getContactSourceEmails(username, MailFolder.INBOX, null);
    for (Email email : inboxEmails) {
      inboxCollected += collectInboxSender(username, email, ownAddress, writtenToDomains) ? 1 : 0;
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
  private boolean collectInboxSender(String username, Email email, String ownAddress, Set<String> writtenToDomains) {
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
    String domain = EmailContactUtils.domainOf(EmailContactUtils.normalizeAddress(address));
    if (domain == null || !writtenToDomains.contains(domain)) {
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
  private Set<String> getWrittenToDomains(String username) {
    Set<String> domains = new HashSet<>();
    for (Email sent : emailBoxStorage.getContactSourceEmails(username, MailFolder.SENT, null)) {
      for (EmailRecipient recipient : recipientsOf(sent)) {
        String domain = EmailContactUtils.domainOf(EmailContactUtils.normalizeAddress(recipient.getAddress()));
        if (domain != null) {
          domains.add(domain);
        }
      }
    }
    return domains;
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
    target.setDisplayName(StringUtils.trimToNull(authored.getDisplayName()));
    target.setGivenName(StringUtils.trimToNull(authored.getGivenName()));
    target.setFamilyName(StringUtils.trimToNull(authored.getFamilyName()));
    target.setPhones(authored.getPhones());
    target.setOrganization(StringUtils.trimToNull(authored.getOrganization()));
    target.setTitle(StringUtils.trimToNull(authored.getTitle()));
    if (StringUtils.isBlank(target.getDisplayName())
        && (StringUtils.isNotBlank(target.getGivenName()) || StringUtils.isNotBlank(target.getFamilyName()))) {
      target.setDisplayName(StringUtils.trim(StringUtils.trimToEmpty(target.getGivenName()) + " "
          + StringUtils.trimToEmpty(target.getFamilyName())));
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
