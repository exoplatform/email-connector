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

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
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

import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.utils.EmailContactUtils;

/**
 * Storage of the per-user contact store: converts between the JPA entity and
 * the {@link EmailContact} DTO, derives the sort key and letter bucket at write
 * time, and assembles the paged list with its letter-index map.
 */
@Component
public class EmailContactStorage {

  // The list's one ordering, everywhere: bucket (collation-proof), then sort key,
  // then id as the stable tiebreaker the rail's offset arithmetic needs.
  private static final Sort CONTACT_SORT = Sort.by(Sort.Direction.ASC, "sortBucket", "sortName", "id");

  @Autowired
  private EmailContactDAO   emailContactDAO;

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
    String term = StringUtils.isBlank(query) ? null : query.trim().toLowerCase(Locale.ROOT);
    PageRequest pageRequest = PageRequest.of(limit > 0 ? offset / limit : 0, limit, CONTACT_SORT);
    Page<EmailContactEntity> page;
    List<Object[]> bucketCounts;
    if (sources == null || sources.isEmpty()) {
      page = emailContactDAO.findContacts(userId, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucket(userId, term);
    } else {
      page = emailContactDAO.findContactsBySources(userId, sources, term, pageRequest);
      bucketCounts = emailContactDAO.countBySortBucketAndSources(userId, sources, term);
    }
    List<EmailContact> contacts = page.getContent().stream().map(this::fromEntity).toList();
    return new EmailContactPage(contacts, toLetterIndex(bucketCounts), page.getTotalElements(), offset, limit);
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
  public EmailContact getContactByAddress(String userId, String normalizedAddress) {
    return emailContactDAO.findByUserIdAndPrimaryEmail(userId, normalizedAddress)
                          .map(this::fromEntity)
                          .orElseGet(() -> emailContactDAO.findBySecondaryEmail(userId, normalizedAddress)
                                                          .stream()
                                                          .findFirst()
                                                          .map(this::fromEntity)
                                                          .orElse(null));
  }

  /**
   * Creates a row from the DTO, deriving the sort key/bucket and stamping the
   * creation date.
   *
   * @param contact the contact to persist
   * @return the persisted contact with its id
   */
  public EmailContact createContact(EmailContact contact) {
    EmailContactEntity entity = toEntity(contact);
    entity.setId(null);
    entity.setCreatedDate(new Date());
    entity.setUpdatedDate(entity.getCreatedDate());
    return fromEntity(emailContactDAO.save(entity));
  }

  /**
   * Updates a row in place from the DTO (the id addresses the row), re-deriving
   * the sort key/bucket and stamping the update date. The creation date is
   * preserved from the stored row.
   *
   * @param contact the contact to persist, with its id set
   * @return the persisted contact
   */
  public EmailContact updateContact(EmailContact contact) {
    Date createdDate = emailContactDAO.findById(contact.getId())
                                      .map(EmailContactEntity::getCreatedDate)
                                      .orElse(null);
    EmailContactEntity entity = toEntity(contact);
    entity.setCreatedDate(createdDate);
    entity.setUpdatedDate(new Date());
    return fromEntity(emailContactDAO.save(entity));
  }

  /**
   * Hard-deletes a row — the MANUAL delete path; collected rows go through the
   * service's suppression instead.
   *
   * @param id the contact id
   */
  public void deleteContact(long id) {
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
    entity.setSuppressed(contact.isSuppressed());
    entity.setSeenCount(contact.getSeenCount());
    entity.setLastSeenDate(contact.getLastSeenDate());
    entity.setCreatedDate(contact.getCreatedDate());
    entity.setUpdatedDate(contact.getUpdatedDate());
    return entity;
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
