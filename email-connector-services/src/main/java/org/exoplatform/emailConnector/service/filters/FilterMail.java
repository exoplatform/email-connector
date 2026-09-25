/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.filters;

import java.util.List;
import java.util.Set;

/**
 * What a rule's conditions read of one mail. A part eXo does not know of that mail --
 * a header or the size of a mail cached before, which the cache does not keep -- is
 * null, and a condition on it is undecided rather than false.
 */
public interface FilterMail {

  /**
   * The sender's address.
   *
   * @return the address, or null
   */
  String from();

  /**
   * The To addresses.
   *
   * @return the addresses, never null
   */
  List<String> to();

  /**
   * The Cc addresses.
   *
   * @return the addresses, never null
   */
  List<String> cc();

  /**
   * The subject.
   *
   * @return the subject, or null
   */
  String subject();

  /**
   * The body as plain text.
   *
   * @return the text, or null
   */
  String bodyText();

  /**
   * Whether the mail has an attachment.
   *
   * @return true when it has one
   */
  boolean hasAttachment();

  /**
   * Whether the mail comes from a mailing list.
   *
   * @return true for a list mail
   */
  boolean isList();

  /**
   * Whether the mail was sent by an automated sender.
   *
   * @return true for an automated mail
   */
  boolean isAutomated();

  /**
   * The values of a header.
   *
   * @param name the header's name, lower-case
   * @return the values, empty when absent; null when eXo cannot read the mail's headers
   */
  List<String> header(String name);

  /**
   * The mail's size, in kilobytes.
   *
   * @return the size; null when not known
   */
  Long sizeKb();

  /**
   * The keywords the server set on the mail.
   *
   * @return the keywords, lower-case; empty when none or not known
   */
  Set<String> keywords();
}
