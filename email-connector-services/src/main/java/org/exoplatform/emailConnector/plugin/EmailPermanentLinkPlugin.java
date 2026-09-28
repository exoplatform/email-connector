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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.services.security.Identity;

import io.meeds.portal.permlink.model.PermanentLinkObject;
import io.meeds.portal.permlink.plugin.PermanentLinkPlugin;
import io.meeds.portal.permlink.service.PermanentLinkService;
import jakarta.annotation.PostConstruct;

/**
 * The permanent link of a mail.
 * <p>
 * Social's content-link registry computes the URI of every link it returns
 * through the permanent-link service, and fails the link when no plugin serves
 * its type: without this plugin a mail could never be resolved as a content
 * link. The link opens that mail in the mailbox's reader; the mail itself stays
 * readable by its owner only, since the reader fetches it as the viewer.
 */
@Component
public class EmailPermanentLinkPlugin implements PermanentLinkPlugin {

  public static final String      OBJECT_TYPE = EmailConnectorUtils.EMAIL_FEATURE;

  public static final String      URL_FORMAT      = "/portal/%s?openEmailBox=true";

  /** The mailbox URL, followed by the IMAP UID of the mail and its folder key. */
  public static final String      MAIL_URL_FORMAT = URL_FORMAT + "&mailRemoteId=%d&folder=%s";

  @Autowired
  private UserPortalConfigService portalConfigService;

  @Autowired
  private PermanentLinkService    permanentLinkService;

  @Autowired
  private UserACL                 userAcl;

  @Autowired
  private EmailBoxService         emailBoxService;

  /**
   * Registers this plugin in the permanent-link service, the way the content
   * add-on registers its news plugin.
   */
  @PostConstruct
  public void init() {
    permanentLinkService.addPlugin(this);
  }

  /**
   * @return {@code email}
   */
  @Override
  public String getObjectType() {
    return OBJECT_TYPE;
  }

  /**
   * A mail is accessible to its mailbox owner only. The decision is the one
   * {@link EmailAclPlugin} already takes, reached through {@link UserACL}: an
   * unknown id answers {@code false} like another user's mail, so the answer
   * never tells whether a mail id exists.
   *
   * @param object the designated mail
   * @param identity the identity accessing it
   * @return {@code true} only when the mail belongs to that identity
   */
  @Override
  public boolean canAccess(PermanentLinkObject object, Identity identity) {
    return object != null && userAcl.hasAccessPermission(OBJECT_TYPE, object.getObjectId(), identity);
  }

  /**
   * The URL a mail link leads to: the mailbox, opened on the site's home page,
   * straight onto that mail in its reader.
   * <p>
   * The mail is named by what the reader opens a message from outside with -- its
   * IMAP UID and the key of the folder it sits in -- read from the cache when the
   * link is followed, so a mail moved since the link was written still opens where
   * it is now. The permanent-link service asks {@link #canAccess} first, so only the
   * owner is sent there; and the reader then fetches the mail as the viewer, so the
   * URL opens nothing for anybody else either. A mail the cache no longer holds, or
   * one the reader cannot open from outside (a draft), falls back to the mailbox.
   *
   * @param object the designated mail
   * @return the URL of that mail in the mailbox, or of the mailbox alone
   */
  @Override
  public String getDirectAccessUrl(PermanentLinkObject object) {
    String portal = portalConfigService.getMetaPortal();
    Email email = object == null ? null : findEmail(object.getObjectId());
    if (email == null || email.getMailRemoteId() == null || !isOpenableFolder(email.getFolder())) {
      return String.format(URL_FORMAT, portal);
    }
    return String.format(MAIL_URL_FORMAT,
                         portal,
                         email.getMailRemoteId(),
                         URLEncoder.encode(email.getFolder(), StandardCharsets.UTF_8));
  }

  /**
   * The cached mail an object id designates.
   *
   * @param objectId the cached mail's technical id, as the link stored it
   * @return the mail, or {@code null} for an id that is not a number or matches
   *         no cached mail
   */
  private Email findEmail(String objectId) {
    long emailId;
    try {
      emailId = Long.parseLong(objectId);
    } catch (NumberFormatException e) {
      return null;
    }
    return emailBoxService.getEmailById(emailId, null);
  }

  /**
   * Whether the reader can open a mail of that folder from outside the mailbox:
   * any built-in folder but the drafts, which have no UID of their own, or a
   * well-formed custom folder key.
   *
   * @param folder the folder key of the mail
   * @return {@code true} when the key can name the mail in the link
   */
  private boolean isOpenableFolder(String folder) {
    if (MailFolder.isBuiltIn(folder)) {
      return !MailFolder.DRAFTS.equals(folder);
    }
    try {
      MailFolder.customId(folder);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

}
