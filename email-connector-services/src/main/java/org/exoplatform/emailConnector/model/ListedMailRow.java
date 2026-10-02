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
 * A mail of a list that is not a folder's -- a search's hit, a mail of the "Suggestions"
 * view -- which the mailbox draws with the folder list's own row (EXO-90871): named by
 * the cached row it stands for, and given, from that row, what the folder list's rows
 * carry beside the envelope (EXO-90882) -- the line under the subject, the attachments,
 * the conversation's size and its unsent draft. The field names are the folder list's
 * own ({@link Email#getContent()}, and the {@code threadCount} / {@code threadHasDraft}
 * the mailbox stamps on a listed row), so the row reads a hit as it reads a listed mail.
 */
public interface ListedMailRow {

  /**
   * The local id of the cached row the mail stands for.
   *
   * @return the id, null when the mail is not in the local copy
   */
  Long getEmailId();

  /**
   * The folder the mail was listed from.
   *
   * @return the folder key
   */
  String getFolder();

  /**
   * Sets what the folder list carries of the message's content: its excerpt and its
   * attachments, never its body.
   *
   * @param content the content, without a body
   */
  void setContent(EmailContent content);

  /**
   * Sets how many messages the mail's conversation holds, as the folder list counts it.
   *
   * @param threadCount the count
   */
  void setThreadCount(Integer threadCount);

  /**
   * Sets whether the mail's conversation carries a draft the user has not sent.
   *
   * @param threadHasDraft whether it does
   */
  void setThreadHasDraft(Boolean threadHasDraft);
}
