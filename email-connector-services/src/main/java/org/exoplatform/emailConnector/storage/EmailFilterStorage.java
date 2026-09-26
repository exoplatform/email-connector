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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailFilterDAO;
import org.exoplatform.emailConnector.dao.EmailFilterMatchDAO;
import org.exoplatform.emailConnector.entity.EmailFilterEntity;
import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;
import org.exoplatform.emailConnector.model.AppliedAction;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.FilterAction;
import org.exoplatform.emailConnector.model.ServerRule;

import io.meeds.social.util.JsonUtils;

/**
 * The eXo rules and their matches, mapped to the service's DTOs. No business rule here:
 * every read is scoped to the owner the caller names, and the one decision taken at
 * this layer is the database's -- a second match of one rule on one mail is refused by
 * the unique key, and answered as "already handled".
 */
@Component
public class EmailFilterStorage {

  /** The longest error kept on a row. */
  private static final int    MAX_ERROR_LENGTH   = 1000;

  /** The longest subject kept on a match. */
  private static final int    MAX_SUBJECT_LENGTH = 500;

  /** The longest Message-ID kept on a match, the RFC 5322 line limit. */
  private static final int    MAX_HEADER_LENGTH  = 998;

  @Autowired
  private EmailFilterDAO      emailFilterDAO;

  @Autowired
  private EmailFilterMatchDAO emailFilterMatchDAO;

  /**
   * The JSON of what one match did, as {@code ACTIONS_APPLIED} holds it.
   *
   * @param postActionsState whether the post-actions wait for the assistant
   * @param actions what was done
   */
  public record AppliedActions(String postActionsState, List<AppliedAction> actions) {
  }

  /**
   * A user's rules, in evaluation order.
   *
   * @param userId the owner
   * @return the rules
   */
  public List<EmailFilter> getFilters(String userId) {
    return emailFilterDAO.findByUserId(userId).stream().map(EmailFilterStorage::toDto).toList();
  }

  /**
   * One rule of its owner.
   *
   * @param id the rule
   * @param userId the owner
   * @return the rule, or empty when it is not this user's
   */
  public Optional<EmailFilter> getFilter(long id, String userId) {
    return emailFilterDAO.findByIdAndUserId(id, userId).stream().findFirst().map(EmailFilterStorage::toDto);
  }

  /**
   * The rule of a user a keyword names.
   *
   * @param userId the owner
   * @param tagKeyword the keyword
   * @return the rule, or empty
   */
  public Optional<EmailFilter> getFilterByTag(String userId, String tagKeyword) {
    return emailFilterDAO.findByUserIdAndTagKeyword(userId, tagKeyword).stream().findFirst().map(EmailFilterStorage::toDto);
  }

  /**
   * How many enabled rules a user has.
   *
   * @param userId the owner
   * @return the count
   */
  public long countEnabled(String userId) {
    return emailFilterDAO.countEnabledByUserId(userId);
  }

  /**
   * The position a new rule of a user is appended at.
   *
   * @param userId the owner
   * @return one past the highest, 0 for the first
   */
  public int nextPosition(String userId) {
    Integer max = emailFilterDAO.findMaxPosition(userId);
    return max == null ? 0 : max + 1;
  }

  /**
   * Creates or replaces a rule of its owner. The counters are never written from here:
   * the sync owns them.
   *
   * @param userId the owner
   * @param filter the rule, its id null to create it
   * @param now the date of the write
   * @return the rule as stored
   * @throws IllegalArgumentException {@code emailConnector.filters.notFound} when the id
   *           names no rule of this user
   */
  public EmailFilter save(String userId, EmailFilter filter, Date now) {
    EmailFilterEntity entity;
    if (filter.getId() == null) {
      entity = new EmailFilterEntity();
      entity.setUserId(userId);
      entity.setCreatedDate(now);
    } else {
      entity = emailFilterDAO.findByIdAndUserId(filter.getId(), userId)
                             .stream()
                             .findFirst()
                             .orElseThrow(() -> new IllegalArgumentException("emailConnector.filters.notFound"));
    }
    entity.setName(filter.getName());
    entity.setEnabled(filter.isEnabled());
    entity.setPosition(filter.getPosition());
    entity.setKind(filter.getKind());
    entity.setMailboxScope(filter.getMailboxScope());
    entity.setMatchMode(filter.isMatchAll() ? "ALL" : "ANY");
    entity.setConditions(JsonUtils.toJsonString(filter.getConditions() == null ? List.of() : filter.getConditions()));
    entity.setActions(JsonUtils.toJsonString(filter.getActions() == null ? List.of() : filter.getActions()));
    entity.setStopProcessing(filter.isStopProcessing());
    entity.setTagKeyword(filter.getTagKeyword());
    entity.setServerRuleRef(filter.getServerRuleRef());
    entity.setAgentNameId(filter.getAgentNameId());
    entity.setLastError(truncate(filter.getLastError(), MAX_ERROR_LENGTH));
    if (filter.getActiveSince() != null) {
      entity.setActiveSince(new Date(filter.getActiveSince()));
    }
    entity.setUpdatedDate(now);
    return toDto(emailFilterDAO.saveAndFlush(entity));
  }

  /**
   * Deletes a rule of its owner. Its matches stay: the log outlives the rule.
   *
   * @param id the rule
   * @param userId the owner
   * @return true when deleted
   */
  public boolean delete(long id, String userId) {
    List<EmailFilterEntity> found = emailFilterDAO.findByIdAndUserId(id, userId);
    if (found.isEmpty()) {
      return false;
    }
    emailFilterDAO.delete(found.get(0));
    return true;
  }

  /**
   * Writes the positions of a user's rules.
   *
   * @param userId the owner
   * @param orderedIds every rule of the user, in the new order
   * @param now the date of the write
   */
  public void reorder(String userId, List<Long> orderedIds, Date now) {
    List<EmailFilterEntity> entities = emailFilterDAO.findByUserId(userId);
    for (EmailFilterEntity entity : entities) {
      int position = orderedIds.indexOf(entity.getId());
      if (position >= 0 && position != entity.getPosition()) {
        entity.setPosition(position);
        entity.setUpdatedDate(now);
      }
    }
    emailFilterDAO.saveAll(entities);
  }

  /**
   * Counts matches on a rule.
   *
   * @param id the rule
   * @param userId the owner
   * @param count how many
   * @param date when
   */
  public void addMatches(long id, String userId, long count, Date date) {
    emailFilterDAO.addMatches(id, userId, count, date);
  }

  /**
   * Switches a rule off with the reason.
   *
   * @param id the rule
   * @param userId the owner
   * @param error the reason
   * @param date when
   */
  public void disableWithError(long id, String userId, String error, Date date) {
    emailFilterDAO.disableWithError(id, userId, truncate(error, MAX_ERROR_LENGTH), date);
  }

  /**
   * Records a match of a rule on a mail, unless the rule already matched that mail.
   *
   * @param match the match, its id null
   * @param userId the owner
   * @return the match as stored; empty when the unique key refused it, i.e. the rule
   *         already handled the mail
   */
  public Optional<EmailFilterMatch> createMatch(EmailFilterMatch match, String userId) {
    EmailFilterMatchEntity entity = new EmailFilterMatchEntity();
    entity.setUserId(userId);
    entity.setFilterId(match.getFilterId());
    entity.setMailHeaderId(truncate(match.getMailHeaderId(), MAX_HEADER_LENGTH));
    entity.setMailHeaderHash(hash(match.getMailHeaderId()));
    entity.setCreatedDate(new Date(match.getMatchedDate()));
    write(entity, match);
    try {
      return Optional.of(toDto(emailFilterMatchDAO.saveAndFlush(entity)));
    } catch (DataIntegrityViolationException e) {
      return Optional.empty();
    }
  }

  /**
   * Writes the mutable part of a match of its owner: what was done, the assistant's
   * state and outcome.
   *
   * @param match the match
   * @param userId the owner
   * @return the match as stored
   * @throws IllegalArgumentException {@code emailConnector.filters.match.notFound} when
   *           the id names no match of this user
   */
  public EmailFilterMatch updateMatch(EmailFilterMatch match, String userId) {
    EmailFilterMatchEntity entity = emailFilterMatchDAO.findByIdAndUserId(match.getId(), userId)
                                                       .stream()
                                                       .findFirst()
                                                       .orElseThrow(() -> new IllegalArgumentException("emailConnector.filters.match.notFound"));
    write(entity, match);
    return toDto(emailFilterMatchDAO.saveAndFlush(entity));
  }

  /**
   * One match of its owner.
   *
   * @param id the match
   * @param userId the owner
   * @return the match, or empty
   */
  public Optional<EmailFilterMatch> getMatch(long id, String userId) {
    return emailFilterMatchDAO.findByIdAndUserId(id, userId).stream().findFirst().map(EmailFilterStorage::toDto);
  }

  /**
   * The matches of one rule on some mails.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param mailHeaderIds the mails' Message-IDs
   * @return the matches found
   */
  public List<EmailFilterMatch> getMatches(String userId, long filterId, Collection<String> mailHeaderIds) {
    if (mailHeaderIds == null || mailHeaderIds.isEmpty()) {
      return List.of();
    }
    return emailFilterMatchDAO.findByFilterAndMails(userId, filterId, mailHeaderIds.stream().map(EmailFilterStorage::hash).toList())
                              .stream()
                              .map(EmailFilterStorage::toDto)
                              .toList();
  }

  /**
   * The matches on one mail.
   *
   * @param userId the owner
   * @param mailHeaderId the mail's Message-ID
   * @return the matches, newest first
   */
  public List<EmailFilterMatch> getMatchesOfMail(String userId, String mailHeaderId) {
    return emailFilterMatchDAO.findByMail(userId, hash(mailHeaderId)).stream().map(EmailFilterStorage::toDto).toList();
  }

  /**
   * The log of one rule.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param limit how many, newest first
   * @return the matches
   */
  public List<EmailFilterMatch> getLog(String userId, long filterId, int limit) {
    return emailFilterMatchDAO.findByFilter(userId, filterId, PageRequest.of(0, limit))
                              .stream()
                              .map(EmailFilterStorage::toDto)
                              .toList();
  }

  /**
   * A user's matches in an assistant status, oldest first.
   *
   * @param userId the owner
   * @param agentStatus the status
   * @param limit how many
   * @return the matches
   */
  public List<EmailFilterMatch> getMatchesByAgentStatus(String userId, String agentStatus, int limit) {
    return emailFilterMatchDAO.findByAgentStatus(userId, agentStatus, PageRequest.of(0, limit))
                              .stream()
                              .map(EmailFilterStorage::toDto)
                              .toList();
  }

  /**
   * A user's matches the assistant's handler takes up, oldest first: the waiting ones,
   * and the running ones whose last write is older than a date.
   *
   * @param userId the owner
   * @param runningBefore a running match last written before this is taken up again
   * @param limit how many
   * @return the matches
   */
  public List<EmailFilterMatch> getMatchesDueForAgent(String userId, Date runningBefore, int limit) {
    return emailFilterMatchDAO.findDueForAgent(userId,
                                               EmailFilterMatch.AGENT_PENDING,
                                               EmailFilterMatch.AGENT_RUNNING,
                                               runningBefore,
                                               PageRequest.of(0, limit))
                              .stream()
                              .map(EmailFilterStorage::toDto)
                              .toList();
  }

  /**
   * How many of a user's matches are in an assistant status.
   *
   * @param userId the owner
   * @param agentStatus the status
   * @return the count
   */
  public long countByAgentStatus(String userId, String agentStatus) {
    return emailFilterMatchDAO.countByAgentStatus(userId, agentStatus);
  }

  /**
   * How many assistant runs a user's matches were queued for since a date.
   *
   * @param userId the owner
   * @param statuses the statuses of a queued run
   * @param since the date
   * @return the count
   */
  public long countQueuedSince(String userId, Collection<String> statuses, Date since) {
    return emailFilterMatchDAO.countQueuedSince(userId, statuses, since);
  }

  /**
   * Deletes a user's matches older than a date.
   *
   * @param userId the owner
   * @param before the oldest date kept
   * @return how many
   */
  public int pruneMatches(String userId, Date before) {
    return emailFilterMatchDAO.deleteOlderThan(userId, before);
  }

  /**
   * The SHA-256 of a Message-ID, in lower-case hex: the column the keys use.
   *
   * @param mailHeaderId the Message-ID
   * @return the hash
   */
  public static String hash(String mailHeaderId) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
                                   .digest(String.valueOf(mailHeaderId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Copies the mutable part of a match onto its entity.
   *
   * @param entity the entity
   * @param match the match
   */
  private static void write(EmailFilterMatchEntity entity, EmailFilterMatch match) {
    entity.setMailRemoteId(match.getMailRemoteId());
    entity.setFolder(match.getFolder());
    entity.setSubject(truncate(match.getSubject(), MAX_SUBJECT_LENGTH));
    entity.setMatchedDate(new Date(match.getMatchedDate()));
    entity.setActionsApplied(JsonUtils.toJsonString(new AppliedActions(match.getPostActionsState(),
                                                                        match.getActions() == null ? List.of()
                                                                                                   : match.getActions())));
    entity.setAgentStatus(match.getAgentStatus() == null ? EmailFilterMatch.AGENT_NONE : match.getAgentStatus());
    entity.setAgentNameId(match.getAgentNameId());
    entity.setAgentConversationId(match.getAgentConversationId());
    entity.setAgentOutput(match.getAgentOutput());
    entity.setAgentDate(match.getAgentDate() == null ? null : new Date(match.getAgentDate()));
    entity.setAgentAttempts(match.getAgentAttempts());
    entity.setLastError(truncate(match.getLastError(), MAX_ERROR_LENGTH));
  }

  /**
   * Maps a rule's row.
   *
   * @param entity the row
   * @return the rule
   */
  static EmailFilter toDto(EmailFilterEntity entity) {
    EmailFilter filter = new EmailFilter();
    filter.setId(entity.getId());
    filter.setName(entity.getName());
    filter.setEnabled(entity.isEnabled());
    filter.setPosition(entity.getPosition());
    filter.setKind(entity.getKind());
    filter.setMailboxScope(entity.getMailboxScope());
    filter.setMatchAll(!"ANY".equals(entity.getMatchMode()));
    filter.setConditions(readConditions(entity.getConditions()));
    filter.setActions(readActions(entity.getActions()));
    filter.setStopProcessing(entity.isStopProcessing());
    filter.setTagKeyword(entity.getTagKeyword());
    filter.setServerRuleRef(entity.getServerRuleRef());
    filter.setAgentNameId(entity.getAgentNameId());
    filter.setMatchCount(entity.getMatchCount());
    filter.setLastMatchDate(entity.getLastMatchDate() == null ? null : entity.getLastMatchDate().getTime());
    filter.setLastError(entity.getLastError());
    filter.setActiveSince(entity.getActiveSince() == null ? null : entity.getActiveSince().getTime());
    filter.setCreatedDate(entity.getCreatedDate() == null ? null : entity.getCreatedDate().getTime());
    filter.setUpdatedDate(entity.getUpdatedDate() == null ? null : entity.getUpdatedDate().getTime());
    return filter;
  }

  /**
   * Maps a match's row.
   *
   * @param entity the row
   * @return the match, its rule's name not resolved
   */
  static EmailFilterMatch toDto(EmailFilterMatchEntity entity) {
    EmailFilterMatch match = new EmailFilterMatch();
    match.setId(entity.getId());
    match.setFilterId(entity.getFilterId());
    match.setMailHeaderId(entity.getMailHeaderId());
    match.setMailRemoteId(entity.getMailRemoteId());
    match.setFolder(entity.getFolder());
    match.setSubject(entity.getSubject());
    match.setMatchedDate(entity.getMatchedDate() == null ? null : entity.getMatchedDate().getTime());
    AppliedActions applied = readApplied(entity.getActionsApplied());
    match.setPostActionsState(applied.postActionsState());
    match.setActions(applied.actions() == null ? List.of() : applied.actions());
    match.setAgentStatus(entity.getAgentStatus());
    match.setAgentNameId(entity.getAgentNameId());
    match.setAgentConversationId(entity.getAgentConversationId());
    match.setAgentOutput(entity.getAgentOutput());
    match.setAgentDate(entity.getAgentDate() == null ? null : entity.getAgentDate().getTime());
    match.setAgentAttempts(entity.getAgentAttempts());
    match.setLastError(entity.getLastError());
    return match;
  }

  /**
   * Reads the conditions' JSON. A value the JSON reader refuses is null, never an empty
   * list -- no condition at all would match every mail: the service validates every
   * rule it runs, and switches off one it cannot read.
   *
   * @param json the column
   * @return the conditions, or null when unreadable
   */
  private static List<ServerRule.Condition> readConditions(String json) {
    try {
      ServerRule.Condition[] read = json == null ? null : JsonUtils.fromJsonString(json, ServerRule.Condition[].class);
      return read == null ? null : Arrays.asList(read);
    } catch (RuntimeException e) {
      return null; // NOSONAR the service tells unreadable from empty
    }
  }

  /**
   * Reads the actions' JSON, as {@link #readConditions} does.
   *
   * @param json the column
   * @return the actions, or null when unreadable
   */
  private static List<FilterAction> readActions(String json) {
    try {
      FilterAction[] read = json == null ? null : JsonUtils.fromJsonString(json, FilterAction[].class);
      return read == null ? null : Arrays.asList(read);
    } catch (RuntimeException e) {
      return null; // NOSONAR the service tells unreadable from empty
    }
  }

  /**
   * Reads what a match did.
   *
   * @param json the column
   * @return the record, empty when unreadable
   */
  private static AppliedActions readApplied(String json) {
    try {
      AppliedActions read = json == null ? null : JsonUtils.fromJsonString(json, AppliedActions.class);
      return read == null ? new AppliedActions(null, List.of()) : read;
    } catch (RuntimeException e) {
      return new AppliedActions(null, List.of());
    }
  }

  /**
   * Cuts a text to a column's width.
   *
   * @param text the text
   * @param max the width
   * @return the text, cut; null for null
   */
  private static String truncate(String text, int max) {
    return text == null || text.length() <= max ? text : text.substring(0, max);
  }
}
