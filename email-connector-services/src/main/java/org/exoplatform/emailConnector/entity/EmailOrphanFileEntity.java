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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A stored file that no mail row references any more, written down so something can
 * free it later.
 * <p>
 * It exists because of how mail rows are deleted: {@code EmailBoxDAO.deleteEmailsByIds}
 * is a bulk JPQL DELETE, so there is no cascade in Java, no entity callback and no
 * loaded instance — the attachment rows disappear through the database's own ON DELETE
 * CASCADE and nothing in this application ever observes it. There is therefore no
 * lifecycle hook to hang "and free the file" on, and by the time the rows are gone
 * nothing anywhere knows which files they named.
 * <p>
 * So the ids are read and recorded BEFORE the rows go. A crash between the two leaves
 * a marker for a file that is still referenced, which is harmless because the sweep
 * verifies before deleting; the other order would leave bytes in the file store that
 * nothing names and nothing can find. A note nobody needed beats a file nobody can
 * reach.
 * <p>
 * This is a MARKER, not a queue of work anyone processes yet: the sweep that reads
 * these rows is a later slice. Until then they accumulate on purpose — they are the
 * record that the work is outstanding.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailOrphanFileEntity")
@Table(name = "EMAIL_ORPHAN_FILE")
public class EmailOrphanFileEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_ORPHAN_FILE_ID", sequenceName = "SEQ_EMAIL_ORPHAN_FILE_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_ORPHAN_FILE_ID")
  @Column(name = "ID")
  private Long   id;

  // Unique: recording the same file twice is an ordinary race between two cleanups
  // over overlapping rows, and the second should be a no-op rather than a second
  // marker to process.
  @Column(name = "FILE_ID")
  private Long   fileId;

  // Who owned the mail the file hung off. Carried so the sweep can be scoped and its
  // work attributed, and deliberately not a foreign key: the whole point of this row
  // is to outlive everything it refers to.
  @Column(name = "USER_ID")
  private String userId;

  @Column(name = "RECORDED_DATE")
  private Date   recordedDate;
}
