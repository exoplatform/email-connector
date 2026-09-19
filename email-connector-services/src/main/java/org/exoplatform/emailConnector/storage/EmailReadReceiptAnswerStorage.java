/**
 * Copyright (C) 2026 eXo Platform SAS
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailReadReceiptAnswerDAO;
import org.exoplatform.emailConnector.entity.EmailReadReceiptAnswerEntity;
import org.exoplatform.emailConnector.model.ReadReceiptAnswerOrigin;
import org.exoplatform.emailConnector.model.ReadReceiptState;

/**
 * The durable read-receipt answers (EXO-90435), keyed by user and Message-ID: what
 * makes an answer outlive the cached rows of its message, on a mailbox that stores no
 * {@code $MDNSent} keyword (Exchange).
 * <p>
 * Nothing here decides anything: an insert refused by the unique index comes back as
 * "not claimed", and the service draws the conclusion. A message is addressed by the
 * Message-ID its cached rows carry, which is the one the message came with: a message
 * that came with none has no key here, and keeps the phase-1 answer on its rows. No
 * cache: the rows are the decision, and a cached copy of a decision is a decision
 * nobody took.
 */
@Component
public class EmailReadReceiptAnswerStorage {

  @Autowired
  private EmailReadReceiptAnswerDAO answerDAO;

  /**
   * Records an answer, if none exists yet for this user and message: the at-most-once
   * decision of a read receipt. The unique index refuses the second insert, whoever
   * races -- another tab, another node, the sync mirroring the server's keyword.
   *
   * @param userId the user answering
   * @param messageId the message's Message-ID
   * @param state the answer
   * @param origin where it comes from
   * @param answeredDate when
   * @return the id of the recorded answer, to give it back with {@link #release}; null
   *         when an answer already existed or the message has no key
   */
  public Long claim(String userId, String messageId, ReadReceiptState state, ReadReceiptAnswerOrigin origin, Date answeredDate) {
    String hash = messageIdHash(messageId);
    if (hash == null) {
      return null;
    }
    try {
      return answerDAO.saveAndFlush(new EmailReadReceiptAnswerEntity(null, userId, hash, state, origin, answeredDate)).getId();
    } catch (DataIntegrityViolationException e) {
      // The unique (USER_ID, MESSAGE_ID_HASH): the request was answered a moment ago.
      return null;
    }
  }

  /**
   * Gives back an answer recorded by {@link #claim}, when the receipt it was taken for
   * could not leave at all.
   *
   * @param userId the user
   * @param answerId the id {@link #claim} returned
   */
  public void release(String userId, long answerId) {
    answerDAO.deleteByUserIdAndId(userId, answerId);
  }

  /**
   * The answers a user gave to the given messages, one statement for the lot.
   *
   * @param userId the user
   * @param messageIds the Message-IDs, may hold nulls and blanks, which have no answer
   *          here
   * @return the answers, keyed by {@link #messageIdHash} of each answered Message-ID
   *         (two spellings of one id share a key); never null
   */
  public Map<String, ReadReceiptState> findAnswers(String userId, Collection<String> messageIds) {
    Map<String, String> idsByHash = hashes(messageIds);
    if (idsByHash.isEmpty()) {
      return Map.of();
    }
    Map<String, ReadReceiptState> answers = new HashMap<>();
    for (EmailReadReceiptAnswerEntity answer : answerDAO.findByUserIdAndMessageIdHashes(userId, idsByHash.keySet())) {
      answers.put(answer.getMessageIdHash(), answer.getState());
    }
    return answers;
  }

  /**
   * Records as answered the messages the mail server says were answered
   * ({@code $MDNSent}): {@link ReadReceiptState#SENT}, origin
   * {@link ReadReceiptAnswerOrigin#SERVER}. The messages already answered are read
   * first, in one statement, so a folder the sync mirrored before costs no insert; an
   * insert refused by a concurrent answer is simply that answer standing.
   *
   * @param userId the user
   * @param messageIds the Message-IDs carrying the keyword
   * @param answeredDate when the sync saw it
   * @return how many answers were recorded
   */
  public int recordServerAnswers(String userId, Collection<String> messageIds, Date answeredDate) {
    Map<String, String> idsByHash = hashes(messageIds);
    if (idsByHash.isEmpty()) {
      return 0;
    }
    for (EmailReadReceiptAnswerEntity known : answerDAO.findByUserIdAndMessageIdHashes(userId, idsByHash.keySet())) {
      idsByHash.remove(known.getMessageIdHash());
    }
    int recorded = 0;
    for (String hash : idsByHash.keySet()) {
      try {
        answerDAO.saveAndFlush(new EmailReadReceiptAnswerEntity(null,
                                                                userId,
                                                                hash,
                                                                ReadReceiptState.SENT,
                                                                ReadReceiptAnswerOrigin.SERVER,
                                                                answeredDate));
        recorded++;
      } catch (DataIntegrityViolationException e) {
        // Answered meanwhile, by the user or another node's sync: that answer stands.
      }
    }
    return recorded;
  }

  /**
   * The key of a message in this store: SHA-256 of its Message-ID, trimmed, in
   * lower-case hex. Case is kept before hashing -- a Message-ID is case-sensitive --
   * and the hex is lower-case, so the unique index holds under any collation.
   *
   * @param messageId the Message-ID, as the cache stores it
   * @return the hash, or null for a blank id
   */
  public static String messageIdHash(String messageId) {
    String id = StringUtils.trimToNull(messageId);
    if (id == null) {
      return null;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform must provide SHA-256.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * The keys of the given Message-IDs, each mapped back to its id; nulls and blanks
   * left out.
   *
   * @param messageIds the Message-IDs
   * @return hash to Message-ID, in the order given; never null
   */
  private static Map<String, String> hashes(Collection<String> messageIds) {
    Map<String, String> idsByHash = new LinkedHashMap<>();
    if (messageIds != null) {
      messageIds.stream().filter(Objects::nonNull).forEach(id -> {
        String hash = messageIdHash(id);
        if (hash != null) {
          idsByHash.putIfAbsent(hash, id);
        }
      });
    }
    return idsByHash;
  }
}
