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
package org.exoplatform.emailConnector.storage;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.exoplatform.emailConnector.dao.EmailFilterProposalDAO;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;

import io.meeds.social.util.JsonUtils;

/**
 * The tool calls mail filters' assistants recorded instead of running them: mapping and
 * the at-most-once key. No business rule here -- the caps, the expiry and who may decide
 * are the service's.
 */
@Component
public class EmailFilterProposalStorage {

  /** The longest title kept. */
  static final int                    MAX_TITLE_LENGTH       = 250;

  /** The longest description kept. */
  static final int                    MAX_DESCRIPTION_LENGTH = 2000;

  /** The longest rationale kept. */
  static final int                    MAX_RATIONALE_LENGTH   = 500;

  /** The longest error kept. */
  static final int                    MAX_ERROR_LENGTH       = 500;

  /** The longest tool answer kept. */
  static final int                    MAX_RESULT_LENGTH      = 4000;

  /** A mapper writing objects' keys in order, so that one call always has one JSON. */
  private static final ObjectMapper   CANONICAL              = JsonUtils.OBJECT_MAPPER.copy()
                                                                                      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                                                                                                 true);

  @Autowired
  private EmailFilterProposalDAO      emailFilterProposalDAO;

  /**
   * Records a call, unless the match already holds a proposal for the same call.
   *
   * @param proposal the call, its id null, its status and dates set
   * @param userId the owner
   * @return the proposal as stored, or empty when the match already holds that call
   */
  public Optional<EmailFilterProposal> create(EmailFilterProposal proposal, String userId) {
    EmailFilterProposalEntity entity = new EmailFilterProposalEntity();
    entity.setUserId(userId);
    entity.setMatchId(proposal.getMatchId());
    entity.setFilterId(proposal.getFilterId());
    entity.setToolName(proposal.getToolName());
    entity.setCallHash(callHash(proposal.getToolName(), proposal.getArguments()));
    write(entity, proposal);
    try {
      return Optional.of(toDto(emailFilterProposalDAO.saveAndFlush(entity)));
    } catch (DataIntegrityViolationException e) {
      return Optional.empty();
    }
  }

  /**
   * Writes the mutable part of a proposal of its owner.
   *
   * @param proposal the proposal
   * @param userId the owner
   * @return the proposal as stored, or empty when the id names no proposal of this user
   */
  public Optional<EmailFilterProposal> update(EmailFilterProposal proposal, String userId) {
    return emailFilterProposalDAO.findByIdAndUserId(proposal.getId(), userId).stream().findFirst().map(entity -> {
      write(entity, proposal);
      return toDto(emailFilterProposalDAO.saveAndFlush(entity));
    });
  }

  /**
   * Whether a proposal exists, whoever it belongs to: tells a missing proposal from
   * someone else's.
   *
   * @param id the proposal
   * @return true when it exists
   */
  public boolean exists(long id) {
    return emailFilterProposalDAO.existsById(id);
  }

  /**
   * One proposal of its owner.
   *
   * @param id the proposal
   * @param userId the owner
   * @return the proposal, or empty
   */
  public Optional<EmailFilterProposal> get(long id, String userId) {
    return emailFilterProposalDAO.findByIdAndUserId(id, userId).stream().findFirst().map(EmailFilterProposalStorage::toDto);
  }

  /**
   * The proposal a match holds for a call.
   *
   * @param userId the owner
   * @param matchId the match
   * @param toolName the tool
   * @param arguments the arguments
   * @return the proposal, or empty
   */
  public Optional<EmailFilterProposal> getByCall(String userId, long matchId, String toolName, String arguments) {
    return emailFilterProposalDAO.findByCall(userId, matchId, callHash(toolName, arguments))
                                 .stream()
                                 .findFirst()
                                 .map(EmailFilterProposalStorage::toDto);
  }

  /**
   * The Message-IDs of the owner's mails with a proposal still waiting for them.
   *
   * @param userId the owner
   * @param now the time; a proposal past its expiry at it does not count
   * @return the Message-IDs, each once
   */
  public List<String> getWaitingMailHeaderIds(String userId, Date now) {
    return emailFilterProposalDAO.findWaitingMailHeaderIds(userId, EmailFilterProposal.PROPOSED, now);
  }

  /**
   * The proposals of some matches of their owner.
   *
   * @param userId the owner
   * @param matchIds the matches
   * @return the proposals, oldest first; empty, without a query, for no match
   */
  public List<EmailFilterProposal> getByMatches(String userId, Collection<Long> matchIds) {
    if (matchIds == null || matchIds.isEmpty()) {
      return List.of();
    }
    return emailFilterProposalDAO.findByMatches(userId, matchIds).stream().map(EmailFilterProposalStorage::toDto).toList();
  }

  /**
   * How many calls one run of a match's assistant recorded.
   *
   * @param userId the owner
   * @param matchId the match
   * @param conversationId the run's conversation
   * @return the count
   */
  public long countByRun(String userId, long matchId, String conversationId) {
    return emailFilterProposalDAO.countByRun(userId, matchId, conversationId);
  }

  /**
   * How many of a user's proposals are in a status.
   *
   * @param userId the owner
   * @param status the status
   * @return the count
   */
  public long countByStatus(String userId, String status) {
    return emailFilterProposalDAO.countByStatus(userId, status);
  }

  /**
   * Moves a proposal from a status to another when it is still in the first and not
   * expired.
   *
   * @param id the proposal
   * @param userId the owner
   * @param from the expected status
   * @param to the new status
   * @param now the decision's time, which it must not have expired at
   * @return true when this call moved it
   */
  public boolean claim(long id, String userId, String from, String to, Date now) {
    return emailFilterProposalDAO.claim(id, userId, from, to, now, now) == 1;
  }

  /**
   * Records how an approved call ended, when it is still running.
   *
   * @param id the proposal
   * @param userId the owner
   * @param to {@code DONE} or {@code FAILED}
   * @param result what the tool answered, or null
   * @param lastError why it failed, or null
   * @return true when written
   */
  public boolean finish(long id, String userId, String to, String result, String lastError) {
    return emailFilterProposalDAO.finish(id,
                                         userId,
                                         EmailFilterProposal.RUNNING,
                                         to,
                                         truncate(result, MAX_RESULT_LENGTH),
                                         truncate(lastError, MAX_ERROR_LENGTH)) == 1;
  }

  /**
   * Writes the assistant's reason on a waiting proposal of a match, and nothing else.
   *
   * @param id the proposal
   * @param userId the owner
   * @param matchId the match
   * @param rationale the reason
   * @return true when written
   */
  public boolean setRationale(long id, String userId, long matchId, String rationale) {
    return emailFilterProposalDAO.setRationale(id,
                                               userId,
                                               matchId,
                                               EmailFilterProposal.PROPOSED,
                                               truncate(rationale, MAX_RATIONALE_LENGTH)) == 1;
  }

  /**
   * Fails the owner's approved calls running since before a date.
   *
   * @param userId the owner
   * @param reason the reason
   * @param before a call decided before this is abandoned
   * @return how many
   */
  public int failStaleRunning(String userId, String reason, Date before) {
    return emailFilterProposalDAO.failStaleRunning(userId, EmailFilterProposal.RUNNING, EmailFilterProposal.FAILED, reason, before);
  }

  /**
   * Expires a user's proposals past their expiry.
   *
   * @param userId the owner
   * @param now the time
   * @return how many
   */
  public int expireDue(String userId, Date now) {
    return emailFilterProposalDAO.expireDue(userId, EmailFilterProposal.PROPOSED, EmailFilterProposal.EXPIRED, now);
  }

  /**
   * Expires the waiting proposals of a match, with a reason.
   *
   * @param userId the owner
   * @param matchId the match
   * @param reason the reason
   * @param now the time
   * @return how many
   */
  public int expireOfMatch(String userId, long matchId, String reason, Date now) {
    return emailFilterProposalDAO.expireOfMatch(userId, matchId, EmailFilterProposal.PROPOSED, EmailFilterProposal.EXPIRED, reason, now);
  }

  /**
   * What the owner decided on each rule's suggestions: per rule, how many were approved
   * (running, done or failed once approved), rejected, expired unanswered, handed to the
   * chat, and how many still wait. Read from the proposals themselves, which live as long
   * as the matches they belong to (the rules' log retention). Call {@link #expireDue}
   * first for the expired ones to be counted as such.
   *
   * @param userId the owner
   * @return the counts, one per rule that has any, by rule id
   */
  public List<EmailFilterSuggestionCounts> countByFilter(String userId) {
    Map<Long, Map<String, Long>> byFilter = new TreeMap<>();
    for (Object[] row : emailFilterProposalDAO.countByFilterAndStatus(userId)) {
      byFilter.computeIfAbsent((Long) row[0], key -> new HashMap<>()).merge((String) row[1], ((Number) row[2]).longValue(), Long::sum);
    }
    Map<Long, Long> superseded = new HashMap<>();
    for (Object[] row : emailFilterProposalDAO.countByFilterForReason(userId,
                                                                     EmailFilterProposal.EXPIRED,
                                                                     EmailFilterProposal.SUPERSEDED)) {
      superseded.put((Long) row[0], ((Number) row[1]).longValue());
    }
    return byFilter.entrySet().stream().map(entry -> {
      Map<String, Long> counts = entry.getValue();
      long approved = counts.getOrDefault(EmailFilterProposal.RUNNING, 0L) + counts.getOrDefault(EmailFilterProposal.DONE, 0L)
          + counts.getOrDefault(EmailFilterProposal.FAILED, 0L);
      long expired = Math.max(0L, counts.getOrDefault(EmailFilterProposal.EXPIRED, 0L) - superseded.getOrDefault(entry.getKey(), 0L));
      return new EmailFilterSuggestionCounts(entry.getKey(),
                                             approved,
                                             counts.getOrDefault(EmailFilterProposal.REJECTED, 0L),
                                             expired,
                                             counts.getOrDefault(EmailFilterProposal.HANDED_OVER, 0L),
                                             counts.getOrDefault(EmailFilterProposal.PROPOSED, 0L));
    }).toList();
  }

  /**
   * The key of a call: SHA-256, in lower-case hex, of the tool's name and of the
   * arguments' canonical JSON (objects' keys in order), so that one call made twice with
   * its keys in another order is still one call. Arguments that are not JSON are hashed
   * as they are.
   *
   * @param toolName the tool
   * @param arguments the arguments
   * @return the hash
   */
  public static String callHash(String toolName, String arguments) {
    return EmailFilterStorage.hash(toolName + "\n" + canonicalJson(arguments));
  }

  /**
   * The canonical form of a JSON text: the same value, objects' keys in order, no
   * whitespace.
   *
   * @param json the text
   * @return its canonical form, or the text itself when it is not JSON
   */
  static String canonicalJson(String json) {
    if (json == null) {
      return "";
    }
    try {
      Object value = CANONICAL.readValue(json, Object.class);
      return CANONICAL.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return json;
    }
  }

  /**
   * Copies the mutable part of a proposal onto its entity.
   *
   * @param entity the entity
   * @param proposal the proposal
   */
  private static void write(EmailFilterProposalEntity entity, EmailFilterProposal proposal) {
    entity.setToolTitle(truncate(proposal.getToolTitle(), MAX_TITLE_LENGTH));
    entity.setToolDescription(truncate(proposal.getToolDescription(), MAX_DESCRIPTION_LENGTH));
    entity.setArguments(proposal.getArguments() == null ? "{}" : proposal.getArguments());
    entity.setRationale(truncate(proposal.getRationale(), MAX_RATIONALE_LENGTH));
    entity.setStatus(proposal.getStatus());
    entity.setCreatedDate(date(proposal.getCreatedDate()));
    entity.setExpiresDate(date(proposal.getExpiresDate()));
    entity.setDecidedDate(date(proposal.getDecidedDate()));
    entity.setConversationId(proposal.getConversationId());
    entity.setResult(truncate(proposal.getResult(), MAX_RESULT_LENGTH));
    entity.setLastError(truncate(proposal.getLastError(), MAX_ERROR_LENGTH));
  }

  /**
   * The DTO of an entity.
   *
   * @param entity the entity
   * @return the proposal
   */
  private static EmailFilterProposal toDto(EmailFilterProposalEntity entity) {
    return new EmailFilterProposal(entity.getId(),
                                   entity.getMatchId(),
                                   entity.getFilterId(),
                                   entity.getToolName(),
                                   entity.getToolTitle(),
                                   entity.getToolDescription(),
                                   entity.getArguments(),
                                   entity.getRationale(),
                                   entity.getStatus(),
                                   time(entity.getCreatedDate()),
                                   time(entity.getExpiresDate()),
                                   time(entity.getDecidedDate()),
                                   entity.getConversationId(),
                                   entity.getResult(),
                                   entity.getLastError());
  }

  /**
   * A time as a date.
   *
   * @param time milliseconds, or null
   * @return the date, or null
   */
  private static Date date(Long time) {
    return time == null ? null : new Date(time);
  }

  /**
   * A date as a time.
   *
   * @param date the date, or null
   * @return milliseconds, or null
   */
  private static Long time(Date date) {
    return date == null ? null : date.getTime();
  }

  /**
   * A text cut to a length.
   *
   * @param text the text, or null
   * @param max the length
   * @return the text, cut
   */
  private static String truncate(String text, int max) {
    return text == null || text.length() <= max ? text : text.substring(0, max);
  }
}
