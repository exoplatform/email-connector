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
package org.exoplatform.emailConnector.plugin;

/**
 * The declaration that something answers the mail filters' assistant request: a bean
 * implementing this interface in the email-connector Spring context declares that a
 * listener answers {@link org.exoplatform.emailConnector.utils.EmailConnectorUtils#FILTER_AGENT_REQUESTED}
 * and runs the assistant of the matches it names, then calls back
 * {@link org.exoplatform.emailConnector.service.EmailFilterService#applyPostActions}.
 * <p>
 * A marker, with no method: the request goes out through the kernel
 * {@code ListenerService}, which says nothing of who listens, so the glue that listens
 * says it here as well. Without such a bean -- no AI add-on, or its profile off --
 * {@link org.exoplatform.emailConnector.service.EmailFilterService} does not queue the
 * assistant: a match is recorded as skipped and the rule's other actions run at once,
 * rather than wait for an answer that never comes.
 */
public interface EmailFilterAgentHandler {
}
