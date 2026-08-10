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

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.emailConnector.dao.EmailContactAddressDAO;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactAddressEntity;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.CardDavContactData;
import org.exoplatform.emailConnector.model.CardDavRow;
import org.exoplatform.emailConnector.model.PhotoOrigin;
import org.exoplatform.emailConnector.model.PostalAddress;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

/**
 * Storage of the per-user contact store: converts between the JPA entity and
 * the {@link EmailContact} DTO, derives the sort key and letter bucket at write
 * time, and assembles the paged list with its letter-index map.
 */
@Component
public class EmailContactStorage {

  private static final Log  LOG          = ExoLogger.getLogger(EmailContactStorage.class);

  // The list's one ordering, everywhere: bucket (collation-proof), then sort key,
  // then id as the stable tiebreaker the rail's offset arithmetic needs.
  private static final Sort CONTACT_SORT = Sort.by(Sort.Direction.ASC, "sortBucket", "sortName", "id");

  // The compose field's ordering: usefulness, not alphabet. SEEN_COUNT descending
  // puts the people the user actually corresponds with on top and, because an
  // un-corresponded row counts 0, drops the rest of the store below them without
  // needing a tier expression the databases would each render differently. Recency
  // then breaks ties between equally-frequent correspondents, the sort key breaks
  // those, and the id keeps the window stable when everything else is equal.
  private static final Sort SUGGEST_SORT = Sort.by(Sort.Order.desc("seenCount"),
                                                   Sort.Order.desc("lastSeenDate").nullsLast(),
                                                   Sort.Order.asc("sortName"),
                                                   Sort.Order.asc("id"));

  // Contact photos share the add-on's file namespace with the connector illustrations:
  // one namespace per add-on is what the FileService expects, the name below is what
  // tells the two apart.
  private static final String PHOTO_FILE_NAME = "emailContactPhoto";

  @Autowired
  private EmailContactAddressDAO emailContactAddressDAO;

  @Autowired
  private EmailContactDAO   emailContactDAO;

  @Autowired
  private UploadService     uploadService;

  @Autowired
  private FileService       fileService;

  /**
   * One page of the visible store, with the letter-index map computed over the
   * whole filtered set by one GROUP BY. The offset is floored to a page multiple
   * (browse and the eager-loading drawer only ever ask for multiples; an
   * arbitrary-offset window is the phase-2+ seam, not a phase-1 need).
   *
   * @param userId the store owner
   * @param sources the source discriminators to keep, or null/empty for all
   * @param query the raw filter text, or null
   * @param offset the row offset, expected to be a multiple of limit
   * @param limit the page size
   * @return the page, its ordered letter index and the filtered total
   */
  public EmailContactPage getContacts(String userId, List<String> sources, String query, int offset, int limit) {
    return getContacts(userId, sources, query, null, offset, limit);
  }

  /**
   * The same page restricted to a set of row ids — the Favorites filter, where
   * the ids come from the platform's favorites store. A null id set means no
   * restriction; an EMPTY one is the caller's to short-circuit (the service
   * answers an empty page without coming here), because {@code IN ()} is not a
   * query every database accepts.
   *
   * @param userId the store owner
   * @param sources the source discriminators to keep, or null/empty for all
   * @param query the raw filter text, or null
   * @param ids the row ids to keep, or null for no id restriction
   * @param offset the row offset, expected to be a multiple of limit
   * @param limit the page size
   * @return the page, its ordered letter index and the filtered total
   */
  public EmailContactPage getContacts(String userId, List<String> sources, String query, Set<Long> ids, int offset, int limit) {
    String term = StringUtils.isBlank(query) ? null : query.trim().toLowerCase(Locale.ROOT);
    PageRequest pageRequest = PageRequest.of(limit > 0 ? offset / limit : 0, limit, CONTACT_SORT);
    boolean bySources = sources != null && !sources.isEmpty();
    Page<EmailContactEntity> page;
    List<Object[]> bucketCounts;
    if (ids != null && bySources) {
      page = emailContactDAO.findContactsBySourcesAndIds(userId, sources, ids, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucketAndSourcesAndIds(userId, sources, ids, term);
    } else if (ids != null) {
      page = emailContactDAO.findContactsByIds(userId, ids, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucketAndIds(userId, ids, term);
    } else if (bySources) {
      page = emailContactDAO.findContactsBySources(userId, sources, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucketAndSources(userId, sources, term);
    } else {
      page = emailContactDAO.findContacts(userId, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucket(userId, term);
    }
    List<EmailContact> contacts = page.getContent().stream().map(this::fromEntity).toList();
    return new EmailContactPage(contacts, toLetterIndex(bucketCounts), page.getTotalElements(), offset, limit);
  }

  /**
   * The store's half of the compose field's type-ahead: the best {@code limit}
   * matches by usefulness ({@link #SUGGEST_SORT}), not alphabetically. A blank
   * term ranks the whole visible store, which is what makes an empty field able
   * to offer the user's top correspondents.
   *
   * @param userId the store owner
   * @param query the raw typed text, or null/blank to rank the whole store
   * @param limit how many rows to return, already sanitized by the service
   * @return the ranked rows, never null
   */
  public List<EmailContact> suggestContacts(String userId, String query, int limit) {
    String term = StringUtils.isBlank(query) ? null : query.trim().toLowerCase(Locale.ROOT);
    return emailContactDAO.suggestContacts(userId, term, PageRequest.of(0, limit, SUGGEST_SORT))
                          .stream()
                          .map(this::fromEntity)
                          .toList();
  }

  /**
   * A contact by id, suppressed or not — visibility rules belong to the service.
   *
   * @param id the contact id
   * @return the contact, or null when the row does not exist
   */
  public EmailContact getContactById(long id) {
    return emailContactDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * A contact by any of its addresses — primary first (the natural key), then
   * the secondary addresses — so the same person always resolves to one row.
   * Suppressed rows ARE returned: the upsert needs to see the tombstone to honor
   * it, and manual add needs to see it to un-suppress it.
   *
   * @param userId the store owner
   * @param normalizedAddress the normalized address
   * @return the contact, or null when no row carries the address
   */
  @Transactional
  public EmailContact getContactByAddress(String userId, String normalizedAddress) {
    // One indexed read, against the table that now owns address uniqueness. It
    // finds a contact by ANY of its addresses, where this used to try the primary
    // one and then fall back to a LIKE over a joined string -- which meant the
    // answer depended on which address a contact happened to be filed under.
    EmailContactAddressEntity row = emailContactAddressDAO.findByUserIdAndAddress(userId, normalizedAddress).orElse(null);
    if (row == null) {
      return null;
    }
    EmailContactEntity contact = emailContactDAO.findById(row.getContactId()).orElse(null);
    if (contact == null) {
      // Points at a contact that is gone. Left in place such a row is worse than
      // useless: it hides the address from every lookup while still holding the
      // unique key, so nothing can be found there and nothing can be written there
      // either -- which is how one stale row stopped an entire collection run.
      emailContactAddressDAO.delete(row);
      return null;
    }
    return fromEntity(contact);
  }

  /**
   * Every address a synced card can be reached at, preferred one first.
   *
   * @param data the card as the sync read it
   * @return the addresses, never null
   */
  private List<String> addressesOfCardDav(CardDavContactData data) {
    List<String> addresses = new ArrayList<>();
    if (StringUtils.isNotBlank(data.primaryEmail())) {
      addresses.add(data.primaryEmail());
    }
    if (data.secondaryEmails() != null) {
      addresses.addAll(data.secondaryEmails());
    }
    return addresses;
  }

  /**
   * Writes the addresses a contact can be reached at, replacing whatever was
   * stored.
   * <p>
   * Called on every authored save so the lookup table cannot drift from the
   * contact: an address the user removed from the form must stop resolving to
   * them. The set may be empty — a contact with a phone number and no mail
   * address is ordinary in an address book, and is exactly what the old model
   * could not hold.
   *
   * @param contactId the contact
   * @param userId the store owner
   * @param addresses every address, already normalized, in preference order
   */
  private void saveAddresses(Long contactId, String userId, List<String> addresses) {
    if (contactId == null) {
      return;
    }
    emailContactAddressDAO.deleteByContactId(contactId);
    // Sent to the database before a single row is written back. Both statements sit
    // in one transaction now, and Hibernate orders its own queue -- inserts before
    // deletes -- so re-saving a contact re-inserted addresses it had not deleted
    // yet and collided with itself on the unique key. Every collected contact
    // failed that way, on its own addresses.
    emailContactAddressDAO.flush();
    if (addresses == null || addresses.isEmpty()) {
      return;
    }
    Set<String> written = new LinkedHashSet<>();
    for (String address : addresses) {
      String normalized = EmailContactUtils.normalizeAddress(address);
      if (normalized == null || !written.add(normalized)) {
        continue;
      }
      EmailContactAddressEntity entity = new EmailContactAddressEntity();
      entity.setContactId(contactId);
      entity.setUserId(userId);
      entity.setAddress(normalized);
      emailContactAddressDAO.save(entity);
    }
  }

  /**
   * Adds addresses to a contact without removing any — the address book's path.
   * <p>
   * A union rather than a replacement because the vCard is not the only thing
   * entitled to say how a person can be reached. A refresh that replaced the set
   * would drop every address the card does not carry: today only the sync writes
   * these rows so nothing is lost, but the moment anything else files one — mail
   * collection against a synced contact, an import, a merge — a routine refresh
   * would silently unfile it, and the person would stop resolving at an address
   * they still use.
   * <p>
   * An address another contact already holds is left with them: the unique index
   * is the authority on who owns an address, and a card is not grounds to take
   * one off somebody else.
   *
   * @param contactId the contact
   * @param userId the store owner
   * @param addresses the addresses the card carries, already normalized
   */
  private void mergeAddresses(Long contactId, String userId, List<String> addresses) {
    if (contactId == null || addresses == null || addresses.isEmpty()) {
      return;
    }
    Set<String> filed = new LinkedHashSet<>();
    emailContactAddressDAO.findByContactId(contactId).forEach(row -> filed.add(row.getAddress()));
    for (String address : addresses) {
      String normalized = EmailContactUtils.normalizeAddress(address);
      if (normalized == null || !filed.add(normalized)) {
        continue;
      }
      if (emailContactAddressDAO.findByUserIdAndAddress(userId, normalized).isPresent()) {
        // Somebody else's, and the index would refuse it anyway -- refusing it here
        // keeps one contested address from failing the whole run.
        LOG.debug("Address {} is already filed under another contact of user {} and was left there", normalized, userId);
        continue;
      }
      EmailContactAddressEntity entity = new EmailContactAddressEntity();
      entity.setContactId(contactId);
      entity.setUserId(userId);
      entity.setAddress(normalized);
      emailContactAddressDAO.save(entity);
    }
  }

  /**
   * Every address of a contact, the preferred one first: the one the contact is
   * displayed and written to by, then the rest.
   *
   * @param contact the contact being saved
   * @return the addresses in preference order
   */
  private List<String> addressesOf(EmailContact contact) {
    List<String> addresses = new ArrayList<>();
    if (StringUtils.isNotBlank(contact.getPrimaryEmail())) {
      addresses.add(contact.getPrimaryEmail());
    }
    if (contact.getSecondaryEmails() != null) {
      addresses.addAll(contact.getSecondaryEmails());
    }
    return addresses;
  }

  /**
   * The row already linking a platform identity, if any, suppressed or not —
   * the directory import's first dedupe key, ahead of the address (an
   * identity's profile email may have changed since it was imported).
   *
   * @param userId the store owner
   * @param platformUsername the platform identity
   * @return the linked contact, or null when the identity was never imported
   */
  public EmailContact getContactByPlatformUsername(String userId, String platformUsername) {
    return emailContactDAO.findFirstByUserIdAndPlatformUsername(userId, platformUsername).map(this::fromEntity).orElse(null);
  }

  /**
   * Creates a row from the DTO, deriving the sort key/bucket and stamping the
   * creation date.
   *
   * @param contact the contact to persist
   * @return the persisted contact with its id
   */
  @Transactional
  public EmailContact createContact(EmailContact contact) {
    EmailContactEntity entity = toEntity(contact);
    entity.setId(null);
    entity.setCreatedDate(new Date());
    entity.setUpdatedDate(entity.getCreatedDate());
    EmailContactEntity saved = emailContactDAO.save(entity);
    saveAddresses(saved.getId(), saved.getUserId(), addressesOf(contact));
    return fromEntity(saved);
  }

  /**
   * Updates a row in place from the DTO (the id addresses the row), re-deriving
   * the sort key/bucket and stamping the update date. The creation date is
   * preserved from the stored row.
   *
   * @param contact the contact to persist, with its id set
   * @return the persisted contact
   */
  // A contact and its addresses are saved together, and the addresses are replaced
  // rather than merged -- a delete that JPA refuses outright without a transaction.
  // Callers on a request thread had one; contact collection runs on the event bus's
  // own thread and had none, so collecting from a synced mailbox threw where saving
  // the same contact by hand succeeded.
  @Transactional
  public EmailContact updateContact(EmailContact contact) {
    // Written ONTO the stored row, never rebuilt from the DTO. The DTO does not
    // carry every column -- the CardDAV ones (href, etag, vCard uid, connector)
    // exist only on the entity -- so rebuilding erased whatever it did not know
    // about, and the erasure was silent. Rescuing them one at a time is what the
    // created date already needed; copying only the fields the DTO owns retires
    // that whole class of bug, including for columns nobody has added yet.
    EmailContactEntity entity = emailContactDAO.findById(contact.getId()).orElse(null);
    if (entity == null) {
      entity = toEntity(contact);
    } else {
      applyAuthoredColumns(contact, entity);
    }
    entity.setUpdatedDate(new Date());
    EmailContactEntity saved = emailContactDAO.save(entity);
    saveAddresses(saved.getId(), saved.getUserId(), addressesOf(contact));
    return fromEntity(saved);
  }

  /**
   * Hard-deletes a row and the addresses filed under it — the MANUAL delete
   * path, and the sync's, since collected rows go through the service's
   * suppression instead.
   * <p>
   * The addresses go here rather than through a cascading foreign key: this
   * changelog carries exactly one FK in total, and an orphan-cleaning cascade
   * added now would have to be written for four vendors and applied to stores
   * that already hold orphans. Cleaning in the one method every delete call site
   * funnels through covers the same ground without that.
   * <p>
   * Leaving them behind is not cosmetic. An orphan still holds {@code (USER_ID,
   * ADDRESS)}, so the next contact reaching that address cannot be written at
   * all, and the address book sync reports it as a failed run.
   *
   * @param id the contact id
   */
  @Transactional
  public void deleteContact(long id) {
    emailContactAddressDAO.deleteByContactId(id);
    emailContactDAO.deleteById(id);
  }

  /**
   * Whether the user has any row at all — suppressed included — in the given
   * sources. Drives the one-time backfill guard and the Address book chip's
   * presence probe.
   *
   * @param userId the store owner
   * @param sources the source discriminators to probe
   * @return true when at least one row exists
   */
  public boolean hasContacts(String userId, List<String> sources) {
    return emailContactDAO.countByUserIdAndSourceIn(userId, sources) > 0;
  }

  /**
   * Every row this user holds from one address book, in the shape the sync
   * reasons about. Hidden rows are included: the sync must refresh one, not
   * mistake it for an entry the server dropped.
   *
   * @param userId the store owner
   * @param connectorId the address book's connector
   * @return the rows, never null
   */
  public List<CardDavRow> getCardDavRows(String userId, Long connectorId) {
    return emailContactDAO.findByUserIdAndConnectorId(userId, connectorId).stream().map(this::toCardDavRow).toList();
  }

  /**
   * Every address-book row this user holds, whichever connector wrote it.
   *
   * @param userId the store owner
   * @return the rows, never null
   */
  public List<CardDavRow> getAllCardDavRows(String userId) {
    return emailContactDAO.findByUserIdAndSource(userId, EmailContactSource.CARDDAV).stream().map(this::toCardDavRow).toList();
  }

  /**
   * The row at this address, whatever its source, in the same shape — what the
   * sync consults before deciding whether it may claim an existing contact.
   *
   * @param userId the store owner
   * @param normalizedAddress the lower-cased address
   * @return the row, or null when the user has none at that address
   */
  @Transactional
  public CardDavRow getCardDavRowByAddress(String userId, String normalizedAddress) {
    // Through the address table, like every other lookup by address. Asking
    // EMAIL_CONTACT.PRIMARY_EMAIL instead only ever found a person filed under
    // their preferred address, so a card carrying somebody's secondary one looked
    // like a stranger: the sync created a second row, the unique index refused it,
    // and three such runs left the whole address book BLOCKED with nothing on
    // screen to say why.
    EmailContactAddressEntity row = emailContactAddressDAO.findByUserIdAndAddress(userId, normalizedAddress).orElse(null);
    if (row == null) {
      return null;
    }
    EmailContactEntity contact = emailContactDAO.findById(row.getContactId()).orElse(null);
    if (contact == null) {
      // Same self-heal as the collection path: an address pointing at a contact
      // that is gone holds the unique key hostage, so nothing can be found there
      // and nothing can be written there either.
      emailContactAddressDAO.delete(row);
      return null;
    }
    return toCardDavRow(contact);
  }

  /**
   * Writes an address-book entry onto a row, creating it when there is none.
   * <p>
   * This exists because {@code updateContact} structurally cannot do it: that
   * path copies only the columns the DTO owns, deliberately, so the bookkeeping
   * the sync depends on — which server entry this is, at which version — never
   * travels through the REST model at all.
   * <p>
   * Counters are never touched here. A contact claimed from the collected pile
   * keeps the correspondence history that made it worth having, which is what
   * keeps the compose autocomplete's ranking intact across a sync.
   *
   * @param userId the store owner
   * @param existingId the row to write onto, or null to create one
   * @param connectorId the address book's connector
   * @param href the entry's path on the server
   * @param etag the entry's version
   * @param data the entry's fields
   * @param photoFileId the picture to replace, or null to write a new one
   * @param writePhoto whether the entry's picture may be stored at all
   * @return the row id
   */
  @SneakyThrows
  @Transactional
  public long saveCardDavContact(String userId,
                                 Long existingId,
                                 Long connectorId,
                                 String href,
                                 String etag,
                                 CardDavContactData data,
                                 Long photoFileId,
                                 boolean writePhoto) {
    EmailContactEntity entity = existingId == null ? new EmailContactEntity()
                                                   : emailContactDAO.findById(existingId).orElse(new EmailContactEntity());
    if (entity.getId() == null) {
      entity.setUserId(userId);
      entity.setCreatedDate(new Date());
      entity.setSeenCount(0);
    }
    entity.setSource(EmailContactSource.CARDDAV);
    entity.setPrimaryEmail(data.primaryEmail());
    entity.setEmails(toEmailsString(data.secondaryEmails()));
    entity.setDisplayName(data.displayName());
    entity.setGivenName(data.givenName());
    entity.setFamilyName(data.familyName());
    String sortName = EmailContactUtils.computeSortName(data.givenName(),
                                                        data.familyName(),
                                                        data.displayName(),
                                                        data.primaryEmail());
    entity.setSortName(sortName);
    entity.setSortBucket(EmailContactUtils.sortBucketOf(sortName));
    entity.setPhones(joinValues(data.phones()));
    entity.setOrganization(data.organization());
    entity.setTitle(data.title());
    applyPostalAddress(data.address(), entity);
    entity.setBirthday(data.birthday());
    entity.setNote(EmailContactUtils.truncateNote(data.note()));
    entity.setWebsite(data.website());
    entity.setHref(href);
    entity.setEtag(etag);
    entity.setVcardUid(data.vcardUid());
    entity.setConnectorId(connectorId);
    entity.setUpdatedDate(new Date());
    if (writePhoto) {
      if (data.photo() != null && data.photo().length > 0) {
        entity.setPhotoFileId(savePhotoBytes(photoFileId, data.photo(), data.photoMimeType()));
        entity.setPhotoOrigin(PhotoOrigin.VCARD);
      } else if (photoFileId != null) {
        // The entry lost its picture: so does the row, and so does the file.
        deletePhotoFile(photoFileId);
        entity.setPhotoFileId(null);
        entity.setPhotoOrigin(null);
      }
    }
    EmailContactEntity saved = emailContactDAO.save(entity);
    // The address table, which every lookup by address reads. Missing here, a
    // synced contact could not be found by their own address: mail from them
    // was collected as somebody new, clicking their name in a header offered to
    // create them, and an import of the same address book duplicated the lot.
    // Merged, not replaced -- see mergeAddresses for why the card does not get
    // to be the only voice on how a person is reached.
    mergeAddresses(saved.getId(), saved.getUserId(), addressesOfCardDav(data));
    return saved.getId();
  }

  /**
   * Turns a row the address book no longer has back into a collected contact:
   * the server bookkeeping goes, the person stays.
   * <p>
   * For a contact with correspondence behind it, deleting would throw away
   * history the mailbox earned; the address book losing an entry says nothing
   * about whether the user still writes to them.
   *
   * @param id the row id
   * @param deletePhoto whether the stored picture came from the address book and
   *          should go with it
   */
  /**
   * Turns every collected contact of a user into one they hold themselves,
   * forgetting the correspondence the old mailbox earned.
   * <p>
   * Called when a mailbox is rebound or disconnected. "Collected" means "derived
   * from mail in this mailbox", and that mailbox is gone: the rows would
   * otherwise keep claiming a provenance that no longer exists and a history the
   * app can no longer show. Turning them into MANUAL rows states the only thing
   * still true of them — the user has these people — and follows the rule the
   * address-book release already applies, downgrading provenance rather than
   * deleting what somebody may still want.
   * <p>
   * The counters go to zero rather than travelling: they rank autocomplete, and
   * ranking people by traffic in an account the user no longer has is worse than
   * starting the count again. Suppressed rows are deliberately left alone — a
   * tombstone is a decision to not see somebody, and reviving it here would put
   * them back in the list on the very screen where the user changed accounts.
   *
   * @param userId the store owner
   * @return how many contacts were handed over
   */
  @Transactional
  public int releaseCollectedContacts(String userId) {
    List<EmailContactEntity> collected = emailContactDAO.findByUserIdAndSource(userId, EmailContactSource.COLLECTED);
    int released = 0;
    for (EmailContactEntity entity : collected) {
      if (entity.isSuppressed()) {
        continue;
      }
      entity.setSource(EmailContactSource.MANUAL);
      entity.setSeenCount(0);
      entity.setLastSeenDate(null);
      entity.setUpdatedDate(new Date());
      emailContactDAO.save(entity);
      released++;
    }
    return released;
  }

  @SneakyThrows
  @Transactional
  public void demoteCardDavRow(long id, boolean deletePhoto) {
    EmailContactEntity entity = emailContactDAO.findById(id).orElse(null);
    if (entity == null) {
      return;
    }
    if (deletePhoto && entity.getPhotoFileId() != null) {
      deletePhotoFile(entity.getPhotoFileId());
      entity.setPhotoFileId(null);
      entity.setPhotoOrigin(null);
    }
    entity.setSource(EmailContactSource.COLLECTED);
    entity.setHref(null);
    entity.setEtag(null);
    entity.setVcardUid(null);
    entity.setConnectorId(null);
    entity.setUpdatedDate(new Date());
    emailContactDAO.save(entity);
  }

  /**
   * Stamps a contact with the identity the card it came from carried.
   * <p>
   * Written here rather than through the DTO: the column is not something a
   * caller of the REST may set, and the import is the only other writer -- it
   * needs the identity so a second import of the same file recognises a person
   * with no address, which nothing else can do.
   *
   * @param id the contact
   * @param vcardUid the card's UID, ignored when blank
   */
  @Transactional
  public void setVcardUid(long id, String vcardUid) {
    if (StringUtils.isBlank(vcardUid)) {
      return;
    }
    emailContactDAO.findById(id).ifPresent(entity -> {
      entity.setVcardUid(vcardUid);
      emailContactDAO.save(entity);
    });
  }

  /**
   * Binds a contact the user published to the server entry the publish just
   * created: which book's connector, at which URL, at which version, under
   * which card identity — and flips the source to CARDDAV, because from this
   * moment the server copy is the authoritative one and the row is read-only
   * here, exactly like a row the inbound sync wrote.
   * <p>
   * Deliberately NOT {@code saveCardDavContact}: that method carries the
   * inbound sync's claim-and-merge semantics — overwriting fields from a
   * parsed card, merging addresses, deciding photo ownership. A publish has
   * nothing to merge: the fields already are the truth, only the bookkeeping
   * is new. The narrow shape follows {@link #setVcardUid}.
   *
   * @param id the contact that was published
   * @param connectorId the connector whose address book now holds it
   * @param href the entry URL the card was stored at
   * @param etag the version the server answered, or null when it sent none —
   *          stored null on purpose, so the next sync re-reads the entry and
   *          settles on the server's canonical version
   * @param vcardUid the identity minted into the published card
   */
  @Transactional
  public void bindPublishedCard(long id, Long connectorId, String href, String etag, String vcardUid) {
    emailContactDAO.findById(id).ifPresent(entity -> {
      entity.setSource(EmailContactSource.CARDDAV);
      entity.setConnectorId(connectorId);
      entity.setHref(href);
      entity.setEtag(etag);
      entity.setVcardUid(vcardUid);
      entity.setUpdatedDate(new Date());
      emailContactDAO.save(entity);
    });
  }

  /**
   * The contact carrying a vCard's identity, if this store already holds it.
   *
   * @param userId the store owner
   * @param vcardUid the card's UID
   * @return the contact, or null
   */
  public EmailContact getContactByVcardUid(String userId, String vcardUid) {
    if (StringUtils.isBlank(vcardUid)) {
      return null;
    }
    return emailContactDAO.findByUserIdAndVcardUid(userId, vcardUid).map(this::fromEntity).orElse(null);
  }

  /**
   * The vCard uid of every address-book row this user holds, keyed by row id.
   * <p>
   * What the export consults so a CardDAV contact leaves the store carrying the
   * identity its server gave it — one query for the whole export rather than
   * putting a bookkeeping column on the DTO every other read would then carry
   * for nothing.
   *
   * @param userId the store owner
   * @return row id → vCard uid, rows without one absent; never null
   */
  public Map<Long, String> getVcardUids(String userId) {
    Map<Long, String> uids = new HashMap<>();
    for (EmailContactEntity entity : emailContactDAO.findByUserIdAndSource(userId, EmailContactSource.CARDDAV)) {
      if (StringUtils.isNotBlank(entity.getVcardUid())) {
        uids.put(entity.getId(), entity.getVcardUid());
      }
    }
    return uids;
  }

  /**
   * Stores picture bytes that came from a vCard rather than an upload — the
   * address-book sync's path, and the file import's.
   *
   * @param photoFileId the file to replace, or null to write a new one
   * @param bytes the picture
   * @param mimeType its type
   * @return the stored file id, or null when it could not be written
   */
  @SneakyThrows
  public Long savePhotoBytes(Long photoFileId, byte[] bytes, String mimeType) {
    FileItem fileItem = new FileItem(photoFileId,
                                     PHOTO_FILE_NAME,
                                     StringUtils.defaultIfBlank(mimeType, "image/jpeg"),
                                     EmailConnectorStorage.NAME_SPACE,
                                     bytes.length,
                                     new Date(),
                                     null,
                                     false,
                                     new ByteArrayInputStream(bytes));
    try {
      FileItem stored = photoFileId != null && photoFileId > 0 ? fileService.updateFile(fileItem) : fileService.writeFile(fileItem);
      return stored == null || stored.getFileInfo() == null ? null : stored.getFileInfo().getId();
    } catch (Exception e) {
      // A picture that cannot be stored costs a picture, not a contact.
      LOG.warn("The picture of an address book entry could not be stored", e);
      return photoFileId;
    }
  }

  /**
   * The sync's view of a stored row.
   *
   * @param entity the stored row
   * @return the view
   */
  private CardDavRow toCardDavRow(EmailContactEntity entity) {
    return new CardDavRow(entity.getId(),
                          entity.getPrimaryEmail(),
                          entity.getSource(),
                          entity.isSuppressed(),
                          entity.getSeenCount(),
                          entity.getHref(),
                          entity.getEtag(),
                          entity.getPhotoFileId(),
                          // Null means USER: everything stored before the sync existed
                          // was set by hand.
                          entity.getPhotoOrigin() == null ? PhotoOrigin.USER : entity.getPhotoOrigin());
  }

  /**
   * Writes a cropped photo upload into the add-on's file namespace, following
   * the connector-illustration shape: an existing file id is UPDATED in place
   * (the id is stable across replacements, which is why the read URL is
   * versioned on the row's update date rather than on this id), a null one is
   * written fresh. The upload's own mimetype is kept — the cropper hands back
   * whatever the user picked, and re-labelling a JPEG as PNG only makes clients
   * sniff.
   *
   * @param photoFileId the file id to replace, or null to write a new file
   * @param uploadId the upload id the crop drawer produced
   * @return the stored file id, or null when the file service wrote nothing
   */
  @SneakyThrows
  public Long savePhotoFileItem(Long photoFileId, String uploadId) {
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    if (uploadResource == null) {
      // An expired or already-consumed upload: the caller's photo intent simply
      // cannot be honored, and dropping the whole save over it would be worse.
      LOG.warn("Upload {} is gone; the contact photo was not written", uploadId);
      return photoFileId;
    }
    byte[] bytesContent = IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
    FileItem fileItem = new FileItem(photoFileId,
                                     PHOTO_FILE_NAME,
                                     StringUtils.defaultIfBlank(uploadResource.getMimeType(), "image/png"),
                                     EmailConnectorStorage.NAME_SPACE,
                                     bytesContent.length,
                                     new Date(),
                                     null,
                                     false,
                                     new ByteArrayInputStream(bytesContent));
    if (photoFileId != null && photoFileId > 0) {
      fileItem = fileService.updateFile(fileItem);
    } else {
      fileItem = fileService.writeFile(fileItem);
    }
    return fileItem == null || fileItem.getFileInfo() == null ? null : fileItem.getFileInfo().getId();
  }

  /**
   * Deletes a stored contact photo — the cleanup of a replaced, cleared or
   * hard-deleted contact's file.
   *
   * @param photoFileId the file id, ignored when null or unset
   */
  public void deletePhotoFile(Long photoFileId) {
    if (photoFileId != null && photoFileId > 0) {
      fileService.deleteFile(photoFileId);
    }
  }

  /**
   * Reads a stored contact photo back, bytes and mimetype together — the REST
   * layer needs both to answer with an honest content type.
   *
   * @param photoFileId the file id
   * @return the file item, or null when nothing is stored under that id
   */
  @SneakyThrows
  public FileItem getPhotoFileItem(Long photoFileId) {
    if (photoFileId == null || photoFileId <= 0) {
      return null;
    }
    FileItem fileItem = fileService.getFile(photoFileId);
    return fileItem == null || fileItem.getAsByte() == null ? null : fileItem;
  }

  /**
   * Folds the GROUP BY rows into the ordered letter → count map the rail
   * consumes: "A".."Z" in bucket order, "#" (the everything-else bucket) last —
   * which is also exactly where its rows sort, bucket 26 being the largest.
   *
   * @param bucketCounts rows of {@code [sortBucket, count]} in bucket order
   * @return the ordered letter index, empty letters absent
   */
  private Map<String, Long> toLetterIndex(List<Object[]> bucketCounts) {
    Map<String, Long> letterIndex = new LinkedHashMap<>();
    for (Object[] row : bucketCounts) {
      letterIndex.put(EmailContactUtils.letterOfBucket(((Number) row[0]).intValue()), ((Number) row[1]).longValue());
    }
    return letterIndex;
  }

  /**
   * Maps a DTO to its entity, deriving what is stored but never authored: the
   * sort key, its bucket, and the joined secondary-address and phone strings.
   *
   * @param contact the DTO
   * @return the entity, ready to save
   */
  private EmailContactEntity toEntity(EmailContact contact) {
    EmailContactEntity entity = new EmailContactEntity();
    entity.setId(contact.getId());
    applyAuthoredColumns(contact, entity);
    entity.setCreatedDate(contact.getCreatedDate());
    entity.setUpdatedDate(contact.getUpdatedDate());
    return entity;
  }

  /**
   * Copies onto the entity exactly the columns the DTO owns, and nothing else.
   *
   * This is the whole point of the split: what the DTO does not carry -- the
   * CardDAV href, etag and vCard uid, the address book's connector, and whatever
   * a later phase adds -- belongs to the stored row and must survive a save that
   * knows nothing about it. The identity and the created date are deliberately
   * absent: they are the row's, not the payload's.
   *
   * @param contact the payload being saved
   * @param entity the row being written, mutated in place
   */
  private void applyAuthoredColumns(EmailContact contact, EmailContactEntity entity) {
    entity.setUserId(contact.getUserId());
    entity.setSource(contact.getSource());
    entity.setPrimaryEmail(contact.getPrimaryEmail());
    entity.setEmails(toEmailsString(contact.getSecondaryEmails()));
    entity.setDisplayName(contact.getDisplayName());
    entity.setGivenName(contact.getGivenName());
    entity.setFamilyName(contact.getFamilyName());
    String sortName = EmailContactUtils.computeSortName(contact.getGivenName(),
                                                        contact.getFamilyName(),
                                                        contact.getDisplayName(),
                                                        contact.getPrimaryEmail());
    entity.setSortName(sortName);
    entity.setSortBucket(EmailContactUtils.sortBucketOf(sortName));
    entity.setPhones(joinValues(contact.getPhones()));
    entity.setOrganization(contact.getOrganization());
    entity.setTitle(contact.getTitle());
    entity.setBirthday(contact.getBirthday());
    applyPostalAddress(contact.getPostalAddress(), entity);
    entity.setNote(EmailContactUtils.truncateNote(contact.getNote()));
    entity.setWebsite(contact.getWebsite());
    entity.setPlatformUsername(contact.getPlatformUsername());
    entity.setPhotoFileId(contact.getPhotoFileId());
    entity.setSuppressed(contact.isSuppressed());
    entity.setSeenCount(contact.getSeenCount());
    entity.setLastSeenDate(contact.getLastSeenDate());
  }

  /**
   * Maps an entity to the DTO, decoding the joined strings and resolving the
   * letter label from the stored bucket so the client groups exactly as the
   * server orders.
   *
   * @param entity the stored row
   * @return the DTO, or null for a null entity
   */
  private EmailContact fromEntity(EmailContactEntity entity) {
    if (entity == null) {
      return null;
    }
    EmailContact contact = new EmailContact();
    contact.setId(entity.getId());
    contact.setUserId(entity.getUserId());
    contact.setSource(entity.getSource());
    contact.setPrimaryEmail(entity.getPrimaryEmail());
    contact.setSecondaryEmails(fromEmailsString(entity.getEmails()));
    contact.setDisplayName(entity.getDisplayName());
    contact.setGivenName(entity.getGivenName());
    contact.setFamilyName(entity.getFamilyName());
    contact.setSortName(entity.getSortName());
    contact.setLetter(EmailContactUtils.letterOfBucket(entity.getSortBucket()));
    contact.setPhones(splitValues(entity.getPhones()));
    contact.setOrganization(entity.getOrganization());
    contact.setTitle(entity.getTitle());
    contact.setBirthday(entity.getBirthday());
    contact.setPostalAddress(PostalAddress.orNull(entity.getAddressStreet(),
                                                  entity.getAddressCity(),
                                                  entity.getAddressRegion(),
                                                  entity.getAddressPostalCode(),
                                                  entity.getAddressCountry()));
    contact.setNote(entity.getNote());
    contact.setWebsite(entity.getWebsite());
    contact.setPlatformUsername(entity.getPlatformUsername());
    contact.setPhotoFileId(entity.getPhotoFileId());
    contact.setSuppressed(entity.isSuppressed());
    contact.setSeenCount(entity.getSeenCount());
    contact.setLastSeenDate(entity.getLastSeenDate());
    contact.setCreatedDate(entity.getCreatedDate());
    contact.setUpdatedDate(entity.getUpdatedDate());
    return contact;
  }

  /**
   * Encodes secondary addresses as the stored {@code type,value;type,value}
   * string. Phase 1 has no address types yet, so every entry is typed "other" —
   * the format, and the LIKE shapes that search it, are the phase-3 seam.
   *
   * @param secondaryEmails the plain addresses, may be null
   * @return the joined string, or null when there is nothing to store
   */
  private String toEmailsString(List<String> secondaryEmails) {
    if (secondaryEmails == null || secondaryEmails.isEmpty()) {
      return null;
    }
    return secondaryEmails.stream()
                          .filter(StringUtils::isNotBlank)
                          .map(address -> "other," + address.trim().toLowerCase(Locale.ROOT))
                          .reduce((a, b) -> a + ";" + b)
                          .orElse(null);
  }

  /**
   * Decodes the stored {@code type,value;type,value} string back to plain
   * addresses (the types are dropped until something displays them).
   *
   * @param emails the stored string, may be null
   * @return the plain addresses, or null when nothing is stored
   */
  private List<String> fromEmailsString(String emails) {
    if (StringUtils.isBlank(emails)) {
      return null;
    }
    return Arrays.stream(emails.split(";"))
                 .map(entry -> entry.contains(",") ? entry.substring(entry.indexOf(',') + 1) : entry)
                 .filter(StringUtils::isNotBlank)
                 .toList();
  }

  /**
   * Writes a structured postal address onto its five columns — null clears all
   * five, so a removed address does not leave a country behind. One writer for
   * the authored path and the sync path alike, which is what keeps the two from
   * drifting a component apart.
   *
   * @param address the address, or null for none
   * @param entity the row being written, mutated in place
   */
  private void applyPostalAddress(PostalAddress address, EmailContactEntity entity) {
    entity.setAddressStreet(address == null ? null : address.street());
    entity.setAddressCity(address == null ? null : address.city());
    entity.setAddressRegion(address == null ? null : address.region());
    entity.setAddressPostalCode(address == null ? null : address.postalCode());
    entity.setAddressCountry(address == null ? null : address.country());
  }

  /**
   * Joins plain values with semicolons for storage.
   *
   * @param values the values, may be null
   * @return the joined string, or null when there is nothing to store
   */
  private String joinValues(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    String joined = values.stream().filter(StringUtils::isNotBlank).map(String::trim).reduce((a, b) -> a + ";" + b).orElse(null);
    return StringUtils.isBlank(joined) ? null : joined;
  }

  /**
   * Splits a semicolon-joined stored string back to its values.
   *
   * @param joined the stored string, may be null
   * @return the values, or null when nothing is stored
   */
  private List<String> splitValues(String joined) {
    if (StringUtils.isBlank(joined)) {
      return null;
    }
    return Arrays.stream(joined.split(";")).map(String::trim).filter(StringUtils::isNotBlank).toList();
  }
}
