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
package org.exoplatform.emailConnector.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Pure helpers for grouping mail messages into conversations (threads) from
 * their RFC 5322 identity headers ({@code Message-ID}, {@code In-Reply-To},
 * {@code References}). Kept free of Spring and persistence so the threading
 * rules can be unit-tested on their own and mocked where the service uses them.
 */
public class EmailThreadingUtils {

  // A message id is an angle-bracketed token, e.g. <abc.123@host>. Headers may carry
  // several, whitespace- or comma-separated; anything outside the brackets is ignored.
  private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("<[^<>\\s]+>");

  // Reply/forward subject prefixes to strip, case-insensitive, across the locales the
  // product ships (EN/FR/DE/IT/ES/SV), optionally numbered as in "Re[2]:".
  private static final Pattern SUBJECT_PREFIX_PATTERN =
                                                      Pattern.compile("^\\s*(re|fwd?|fw|tr|aw|sv|rif|res|antw)(\\[\\d+\\])?\\s*:\\s*",
                                                                      Pattern.CASE_INSENSITIVE);

  private EmailThreadingUtils() {
    // utility class
  }

  /**
   * Extracts the angle-bracketed message ids from a header value.
   *
   * @param header a raw {@code References} / {@code In-Reply-To} / {@code Message-ID}
   *          header value, may be null
   * @return the message ids in the order they appear, never null
   */
  public static List<String> parseMessageIds(String header) {
    List<String> ids = new ArrayList<>();
    if (StringUtils.isBlank(header)) {
      return ids;
    }
    Matcher matcher = MESSAGE_ID_PATTERN.matcher(header);
    while (matcher.find()) {
      ids.add(matcher.group());
    }
    return ids;
  }

  /**
   * The set of message ids a message points back to: everything in its
   * {@code References} plus its {@code In-Reply-To}, de-duplicated while keeping
   * the first-seen order (References is the fuller chain, so it comes first).
   *
   * @param inReplyTo the raw In-Reply-To header, may be null
   * @param references the raw References header, may be null
   * @return the referenced message ids, never null
   */
  public static Set<String> collectReferencedIds(String inReplyTo, String references) {
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(parseMessageIds(references));
    ids.addAll(parseMessageIds(inReplyTo));
    return ids;
  }

  /**
   * Builds the {@code References} header of a reply, per RFC 5322 §3.6.4: the
   * parent's own References followed by the parent's Message-ID. Using only the
   * parent id (as the code did before) drops the link to earlier messages, so a
   * third message in a chain would start a new thread — this keeps the full chain.
   *
   * @param parentReferences the parent message's References header, may be null
   * @param parentMessageId the parent message's Message-ID, may be null
   * @return the References header to set on the reply, or null when there is nothing to reference
   */
  public static String buildReferencesHeader(String parentReferences, String parentMessageId) {
    String chain = StringUtils.isBlank(parentReferences) ? "" : parentReferences.trim();
    if (StringUtils.isNotBlank(parentMessageId)) {
      chain = chain.isEmpty() ? parentMessageId.trim() : chain + " " + parentMessageId.trim();
    }
    return chain.isEmpty() ? null : chain;
  }

  /**
   * A stable, synthetic Message-ID for a message whose sender omitted one, so it can
   * still anchor a thread and be referenced later.
   *
   * @param mailRemoteId the IMAP UID of the message
   * @param userId the owning user
   * @return a synthesized angle-bracketed message id
   */
  public static String synthesizeMessageId(long mailRemoteId, String userId) {
    return "<" + mailRemoteId + "." + userId + "@email-connector.local>";
  }

  /**
   * The subject stripped of its reply/forward prefixes, used for display of a thread
   * title and for the conservative subject fallback. Applied repeatedly so a
   * "Re: Fwd:" chain is fully reduced.
   *
   * @param subject the raw subject, may be null
   * @return the normalized subject, never null
   */
  public static String normalizeSubject(String subject) {
    if (StringUtils.isBlank(subject)) {
      return "";
    }
    String normalized = subject.trim();
    Matcher matcher = SUBJECT_PREFIX_PATTERN.matcher(normalized);
    while (matcher.lookingAt()) {
      normalized = normalized.substring(matcher.end());
      matcher = SUBJECT_PREFIX_PATTERN.matcher(normalized);
    }
    return normalized.trim();
  }
}
