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
 * be the source of an AI chat (the chat resolves its source chip through the
 * content-link search) and be linked from other contents.
 * <p>
 * A mail is private to its mailbox owner, and this plugin only ever resolves the
 * caller's own mails: another user's mail, an unknown id or a non-numeric id
 * resolve to nothing. A delegate's access to a shared mailbox is not considered,
 * the owned lookup it relies on does not know delegation.
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
   * Resolves a mail of the searching user.
   * <p>
   * A numeric keyword is a mail id and resolves to that mail only when the user
   * owns it. A text keyword resolves to nothing: the only owner-scoped text
   * search over the mailbox cache loads the whole mailbox in memory and answers
   * with IMAP UIDs, not with the ids a content link carries, so it is not used
   * from a picker that fires on every keystroke.
   *
   * @param keyword the searched keyword, a mail id to resolve anything
   * @param identity the searching user
   * @param locale the user locale (unused)
   * @param offset the results offset
   * @param limit the results limit
   * @return the user's own mail with that id, or an empty list
   */
  @Override
  public List<ContentLinkSearchResult> search(String keyword, Identity identity, Locale locale, int offset, int limit) {
    String username = identity == null ? null : identity.getUserId();
    if (offset > 0 || limit <= 0 || !isId(StringUtils.trim(keyword))) {
      return Collections.emptyList();
    }
    Email email = getOwnedEmail(StringUtils.trim(keyword), username);
    if (email == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(new ContentLinkSearchResult(OBJECT_TYPE,
                                                                 String.valueOf(email.getId()),
                                                                 getTitle(email),
                                                                 EXTENSION.getIcon()));
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
