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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailRecipient;

/**
 * A cached mail as a rule's conditions read it. A mail listed without its recipients or
 * body -- the inbox window a preview reads -- loads its whole row the first time a
 * condition asks for either, and only then.
 */
public final class EmailFilterMail implements FilterMail {

  private final Email                          listed;

  private final Supplier<Email>                loader;

  private final Set<String>                    keywords;

  private final Function<String, List<String>> headers;

  private final Supplier<Long>                 sizeKb;

  private Email                                full;

  /**
   * A cached mail.
   *
   * @param listed the row as read, possibly without its recipients or body
   * @param loader reads the whole row, when a condition needs what the listed one lacks;
   *          null when {@code listed} is whole
   * @param keywords the server's keywords on the mail, lower-case; null when not known
   * @param headers reads a header of the mail, null when eXo cannot read its headers
   * @param sizeKb reads the mail's size in kilobytes, null when eXo cannot know it
   */
  public EmailFilterMail(Email listed,
                         Supplier<Email> loader,
                         Set<String> keywords,
                         Function<String, List<String>> headers,
                         Supplier<Long> sizeKb) {
    this.listed = Objects.requireNonNull(listed);
    this.loader = loader;
    this.keywords = keywords == null ? Set.of()
                                     : keywords.stream().map(keyword -> keyword.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    this.headers = headers;
    this.sizeKb = sizeKb;
  }

  /**
   * The cached row this mail reads.
   *
   * @return the row as listed
   */
  public Email email() {
    return listed;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String from() {
    return listed.getSender() == null ? null : listed.getSender().getAddress();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> to() {
    return addresses(whole().getTo());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> cc() {
    return addresses(whole().getCc());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String subject() {
    return listed.getSubject();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String bodyText() {
    Email email = whole();
    if (email.getContent() == null) {
      return null;
    }
    String body = email.getContent().getBody();
    if (body == null) {
      return email.getContent().getExcerpt();
    }
    return email.getContent().isHtml() ? Jsoup.parse(body).text() : body;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasAttachment() {
    return listed.getContent() != null && listed.getContent().getAttachments() != null
        && !listed.getContent().getAttachments().isEmpty();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isList() {
    return listed.isHasListId() || listed.isHasListPost() || listed.isHasListUnsubscribe();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isAutomated() {
    return listed.isAutoSubmitted();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<String> header(String name) {
    return headers == null ? null : headers.apply(name);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Long sizeKb() {
    return sizeKb == null ? null : sizeKb.get();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> keywords() {
    return keywords;
  }

  /**
   * The whole row: the listed one when it carries its recipients or no loader is given,
   * else the one the loader reads, once.
   *
   * @return the row
   */
  private Email whole() {
    if (loader == null || listed.getTo() != null) {
      return listed;
    }
    if (full == null) {
      Email loaded = loader.get();
      full = loaded == null ? listed : loaded;
    }
    return full;
  }

  /**
   * The addresses of recipients.
   *
   * @param recipients the recipients, possibly null
   * @return their non-blank addresses
   */
  private static List<String> addresses(List<EmailRecipient> recipients) {
    return recipients == null ? List.of()
                              : recipients.stream().map(EmailRecipient::getAddress).filter(StringUtils::isNotBlank).toList();
  }
}
