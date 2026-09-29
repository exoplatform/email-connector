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
package org.exoplatform.emailConnector.model;

/**
 * What the owner decided on the suggestions of one of their rules' assistant, over the
 * rules' log retention: the numbers that tell whether the assistant is worth running on
 * more mail.
 *
 * @param filterId the rule
 * @param approved how many were approved, whatever the tool then did
 * @param rejected how many were rejected
 * @param expired how many expired unanswered; those a later run of the assistant set
 *          aside are not counted
 * @param handedOver how many were continued in the AI chat
 * @param waiting how many still wait for a decision
 */
public record EmailFilterSuggestionCounts(long filterId, long approved, long rejected, long expired, long handedOver, long waiting) {
}
