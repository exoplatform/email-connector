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
 * One address of one contact.
 * <p>
 * Addresses used to BE the contact: the contact table was unique on the address,
 * which made a person without one impossible to store. Mail always carried an
 * address, so it held — an address book does not, and a contact with a phone
 * number and no mail address is perfectly ordinary. Identity now lives on the
 * contact, and this is what a contact can be reached at.
 * <p>
 * The owner is denormalised onto the row so both uniqueness and the lookup
 * collection performs on every synced message are one index on this table.
 */
@Entity(name = "EmailContactAddressEntity")
@Table(name = "EMAIL_CONTACT_ADDRESS")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailContactAddressEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_CONTACT_ADDRESS_ID", sequenceName = "SEQ_EMAIL_CONTACT_ADDRESS_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_CONTACT_ADDRESS_ID")
  @Column(name = "ID")
  private Long   id;

  @Column(name = "CONTACT_ID")
  private Long   contactId;

  @Column(name = "USER_ID")
  private String userId;

  /** Lower-cased and trimmed, as every address in this add-on is. */
  @Column(name = "ADDRESS")
  private String address;
}
