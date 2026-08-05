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

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import org.exoplatform.emailConnector.entity.EmailContactEntity;

public interface EmailContactDAO extends JpaRepository<EmailContactEntity, Long> {

  /**
   * A contact by its natural key. The primary email is stored normalized
   * (lowercased, trimmed), so the caller passes the normalized form.
   *
   * @param userId the store owner
   * @param primaryEmail the normalized address
   * @return the row, suppressed or not — the collection upsert needs to see the
   *         tombstones to honor them
   */
  Optional<EmailContactEntity> findByUserIdAndPrimaryEmail(String userId, String primaryEmail);

  /**
   * Contacts one of whose SECONDARY addresses is the given one, so a message
   * from a person's alternate address still lands on their one row. The EMAILS
   * column encodes {@code type,value;type,value}, hence the two LIKE shapes: the
   * value is always preceded by a comma and followed by a semicolon or the end
   * of the string — bounding both sides keeps "ann@x.co" from matching
   * "ann@x.com". Matching with LIKE (not LOCATE) because the column is VARCHAR
   * precisely so HSQLDB can search it.
   *
   * @param userId the store owner
   * @param address the normalized address
   * @return the matching rows, in practice zero or one
   */
  @Query("SELECT c FROM EmailContactEntity c WHERE c.userId = :userId AND (LOWER(c.emails) LIKE CONCAT('%,', :address, ';%') OR LOWER(c.emails) LIKE CONCAT('%,', :address))")
  List<EmailContactEntity> findBySecondaryEmail(@Param("userId")
  String userId, @Param("address")
  String address);

  /**
   * The browse/search page over the user's whole visible store ("All" = the
   * local store, every source). The optional term is matched — already
   * lowercased by the caller — against the sort key, the display name and every
   * address. Ordering comes from the Pageable's Sort (bucket, then sort key,
   * then id), which Spring appends to the query.
   *
   * @param userId the store owner
   * @param term the lowercased filter text, or null for a plain browse
   * @param pageable page window plus the (sortBucket, sortName, id) sort
   * @return the page with its total count
   */
  @Query("SELECT c FROM EmailContactEntity c WHERE c.userId = :userId AND c.suppressed = false"
      + " AND (:term IS NULL OR LOWER(c.sortName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.displayName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.primaryEmail) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.emails) LIKE CONCAT('%', :term, '%'))")
  Page<EmailContactEntity> findContacts(@Param("userId")
  String userId, @Param("term")
  String term, Pageable pageable);

  /**
   * The same page restricted to a set of sources — the Collected and Address
   * book chips ("Address book" = MANUAL + CARDDAV, which is why this takes a
   * list and not one value).
   *
   * @param userId the store owner
   * @param sources the source discriminators to keep, never empty
   * @param term the lowercased filter text, or null for a plain browse
   * @param pageable page window plus the (sortBucket, sortName, id) sort
   * @return the page with its total count
   */
  @Query("SELECT c FROM EmailContactEntity c WHERE c.userId = :userId AND c.suppressed = false AND c.source IN :sources"
      + " AND (:term IS NULL OR LOWER(c.sortName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.displayName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.primaryEmail) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.emails) LIKE CONCAT('%', :term, '%'))")
  Page<EmailContactEntity> findContactsBySources(@Param("userId")
  String userId, @Param("sources")
  List<String> sources, @Param("term")
  String term, Pageable pageable);

  /**
   * The letter-index map's raw material: how many visible contacts sit in each
   * letter bucket, over the SAME filter as {@link #findContacts} so the rail
   * and the list can never disagree. One GROUP BY, ordered like the list.
   *
   * @param userId the store owner
   * @param term the lowercased filter text, or null for a plain browse
   * @return rows of {@code [sortBucket, count]}, in bucket order
   */
  @Query("SELECT c.sortBucket, COUNT(c.id) FROM EmailContactEntity c WHERE c.userId = :userId AND c.suppressed = false"
      + " AND (:term IS NULL OR LOWER(c.sortName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.displayName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.primaryEmail) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.emails) LIKE CONCAT('%', :term, '%'))"
      + " GROUP BY c.sortBucket ORDER BY c.sortBucket ASC")
  List<Object[]> countBySortBucket(@Param("userId")
  String userId, @Param("term")
  String term);

  /**
   * The source-restricted companion of {@link #countBySortBucket}, matching
   * {@link #findContactsBySources}'s filter.
   *
   * @param userId the store owner
   * @param sources the source discriminators to keep, never empty
   * @param term the lowercased filter text, or null for a plain browse
   * @return rows of {@code [sortBucket, count]}, in bucket order
   */
  @Query("SELECT c.sortBucket, COUNT(c.id) FROM EmailContactEntity c WHERE c.userId = :userId AND c.suppressed = false AND c.source IN :sources"
      + " AND (:term IS NULL OR LOWER(c.sortName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.displayName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.primaryEmail) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.emails) LIKE CONCAT('%', :term, '%'))"
      + " GROUP BY c.sortBucket ORDER BY c.sortBucket ASC")
  List<Object[]> countBySortBucketAndSources(@Param("userId")
  String userId, @Param("sources")
  List<String> sources, @Param("term")
  String term);

  /**
   * The compose field's type-ahead over the user's own store. Matched — on an
   * already-lowercased term — against the display name, the structured given
   * and family names and every address, which is what somebody typing into a
   * recipient field actually has in mind; the derived sort key is deliberately
   * NOT searched here, since it only ever restates the same names in a form
   * nobody types (uppercased, diacritics stripped, family first).
   * <p>
   * Ordering is the caller's {@link Pageable} sort, and it is the whole point
   * of this query being separate from {@link #findContacts}: browse is
   * alphabetical, suggest is by usefulness. Returns a plain list rather than a
   * {@link Page} so the type-ahead never pays for a COUNT it does not display.
   *
   * @param userId the store owner
   * @param term the lowercased filter text, or null to rank the whole store
   * @param pageable the window plus the usefulness sort
   * @return at most one window of rows, in the requested order
   */
  @Query("SELECT c FROM EmailContactEntity c WHERE c.userId = :userId AND c.suppressed = false"
      + " AND (:term IS NULL OR LOWER(c.displayName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.givenName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.familyName) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.primaryEmail) LIKE CONCAT('%', :term, '%')"
      + " OR LOWER(c.emails) LIKE CONCAT('%', :term, '%'))")
  List<EmailContactEntity> suggestContacts(@Param("userId")
  String userId, @Param("term")
  String term, Pageable pageable);

  /**
   * The row already linking a platform identity, if any — the directory
   * import's first dedupe key: an identity stays one row even when its profile
   * email changed since it was imported.
   *
   * @param userId the store owner
   * @param platformUsername the platform identity
   * @return the linked row, if one exists
   */
  Optional<EmailContactEntity> findFirstByUserIdAndPlatformUsername(String userId, String platformUsername);

  /**
   * How many rows — INCLUDING suppressed ones — the user has in any source. This
   * is a presence probe (does the Address book chip have anything to show, has
   * anything ever been stored), not a display count.
   *
   * @param userId the store owner
   * @param sources the source discriminators to count
   * @return the row count
   */
  long countByUserIdAndSourceIn(String userId, List<String> sources);
}
