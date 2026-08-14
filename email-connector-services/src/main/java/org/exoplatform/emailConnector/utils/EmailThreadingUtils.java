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
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.Email;

/**
 * Pure helpers for grouping mail messages into conversations (threads) from
 * their RFC 5322 identity headers ({@code Message-ID}, {@code In-Reply-To},
 * {@code References}), and for the order the messages of one conversation read
 * in. Kept free of Spring and persistence so the threading rules can be
 * unit-tested on their own and mocked where the service uses them.
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
   * Puts every draft of a conversation immediately after the message it answers,
   * leaving the mail around it in exactly the order it came in.
   * <p>
   * A draft used to land wherever its date put it, and its date is rewritten every
   * time its author types — so a reply to Monday's message, resumed tonight, sorted
   * itself below a message that arrived this evening and answered nothing. The
   * conversation then read as if the user were replying to the newest message, which
   * is precisely what they are not doing. Position is a fact about what a draft
   * ANSWERS, and In-Reply-To is where that fact already lives; the date is a fact
   * about when it was last touched, and touching a draft must not move it.
   * <p>
   * A draft whose parent is not here — a plain message that answers nothing, or a
   * reply whose parent has fallen out of the cache — goes to the END, which is where
   * a draft answering the newest message lands anyway. That is the common case and it
   * looks unchanged.
   * <p>
   * Deliberately NOT a second ORDER BY on the thread query. That query's date
   * ordering is shared with the folder listings and with the sync cleanup's
   * assumption about which end of a list is oldest, and a draft's place in a
   * conversation is a reader's question, not the table's. This is also why it lives
   * here rather than in the reader: every client of a conversation gets the same
   * answer, and there is one rule to keep true rather than one per screen.
   *
   * @param conversation the thread's messages as they were read, oldest first
   * @return the same messages, drafts repositioned, never null when given a list
   */
  public static List<Email> positionDraftsAfterTheirParent(List<Email> conversation) {
    if (conversation == null || conversation.size() < 2 || conversation.stream().noneMatch(EmailThreadingUtils::isDraft)) {
      return conversation;
    }
    List<Email> ordered = new ArrayList<>(conversation.size());
    List<Email> drafts = new ArrayList<>();
    for (Email message : conversation) {
      if (isDraft(message)) {
        drafts.add(message);
      } else {
        ordered.add(message);
      }
    }
    for (Email draft : drafts) {
      int parentIndex = lastIndexOfParent(ordered, draft);
      if (parentIndex < 0) {
        ordered.add(draft);
      } else {
        // Past the drafts already placed under this same parent, so two unsent replies
        // to one message stay in the order they were written rather than swapping.
        int insertAt = parentIndex + 1;
        while (insertAt < ordered.size() && isDraft(ordered.get(insertAt))) {
          insertAt++;
        }
        ordered.add(insertAt, draft);
      }
    }
    return List.copyOf(ordered);
  }

  /**
   * Whether a row is a draft rather than mail, told by the composer's own handle on
   * it — the one thing only a draft has, and what every other layer keys them by.
   *
   * @param message a message of a conversation
   * @return true when the row is a draft
   */
  private static boolean isDraft(Email message) {
    return message != null && StringUtils.isNotBlank(message.getDraftLocalId());
  }

  /**
   * Where the message a draft answers sits in a conversation, or -1 when it is not
   * there.
   * <p>
   * The LAST match, not the first: the same message can be cached once per folder
   * (an inbox copy and an archive copy of one mail), and a reply belongs after all
   * of them rather than between two copies of what it answers.
   *
   * @param messages the conversation's mail, in reading order
   * @param draft the draft looking for its parent
   * @return the parent's index, or -1
   */
  private static int lastIndexOfParent(List<Email> messages, Email draft) {
    List<String> parentIds = parseMessageIds(draft.getInReplyTo());
    if (parentIds.isEmpty() && StringUtils.isNotBlank(draft.getInReplyTo())) {
      // An In-Reply-To that carries no angle brackets is not RFC-shaped, but it is
      // still the client's answer to "what does this reply to", and a draft imported
      // from another mail client is where such a value comes from.
      parentIds = List.of(draft.getInReplyTo().trim());
    }
    for (int index = messages.size() - 1; index >= 0; index--) {
      String messageId = StringUtils.trimToNull(messages.get(index).getMailHeaderId());
      if (messageId != null && parentIds.contains(messageId)) {
        return index;
      }
    }
    return -1;
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
   * The conversation identity encoded in an Exchange/Outlook {@code Thread-Index}
   * header — the 16-byte GUID shared by every message of the conversation. Per
   * MS-OXOMSG the base64 value starts with 1 reserved byte + 5 FILETIME bytes,
   * then the 16-byte conversation GUID (offset 6), then a 5-byte block per reply.
   * Two messages with the same root belong to the same conversation even when
   * their {@code References} chain is broken (subject changes, external forwards).
   *
   * @param threadIndex the raw {@code Thread-Index} header, may be null
   * @return the 16-byte conversation GUID as a lowercase hex string, or null when
   *         the header is absent or not a well-formed Thread-Index
   */
  public static String extractThreadIndexRoot(String threadIndex) {
    if (StringUtils.isBlank(threadIndex)) {
      return null;
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(threadIndex.replaceAll("\\s", ""));
      if (decoded.length < 22) {
        return null;
      }
      StringBuilder hex = new StringBuilder(32);
      for (int i = 6; i < 22; i++) {
        hex.append(String.format("%02x", decoded[i]));
      }
      return hex.toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
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
