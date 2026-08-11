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

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.emailConnector.dao.EmailContactDAO;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.EmailContact;
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
    entity.setEmails(toEmailsList(contact.getSecondaryEmails()));
    entity.setDisplayName(contact.getDisplayName());
    entity.setGivenName(contact.getGivenName());
    entity.setFamilyName(contact.getFamilyName());
    String sortName = EmailContactUtils.computeSortName(contact.getGivenName(),
                                                        contact.getFamilyName(),
                                                        contact.getDisplayName(),
                                                        contact.getPrimaryEmail());
    entity.setSortName(sortName);
    entity.setSortBucket(EmailContactUtils.sortBucketOf(sortName));
    entity.setPhones(contact.getPhones());
    entity.setOrganization(contact.getOrganization());
    entity.setTitle(contact.getTitle());
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
    contact.setSecondaryEmails(fromEmailsList(entity.getEmails()));
    contact.setDisplayName(entity.getDisplayName());
    contact.setGivenName(entity.getGivenName());
    contact.setFamilyName(entity.getFamilyName());
    contact.setSortName(entity.getSortName());
    contact.setLetter(EmailContactUtils.letterOfBucket(entity.getSortBucket()));
    contact.setPhones(entity.getPhones());
    contact.setOrganization(entity.getOrganization());
    contact.setTitle(entity.getTitle());
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
   * Encodes secondary addresses as {@code type,value} pairs, one per list
   * entry (the entity's {@code SemicolonListConverter} joins them for storage).
   * Phase 1 has no address types yet, so every entry is typed "other" — the
   * format, and the LIKE shapes that search it, are the phase-3 seam.
   *
   * @param secondaryEmails the plain addresses, may be null
   * @return the {@code type,value} pairs, or null when there is nothing to store
   */
  private List<String> toEmailsList(List<String> secondaryEmails) {
    if (secondaryEmails == null || secondaryEmails.isEmpty()) {
      return null;
    }
    List<String> pairs = secondaryEmails.stream()
                                        .filter(StringUtils::isNotBlank)
                                        .map(address -> "other," + address.trim().toLowerCase(Locale.ROOT))
                                        .toList();
    return pairs.isEmpty() ? null : pairs;
  }

  /**
   * Decodes the stored {@code type,value} pairs back to plain addresses (the
   * types are dropped until something displays them).
   *
   * @param emails the stored {@code type,value} pairs, may be null
   * @return the plain addresses, or null when nothing is stored
   */
  private List<String> fromEmailsList(List<String> emails) {
    if (emails == null || emails.isEmpty()) {
      return null;
    }
    return emails.stream()
                .map(entry -> entry.contains(",") ? entry.substring(entry.indexOf(',') + 1) : entry)
                .filter(StringUtils::isNotBlank)
                .toList();
  }
}
