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

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailFilterProposalDAO;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.StoredFilterProposal;

/**
 * The tool calls mail filters' assistants recorded in the email-connector's own store,
 * before they moved to the AI add-on's shared proposals (EXO-90956): read once more, page
 * by page, by the move. Nothing writes them any more.
 */
@Component
public class EmailFilterProposalStorage {

  @Autowired
  private EmailFilterProposalDAO emailFilterProposalDAO;

  /**
   * A page of every user's stored proposals, by id.
   *
   * @param afterId the id the page starts after, 0 for the first page
   * @param limit the page size
   * @return the proposals with their owners
   */
  public List<StoredFilterProposal> getPage(long afterId, int limit) {
    return emailFilterProposalDAO.findPage(afterId, PageRequest.of(0, Math.max(1, limit)))
                                 .stream()
                                 .map(entity -> new StoredFilterProposal(entity.getUserId(), toDto(entity)))
                                 .toList();
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
                                   entity.getLastError(),
                                   false);
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

}
