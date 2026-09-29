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
 * Makes a cached mail a content link of type {@code email}, inserted from the
 * editors with the {@code /mail} command, and the source of an AI chat (the chat
 * resolves its source chip through the content-link search).
 * <p>
 * A mail is private to its mailbox owner, and this plugin only ever resolves the
 * caller's own mails: a mail id resolves only when the caller owns it, and a text
 * searches the caller's own cached mails by subject and sender. A delegate's
 * access to a shared mailbox is not considered, neither read knows delegation.
 */
@Component
public class EmailContentLinkPlugin implements ContentLinkPlugin {

  public static final String                OBJECT_TYPE      = EmailConnectorUtils.EMAIL_FEATURE;

  private static final String               TITLE_KEY        = "contentLink.email";

  private static final String               ICON             = "fa fa-envelope";

  /**
   * The label of a chip whose mail its reader cannot see, "Private mail": the
   * same for a mail of somebody else and for a mail that does not exist, so the
   * chip shows the envelope and this label, never a subject nor a link.
   */
  private static final String               PRIVATE_TITLE_KEY = "contentLink.email.private";

  /**
   * The word typed after the slash. It differs from the object type, as the
   * activity's {@code /post} and the news' {@code /article} do: the menu matches
   * the command, the search and the chip carry the type, so the {@code email:<id>}
   * links already written keep resolving.
   */
  private static final String               COMMAND          = "mail";

  private static final String               NO_SUBJECT       = "(no subject)";

  private static final int                  MAX_TITLE_LENGTH = 100;

  /**
   * A drawer extension: a click on a mail chip asks the page for the
   * {@code content-link-email-drawer}, and the mailbox answers by opening that
   * mail in its reader over the page, so the reader of a note or a task stays
   * where they are. The permanent link stays the chip's {@code href}, for a
   * new tab or a copied address. A chip its reader cannot see is a private
   * chip: a click on it says the mail is private.
   */
  private static final ContentLinkExtension EXTENSION        = new ContentLinkExtension(OBJECT_TYPE,
                                                                                        TITLE_KEY,
                                                                                        ICON,
                                                                                        COMMAND,
                                                                                        true,
                                                                                        false,
                                                                                        PRIVATE_TITLE_KEY);

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
   * The {@code email} extension, listed in the editors' "/" menu as "Mail
   * (/mail)" and searched in place; its chips open the mail in the mailbox
   * drawer, over the page they are on.
   *
   * @return the {@code email} extension: its title key, icon, command, drawer
   *         flag and private label key
   */
  @Override
  public ContentLinkExtension getExtension() {
    return EXTENSION;
  }

  /**
   * Finds mails of the searching user.
   * <p>
   * A numeric keyword is first a mail id, and resolves to that mail when the user
   * owns it: that is how the AI chat and the chips resolve an {@code email:<id>}
   * link. Any other keyword, or an id matching none of the user's mails, searches
   * the user's own cached mails by subject and sender, newest first, in one
   * bounded query ({@link EmailBoxService#searchOwnEmailsForLink}).
   *
   * @param keyword the searched keyword: a mail id, or text found in a subject or
   *          a sender
   * @param identity the searching user
   * @param locale the user locale (unused)
   * @param offset the results offset
   * @param limit the results limit
   * @return the user's own matching mails, empty for an anonymous caller
   */
  @Override
  public List<ContentLinkSearchResult> search(String keyword, Identity identity, Locale locale, int offset, int limit) {
    String username = identity == null ? null : identity.getUserId();
    String text = StringUtils.trim(keyword);
    if (StringUtils.isBlank(username) || StringUtils.isEmpty(text) || limit <= 0) {
      return Collections.emptyList();
    }
    if (offset == 0 && isId(text)) {
      Email email = getOwnedEmail(text, username);
      if (email != null) {
        return Collections.singletonList(toResult(email));
      }
    }
    return emailBoxService.searchOwnEmailsForLink(username, text, offset, limit)
                          .stream()
                          // the query selects by owner; checked again so that no change
                          // in it can ever hand a subject to another user
                          .filter(email -> StringUtils.equals(email.getUserId(), username))
                          .map(this::toResult)
                          .toList();
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
   * The search result of a mail.
   *
   * @param email the user's own mail
   * @return its {@code email} link, titled by its subject
   */
  private ContentLinkSearchResult toResult(Email email) {
    return new ContentLinkSearchResult(OBJECT_TYPE, String.valueOf(email.getId()), getTitle(email), EXTENSION.getIcon());
  }

  /**
   * The label of a mail: its subject, else "(no subject)", so that a mail
   * without a subject still resolves (a blank title reads as "not found").
   *
   * @param email the mail
   * @return a non-blank label
   */
  private String getTitle(Email email) {
    String title = StringUtils.isBlank(email.getSubject()) ? NO_SUBJECT : email.getSubject().trim();
    return StringUtils.abbreviate(title, MAX_TITLE_LENGTH);
  }

}
