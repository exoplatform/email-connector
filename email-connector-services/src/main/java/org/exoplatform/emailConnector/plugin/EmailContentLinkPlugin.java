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

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import io.meeds.social.cms.model.ContentLinkExtension;
import io.meeds.social.cms.model.ContentLinkSearchResult;
import io.meeds.social.cms.plugin.ContentLinkPlugin;
import io.meeds.social.cms.service.ContentLinkPluginService;
import jakarta.annotation.PostConstruct;

/**
 * Makes a cached mail a content link of type {@code email}, so that a mail can
 * be the source of an AI chat and be linked from other contents.
 * <p>
 * Social's content-link service resolves a mail id, the numeric keyword the AI
 * chat's source chip searches for, without calling {@link #search}: it reads the
 * title from {@link #getContentTitle} and the access from the mail's ACL plugin
 * through {@code UserACL}. A mail is private to its mailbox owner, and this
 * plugin only ever gives the title of the caller's own mails: another user's
 * mail, an unknown id or a non-numeric id resolve to nothing. A delegate's access
 * to a shared mailbox is not considered, the owned lookup it relies on does not
 * know delegation.
 */
@Component
public class EmailContentLinkPlugin implements ContentLinkPlugin {

  public static final String                OBJECT_TYPE      = EmailConnectorUtils.EMAIL_FEATURE;

  private static final String               TITLE_KEY        = "contentLink.email";

  private static final String               ICON             = "fa fa-envelope";

  private static final String               COMMAND          = "email";

  private static final int                  MAX_TITLE_LENGTH = 100;

  private static final ContentLinkExtension EXTENSION        = new ContentLinkExtension(OBJECT_TYPE,
                                                                                        TITLE_KEY,
                                                                                        ICON,
                                                                                        COMMAND,
                                                                                        false,
                                                                                        true);

  @Autowired
  private ContentLinkPluginService          contentLinkPluginService;

  @Autowired
  private EmailBoxService                   emailBoxService;

  /**
   * Registers this plugin in Social's content-link registry, the way the
   * content add-on registers its news plugin.
   */
  @PostConstruct
  public void init() {
    contentLinkPluginService.addPlugin(this);
  }

  /**
   * The {@code email} extension, hidden from the editors' link picker: a mail
   * is only ever resolved by its id (the AI chat's source), a text search finds
   * nothing, so offering "Mail" in every editor would offer an empty list.
   *
   * @return the {@code email} extension: its title key, icon and command
   */
  @Override
  public ContentLinkExtension getExtension() {
    return EXTENSION;
  }

  /**
   * Answers nothing, whatever the keyword.
   * <p>
   * A mail id never reaches this method: Social's content-link service resolves a
   * numeric keyword itself, through {@link #getContentTitle} and the mail's ACL
   * plugin, and calls a plugin's search for a text keyword only. A text keyword
   * resolves to nothing: the only owner-scoped text search over the mailbox cache
   * loads the whole mailbox in memory and answers with IMAP UIDs, not with the ids
   * a content link carries, so it is not used from a picker that fires on every
   * keystroke.
   *
   * @param keyword the searched text (unused)
   * @param identity the searching user (unused)
   * @param locale the user locale (unused)
   * @param offset the results offset (unused)
   * @param limit the results limit (unused)
   * @return an empty list
   */
  @Override
  public List<ContentLinkSearchResult> search(String keyword, Identity identity, Locale locale, int offset, int limit) {
    return Collections.emptyList();
  }

  /**
   * The title of a mail, for the user of the current request only.
   * <p>
   * The SPI passes no identity, so the current {@link ConversationState} says
   * who is asking; with no current user, or for a mail that is not theirs, the
   * answer is {@code null}, which the registry reads as "not found". A subject
   * never crosses from one user to another.
   *
   * @param objectId the mail id
   * @param locale the user locale (unused)
   * @return the subject of the current user's mail, or {@code null}
   */
  @Override
  public String getContentTitle(String objectId, Locale locale) {
    Email email = getOwnedEmail(objectId, getCurrentUsername());
    return email == null ? null : getTitle(email);
  }

  /**
   * Reads a mail only when it belongs to the given user.
   *
   * @param objectId the candidate mail id, an untrusted string
   * @param username the user who must own the mail
   * @return the mail, or {@code null} when the id is not a number, matches no
   *         mail, or designates somebody else's mail
   */
  private Email getOwnedEmail(String objectId, String username) {
    if (StringUtils.isBlank(username) || !StringUtils.isNumeric(objectId)) {
      return null;
    }
    long emailId;
    try {
      emailId = Long.parseLong(objectId);
    } catch (NumberFormatException e) {
      // beyond the long range: no mail of ours either
      return null;
    }
    try {
      Email email = emailBoxService.getOwnedEmailById(emailId, username);
      // the owned lookup already refuses another owner; checked again so that a
      // change in it can never turn into a subject shown to the wrong user
      return email != null && StringUtils.equals(email.getUserId(), username) ? email : null;
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  /**
   * @return the user of the current request, or {@code null} when none
   */
  private String getCurrentUsername() {
    ConversationState state = ConversationState.getCurrent();
    return state == null || state.getIdentity() == null ? null : state.getIdentity().getUserId();
  }

  /**
   * The label of a mail: its subject, else its sender, so that a mail without a
   * subject still resolves (a blank title reads as "not found").
   *
   * @param email the mail
   * @return a non-blank label
   */
  private String getTitle(Email email) {
    String title = email.getSubject();
    if (StringUtils.isBlank(title) && email.getSender() != null) {
      title = StringUtils.firstNonBlank(email.getSender().getName(), email.getSender().getAddress());
    }
    if (StringUtils.isBlank(title)) {
      title = "#" + email.getId();
    }
    return StringUtils.abbreviate(title.trim(), MAX_TITLE_LENGTH);
  }

}
