/**
 * Copyright (C) 2025 eXo Platform SAS
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
package org.exoplatform.emailConnector.rest;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.AbsenceSettings;
import org.exoplatform.emailConnector.model.AbsenceStatus;
import org.exoplatform.emailConnector.model.OwnerAbsenceStatus;
import org.exoplatform.emailConnector.model.DelegationFolders;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.FolderAccessUpdate;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.ReadReceiptSettings;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.rest.model.DelegationFoldersRequest;
import org.exoplatform.emailConnector.rest.model.DelegationInviteRequest;
import org.exoplatform.emailConnector.rest.model.DelegationPreferencesRequest;
import org.exoplatform.emailConnector.rest.model.DelegationSendModeRequest;
import org.exoplatform.emailConnector.service.EmailAbsenceService;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.ReadReceiptService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/user-email-setting")
@Tag(name = "/email-connector/rest/user-email-setting", description = "Manages User Email Setting")
public class UserEmailSettingRest {

  /**
   * How long a browser may cache the signature image privately. Safe to be
   * long: the image URL carries a version parameter that changes whenever the
   * image does, so a stale cache is a URL nobody asks for any more.
   */
  private static final long       SIGNATURE_IMAGE_CACHE_DAYS = 365;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailSignatureService   emailSignatureService;

  @Autowired
  private ReadReceiptService      readReceiptService;

  @Autowired
  private EmailDelegationService  emailDelegationService;

  @Autowired
  private EmailAbsenceService     emailAbsenceService;

  /**
   * Connects the caller to a connector whose provider asks them for nothing - the
   * one-click path. The mailbox is still opened first, with the provider's own
   * material, and nothing is recorded unless it answered.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param emailConnectorId the connector preset to connect to
   */
  @PostMapping("/connect")
  @Secured("users")
  @Operation(summary = "Connects to an email connector that requires no user action", method = "POST",
      description = "Opens the mailbox with the material the configured provider produces, and records the connection "
          + "only when it answered. Refuses a provider that expects the user to type credentials.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Connected"),
      @ApiResponse(responseCode = "400", description = "The provider expects the user to supply something"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation"),
      @ApiResponse(responseCode = "500", description = "The mailbox refused the service account") })
  public void connectThroughProvider(HttpServletRequest request,
                                     @Parameter(description = "Email connector to connect to", required = true)
                                     @RequestParam(name = "emailConnectorId")
                                     long emailConnectorId) {
    try {
      userEmailSettingService.connectThroughProvider(emailConnectorId, request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Sets user email setting.
   *
   * @param request the caller's request, for the acting user
   * @param broadcast broadcast email box cleanup event
   * @param userEmailSetting the user email setting
   */
  @PutMapping()
  @Secured("users")
  @Operation(summary = "Sets user email setting", method = "PUT", description = "This will set user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void connectUserEmailSetting(HttpServletRequest request,
                                      @Parameter(description = "Broadcast email box cleanup event", required = true)
                                      @RequestParam(name = "broadcast", defaultValue = "true")
                                      boolean broadcast,
                                      @RequestBody
                                      UserEmailSetting userEmailSetting) {
    try {
      userEmailSettingService.connectUserEmailSetting(userEmailSetting, request.getRemoteUser(), broadcast);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping()
  @Secured("users")
  @Operation(summary = "Gets user email setting", method = "GET", description = "This will get user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public UserEmailSetting getUserEmailSetting(HttpServletRequest request) {
    return userEmailSettingService.getUserEmailSetting(request.getRemoteUser());
  }

  @PutMapping("/preferences")
  @Secured("users")
  @Operation(summary = "Updates the user's email notification / default-view preferences",
             method = "PUT",
             description = "Updates only the notification categories and default category view, without reconnecting the mailbox")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"), })
  public void updateEmailPreferences(HttpServletRequest request,
                                     @RequestBody
                                     UserEmailSetting userEmailSetting) {
    userEmailSettingService.updateEmailPreferences(request.getRemoteUser(),
                                                   userEmailSetting.getNotifyAllCategories(),
                                                   userEmailSetting.getNotifyCategories(),
                                                   userEmailSetting.getDefaultCategoryView());
  }

  @PutMapping("/address-book")
  @Secured("users")
  @Operation(summary = "Turns the CardDAV address-book sync on or off for the caller",
             method = "PUT",
             description = "Stores whether the caller's address book should sync. It signs in with the mailbox's own credentials, so there is nothing else to configure.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"), })
  public void updateAddressBookBinding(HttpServletRequest request,
                                       @RequestBody
                                       UserEmailSetting userEmailSetting) {
    userEmailSettingService.updateAddressBookBinding(request.getRemoteUser(), userEmailSetting.getCarddavEnabled());
  }

  /**
   * Turns the automatic address-book push on or off for the caller. Stores whether a
   * contact the caller authors through the contact form should be published to their
   * CardDAV address book on its own, with no second click.
   *
   * @param request the caller's request, for the acting user
   * @param userEmailSetting the user email setting
   */
  @PutMapping("/address-book/auto-publish")
  @Secured("users")
  @Operation(summary = "Turns the automatic address-book push on or off for the caller",
             method = "PUT",
             description = "Stores whether a contact the caller authors through the contact form should be published to their CardDAV address book on its own, with no second click. Off by default, and off for every user whose settings predate it. It never covers the bulk or unattended paths - a .vcf import, the automatic collection from mail, the hand-over of a rebound mailbox, a directory colleague - which stay publishable only by an explicit click. Its own endpoint rather than a field of the address-book binding, because changing the binding releases the contacts of the book being left and a preference about future saves must not.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"), })
  public void updateAddressBookAutoPublish(HttpServletRequest request,
                                           @RequestBody
                                           UserEmailSetting userEmailSetting) {
    userEmailSettingService.updateAddressBookAutoPublish(request.getRemoteUser(), userEmailSetting.getCarddavAutoPublish());
  }

  /**
   * The caller's read-receipt preferences.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the effective preferences
   */
  @GetMapping("/read-receipts")
  @Secured("users")
  @Operation(summary = "Gets the caller's read-receipt preferences", method = "GET",
             description = "Answers whether the composer requests a read receipt by default (requestByDefault, false unless chosen), what to do when a received message asks for one (responsePolicy: ASK, the default; NEVER; ALWAYS), and whether the administrator allows ALWAYS (alwaysAllowed; when not, a stored ALWAYS is answered as ASK).")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public ReadReceiptSettings getReadReceiptSettings(HttpServletRequest request) {
    return readReceiptService.getSettings(request.getRemoteUser());
  }

  /**
   * Stores the caller's read-receipt preferences.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param settings the preferences
   * @return the preferences as they now stand
   */
  @PutMapping("/read-receipts")
  @Secured("users")
  @Operation(summary = "Stores the caller's read-receipt preferences", method = "PUT",
             description = "Stores requestByDefault and responsePolicy (a missing policy is stored as ASK; alwaysAllowed is ignored). ALWAYS is refused while the administrator disables it (email.connector.readReceipt.allowAlways=false). Even under ALWAYS a receipt is only sent without asking when the request's Return-Path matches its one address, the caller is a To or Cc recipient, and the message came through no mailing list and was not machine-generated; every other case still asks.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "ALWAYS while the administrator disables it, or no body (emailConnector.readReceipt.notAllowed)"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public ReadReceiptSettings saveReadReceiptSettings(HttpServletRequest request,
                                                     @RequestBody
                                                     ReadReceiptSettings settings) {
    try {
      return readReceiptService.saveSettings(request.getRemoteUser(), settings);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Deletes user email setting.
   *
   * @param request the caller's request, for the acting user
   */
  @DeleteMapping()
  @Secured("users")
  @Operation(summary = "Deletes user email setting", method = "DELETE", description = "This will delete user email setting")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void deleteUserEmailSetting(HttpServletRequest request) {
    try {
      userEmailSettingService.deleteUserEmailSetting(request.getRemoteUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Gets the caller's email signature. Answers the stored preference (the on/off switch
   * and the caller's own markup, when they wrote one) together with the default signature
   * computed from their profile as it stands right now - name linked to the profile page,
   * position and company, location, the phone the platform is configured to display, and
   * the signature image.
   *
   * @param request the caller's request, for the acting user
   * @return the email signature
   */
  @GetMapping("/signature")
  @Secured("users")
  @Operation(summary = "Gets the caller's email signature",
             method = "GET",
             description = "Answers the stored preference (the on/off switch and the caller's own markup, when they wrote one) together with the default signature computed from their profile as it stands right now - name linked to the profile page, position and company, location, the phone the platform is configured to display, and the signature image. The image URL in the markup points at this resource's own /signature/image, which the send path replaces with an embedded cid: part so external recipients see it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public EmailSignature getEmailSignature(HttpServletRequest request) {
    return emailSignatureService.getEmailSignature(request.getRemoteUser());
  }

  /**
   * Stores the caller's email signature preference. Stores the on/off switch and the
   * caller's own markup.
   *
   * @param request the caller's request, for the acting user
   * @param signature the signature
   */
  @PutMapping("/signature")
  @Secured("users")
  @Operation(summary = "Stores the caller's email signature preference",
             method = "PUT",
             description = "Stores the on/off switch and the caller's own markup. The markup is sanitized on the way in and capped in size; sending it null (or blank) resets to the computed default, which then keeps following the profile.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The custom markup exceeds the size cap"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public void saveEmailSignature(HttpServletRequest request,
                                 @RequestBody
                                 EmailSignature signature) {
    try {
      emailSignatureService.saveEmailSignature(request.getRemoteUser(), signature);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Streams the caller's signature image. The EFFECTIVE image - the picture the caller
   * uploaded through the cropper when they set one, the platform's company logo
   * otherwise.
   *
   * @param request the caller's request, for the acting user
   * @return the answer, as ResponseEntity&lt;InputStreamResource&gt;
   */
  @GetMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Streams the caller's signature image",
             method = "GET",
             description = "The EFFECTIVE image - the picture the caller uploaded through the cropper when they set one, the platform's company logo otherwise. This URL only renders for the logged-in caller; in a message that actually goes out, the send path swaps it for a cid: reference to an embedded multipart/related part, which is the only form an external recipient's client renders. Answers 404 when there is no image at all.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public ResponseEntity<InputStreamResource> getSignatureImage(HttpServletRequest request) {
    EmailSignatureLogo logo = emailSignatureService.getSignatureLogo(request.getRemoteUser());
    if (logo == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok()
                         .cacheControl(CacheControl.maxAge(SIGNATURE_IMAGE_CACHE_DAYS, TimeUnit.DAYS).cachePrivate())
                         .contentType(StringUtils.isBlank(logo.mimeType()) ? MediaType.IMAGE_PNG
                                                                           : MediaType.parseMediaType(logo.mimeType()))
                         .body(new InputStreamResource(new ByteArrayInputStream(logo.bytes())));
  }

  /**
   * Replaces the caller's signature image. Takes the upload id the platform's image
   * cropper produced and stores the picture as the caller's own signature image,
   * replacing the company logo for their signature only.
   *
   * @param request the caller's request, for the acting user
   * @param uploadId the upload id the image cropper produced
   */
  @PutMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Replaces the caller's signature image",
             method = "PUT",
             description = "Takes the upload id the platform's image cropper produced and stores the picture as the caller's own signature image, replacing the company logo for their signature only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The upload is gone or is not an image"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public void saveSignatureImage(HttpServletRequest request,
                                 @Parameter(description = "The upload id the image cropper produced", required = true)
                                 @RequestParam("uploadId")
                                 String uploadId) {
    try {
      emailSignatureService.saveSignatureLogo(request.getRemoteUser(), uploadId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Puts the caller's signature image back to the company logo. Deletes the caller's own
   * uploaded signature image; their signature then carries the platform's company logo
   * again.
   *
   * @param request the caller's request, for the acting user
   */
  @DeleteMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Puts the caller's signature image back to the company logo",
             method = "DELETE",
             description = "Deletes the caller's own uploaded signature image; their signature then carries the platform's company logo again.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in"), })
  public void deleteSignatureImage(HttpServletRequest request) {
    emailSignatureService.deleteSignatureLogo(request.getRemoteUser());
  }

  // ---------------------------------------------------------------------------------
  // Mailbox delegation. The mailbox acted on is always the caller's own; a delegation
  // id resolves only with the caller as its grantee or its owner. Status mapping, the
  // org contract (EXO-90627): IllegalAccessException 403, ObjectNotFoundException 404,
  // IllegalArgumentException 400 with the code, MailboxAclException 502 with the code
  // (the mail server would not or could not), DelegationRevokedException 410 with the
  // code (the share is gone -- a business state, not a refusal).
  // ---------------------------------------------------------------------------------

  /**
   * Who has access to the caller's mailbox, read live from the mail server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the server's capabilities and the grantees
   */
  @GetMapping("/delegations/granted")
  @Secured("users")
  @Operation(summary = "Lists who has access to the caller's own mailbox",
             method = "GET",
             description = "Reads the ACL of the caller's INBOX on the caller's own session and maps each entry to the eXo user connected on the same connector with that identifier, with the delegation row when one exists (status PENDING, ACCEPTED, DECLINED, REVOKED, AVAILABLE). Entries granted outside eXo appear too; an identifier no eXo user holds is listed raw. When the server does not support sharing, capabilities.supported is false with the reason code and only eXo's own rows are listed.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be reached or refused (emailConnector.delegation.*)") })
  public GrantedDelegations getGrantedDelegations(HttpServletRequest request) {
    try {
      return emailDelegationService.getGrantedDelegations(request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * The mailboxes shared with the caller.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param discover whether to also walk the caller's Other Users namespace on the
   *          server, so shares granted outside eXo are offered
   * @return the caller's delegation rows
   */
  @GetMapping("/delegations/received")
  @Secured("users")
  @Operation(summary = "Lists the mailboxes shared with the caller",
             method = "GET",
             description = "The caller's delegation rows in every state: PENDING invitations, ACCEPTED subscriptions, DECLINED and REVOKED history, AVAILABLE shares seen on the server that nobody invited from eXo. With discover=true the caller's own session lists the Other Users namespace first, so a share granted in the mail server's own interface is offered (proposed, never auto-subscribed); an unreachable server leaves the rows as they are. Each row carries the affordances its last observed rights unlock.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in") })
  public List<EmailDelegation> getReceivedDelegations(HttpServletRequest request,
                                                      @Parameter(description = "Whether to discover shares on the mail server as well")
                                                      @RequestParam(name = "discover", defaultValue = "true")
                                                      boolean discover) {
    return emailDelegationService.getReceivedDelegations(request.getRemoteUser(), discover);
  }

  /**
   * The shared mailboxes the caller can switch to from the mail drawer's header.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the switcher's entries, one per accepted share
   */
  @GetMapping("/delegations/mailboxes")
  @Secured("users")
  @Operation(summary = "Lists the shared mailboxes the caller can open in the mail drawer",
             method = "GET",
             description = "One entry per ACCEPTED share whose INBOX is registered: the owner, the rights the server last granted and the affordances they unlock, the CUSTOM:<id> key the shared INBOX is listed under in GET /email-box?folder=, and its unread count in the caller's mirror. Read from eXo's rows, no connection to the mail server. Empty when nothing is shared with the caller, which is what hides the switcher.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in") })
  public List<SharedMailboxEntry> getSharedMailboxes(HttpServletRequest request) {
    return emailDelegationService.getSharedMailboxes(request.getRemoteUser());
  }

  /**
   * Shares the caller's mailbox with an eXo user.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param invite who, and which preset
   * @return the delegation as created
   */
  @PostMapping("/delegations")
  @Secured("users")
  @Operation(summary = "Shares the caller's own mailbox with another eXo user",
             method = "POST",
             description = "Writes an ACL on the caller's INBOX, on the caller's own session, for the identifier the grantee connects to the same connector with (the grantee must be connected there; a mail login is never accepted), and on a server that grants per folder on the caller's Sent, Archive, Trash and Spam too. The preset is READER (lrs) or EDITOR (lrswit, plus e where mail leaves and never on Trash), intersected with the caller's own rights; a, x, p and k are never granted. The optional folderAccess ({SENT|ARCHIVE|TRASH|JUNK: READER|EDITOR|NONE}) sets one of those folders apart before anything is shared: NONE is never shared. The grant is written now: declining later does not remove it, only the owner does. The grantee is then invited (PENDING).")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Self, unknown or unconnected grantee, invalid preset or folder choice, a folder choice on a server that grants a whole mailbox at once, or already shared (emailConnector.delegation.*)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server does not support ACLs, nothing was left to grant, or SETACL was refused (emailConnector.delegation.*)") })
  public EmailDelegation inviteDelegation(HttpServletRequest request,
                                          @RequestBody
                                          DelegationInviteRequest invite) {
    try {
      if (invite.getFolderAccess() == null || invite.getFolderAccess().isEmpty()) {
        return emailDelegationService.invite(request.getRemoteUser(), invite.getGranteeUsername(), invite.getPreset());
      }
      return emailDelegationService.invite(request.getRemoteUser(),
                                           invite.getGranteeUsername(),
                                           invite.getPreset(),
                                           invite.getFolderAccess());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Removes a grantee's access to the caller's mailbox.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   */
  @DeleteMapping("/delegations/{id}")
  @Secured("users")
  @Operation(summary = "Removes a grantee's access to the caller's own mailbox",
             method = "DELETE",
             description = "DELETEACL on the caller's INBOX, on the caller's own session, for the identifier the grant was written to; the delegation goes REVOKED and the grantee's registered folders of the mailbox are dropped. Works on a declined invitation too, which is how an owner answers a decline.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "A share of a mailbox the caller is no longer connected to (emailConnector.delegation.notChangeable)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server refused DELETEACL (emailConnector.delegation.*)") })
  public void revokeDelegation(HttpServletRequest request,
                               @Parameter(description = "The delegation id", required = true)
                               @PathVariable("id")
                               long id) {
    try {
      emailDelegationService.revoke(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      // notChangeable: a share of a mailbox the owner is no longer connected to (#443-1).
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Changes the access a grantee holds on the caller's own mailbox to another preset.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   * @param body the preset to set
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/preset")
  @Secured("users")
  @Operation(summary = "Changes a grantee's access to the caller's own mailbox",
             method = "PUT",
             description = "Writes the preset (READER or EDITOR) for the grantee on the caller's INBOX and on the other folders the share covers, on the caller's own session, through the same engine call as the grant -- it replaces the grantee's entry (RFC 4314 SETACL), capped by the caller's own rights -- and records what the server holds. The status is unchanged. A narrowing a folder refused is answered 502 emailConnector.delegation.notNarrowed after the rest is recorded. Owner only: a delegation that is not the caller's own is answered 404.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Invalid preset, or a share no longer on the server (emailConnector.delegation.*)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server refused the change (emailConnector.delegation.*)") })
  public EmailDelegation changeDelegationPreset(HttpServletRequest request,
                                                @Parameter(description = "The delegation id", required = true)
                                                @PathVariable("id")
                                                long id,
                                                @RequestBody
                                                DelegationInviteRequest body) {
    try {
      return emailDelegationService.changePreset(request.getRemoteUser(), id, body == null ? null : body.getPreset());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Shares with a grantee the caller's folders a share written before EXO-90548 left
   * out: Sent, Archive, Trash and Spam, beside the INBOX it covers.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   * @return the delegation as it now stands
   */
  @PostMapping("/delegations/{id}/extend")
  @Secured("users")
  @Operation(summary = "Extends a share of the caller's own mailbox to its Sent, Archive, Trash and Spam folders",
             method = "POST",
             description = "Grants the share's own preset on each of the caller's Sent, Archive, Trash and Spam folders the share does not cover yet, on the caller's own session, with each folder's letters (an Editor holds e where mail leaves, never on Trash), and records what the server accepted. Only for a share eXo wrote, still on the server, on a server that grants per folder. Owner only: a delegation that is not the caller's own is answered 404.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "A share that cannot be extended (emailConnector.delegation.notChangeable)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be asked (emailConnector.delegation.*)") })
  public EmailDelegation extendDelegation(HttpServletRequest request,
                                          @Parameter(description = "The delegation id", required = true)
                                          @PathVariable("id")
                                          long id) {
    try {
      return emailDelegationService.extend(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * The caller's own folders that can be shared one by one, for the invitation's folder
   * choice.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @return the folders, with no access
   */
  @GetMapping("/delegations/folders")
  @Secured("users")
  @Operation(summary = "Lists the caller's own folders that can be shared one by one",
             method = "GET",
             description = "One LIST on the caller's own session: INBOX first (the share itself, not editable), then Sent, Archive, Trash and Spam, then the caller's other folders as a tree, never Drafts, at most exo.email.delegation.maxFolders beside INBOX (truncated says when more exist). No ACL is read. Only on a server that grants per folder.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "A server that grants a whole mailbox at once (emailConnector.delegation.perFolderUnsupported)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server does not support ACLs or could not be asked (emailConnector.delegation.*)") })
  public DelegationFolders getOwnFolders(HttpServletRequest request) {
    try {
      return emailDelegationService.getOwnFolders(request.getRemoteUser());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * The caller's folders with the access one grantee holds in each, as the mail server
   * says it now.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   * @return the folders with their access
   */
  @GetMapping("/delegations/{id}/folders")
  @Secured("users")
  @Operation(summary = "Lists the caller's folders with the access a grantee holds in each",
             method = "GET",
             description = "The folders of GET /delegations/folders, each with the grantee's access read with GETACL on that folder, on the caller's own session: READER, EDITOR or NONE; null with the raw letters for an entry that reads as no preset (written in another mail application), null with readable false when the ACL could not be read. Nothing is written. Owner only: a delegation that is not the caller's own is answered 404.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "A share no longer on the server or of another mailbox, or a server that grants a whole mailbox at once (emailConnector.delegation.*)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server does not support ACLs or could not be asked (emailConnector.delegation.*)") })
  public DelegationFolders getDelegationFolders(HttpServletRequest request,
                                                @Parameter(description = "The delegation id", required = true)
                                                @PathVariable("id")
                                                long id) {
    try {
      return emailDelegationService.getFolderAccess(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Sets the access one grantee holds in some of the caller's folders.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   * @param body the folders and the access for each
   * @return the delegation as it now stands, and each folder's outcome
   */
  @PutMapping("/delegations/{id}/folders")
  @Secured("users")
  @Operation(summary = "Sets the access a grantee holds in some of the caller's folders",
             method = "PUT",
             description = "For each folder named (as GET /delegations/{id}/folders names it), READER or EDITOR writes the preset's letters for that folder's role -- read on the caller's session, never taken from the request: Editor holds e where mail leaves, never on Trash -- and NONE removes the grantee's entry. Every name is checked against the caller's own shareable folders before anything is written; INBOX and Drafts are refused. Each folder is its own write, answered in results: DONE, REMOVED (a narrower access refused, the entry removed instead), REFUSED, NOTHING_TO_GRANT, NOT_NARROWED or NOT_REACHED; one refused undoes nothing. A folder no longer shared leaves the grantee's screens at once. The grantee is not notified. Owner only: a delegation that is not the caller's own is answered 404.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled, folder by folder"),
      @ApiResponse(responseCode = "400", description = "An invalid request, a folder that is not the caller's or cannot be shared one by one, a share no longer on the server or of another mailbox, or a server that grants a whole mailbox at once (emailConnector.delegation.*)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server does not support ACLs, could not be asked, or no longer records the share (emailConnector.delegation.*)") })
  public FolderAccessUpdate setDelegationFolders(HttpServletRequest request,
                                                 @Parameter(description = "The delegation id", required = true)
                                                 @PathVariable("id")
                                                 long id,
                                                 @RequestBody
                                                 DelegationFoldersRequest body) {
    try {
      return emailDelegationService.setFolderAccess(request.getRemoteUser(), id, body == null ? null : body.getFolders());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Sets, changes or withdraws the caller's consent to one grantee writing mail in the
   * caller's name.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as owner
   * @param body the consent: NONE, ON_BEHALF or AS
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/send-mode")
  @Secured("users")
  @Operation(summary = "Sets the caller's consent to a grantee writing mail in the caller's name",
             method = "PUT",
             description = "ON_BEHALF lets the grantee send mail showing the caller as the author and the grantee as the sender; AS lets them send mail showing the caller alone; NONE withdraws the consent. Recorded on the share, with its date; nothing is sent and no ACL letter is written. A consent needs the shape declared by the administrator for the caller's connector and the share still on the caller's INBOX; a withdrawal is always recorded. Only for a share on offer or in use that eXo made. Owner only: a delegation that is not the caller's own is answered 404.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "An unknown mode (emailConnector.sendMode.invalid), a shape switched off or not declared (emailConnector.sendMode.disabled, .unsupported), or a share that cannot carry it (emailConnector.delegation.notChangeable)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's mailbox"),
      @ApiResponse(responseCode = "502", description = "The mail server does not support sharing or could not be asked (emailConnector.delegation.*)") })
  public EmailDelegation setDelegationSendMode(HttpServletRequest request,
                                               @Parameter(description = "The delegation id", required = true)
                                               @PathVariable("id")
                                               long id,
                                               @RequestBody
                                               DelegationSendModeRequest body) {
    try {
      return emailDelegationService.setSendMode(request.getRemoteUser(), id, body == null ? null : body.getSendMode());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Accepts a share.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as grantee
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/accept")
  @Secured("users")
  @Operation(summary = "Accepts a mailbox shared with the caller",
             method = "PUT",
             description = "On the caller's own session, finds the owner's mailbox under the Other Users namespace and reads MYRIGHTS on its INBOX. Access confirmed: ACCEPTED, with the path and the letters, and the shared INBOX registered as a folder of the caller (sync opt-in to follow). Access gone: the row goes REVOKED and 410 is answered. A DECLINED or AVAILABLE row is accepted the same way.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Not in an acceptable state, or the cap of shared mailboxes is reached (emailConnector.delegation.*)"),
      @ApiResponse(responseCode = "403", description = "Forbidden operation, or no connected mailbox on the share's connector"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's"),
      @ApiResponse(responseCode = "410", description = "The share is no longer on the server (emailConnector.delegation.revoked)"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be asked (emailConnector.delegation.*)") })
  public EmailDelegation acceptDelegation(HttpServletRequest request,
                                          @Parameter(description = "The delegation id", required = true)
                                          @PathVariable("id")
                                          long id) {
    try {
      return emailDelegationService.accept(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    } catch (MailboxAclException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getCode());
    }
  }

  /**
   * Declines an invitation. The ACL on the server is left in place.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as grantee
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/decline")
  @Secured("users")
  @Operation(summary = "Declines a mailbox shared with the caller",
             method = "PUT",
             description = "Records the answer (DECLINED). The ACL on the server is NOT removed: only the owner removes it, and eXo never acts as the owner on the grantee's behalf. The caller can accept later; the server decides then.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The invitation is not pending (emailConnector.delegation.notPending)"),
      @ApiResponse(responseCode = "403", description = "Not signed in"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's") })
  public EmailDelegation declineDelegation(HttpServletRequest request,
                                           @Parameter(description = "The delegation id", required = true)
                                           @PathVariable("id")
                                           long id) {
    try {
      return emailDelegationService.decline(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Leaves an accepted share. The ACL on the server is left in place.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as grantee
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/leave")
  @Secured("users")
  @Operation(summary = "Unsubscribes the caller from a mailbox shared with them",
             method = "PUT",
             description = "An eXo-granted share goes back to DECLINED, a server-discovered one to AVAILABLE; the caller's registered folders of the mailbox are dropped. The ACL on the server is NOT removed.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The share is not accepted (emailConnector.delegation.notAccepted)"),
      @ApiResponse(responseCode = "403", description = "Not signed in"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's") })
  public EmailDelegation leaveDelegation(HttpServletRequest request,
                                         @Parameter(description = "The delegation id", required = true)
                                         @PathVariable("id")
                                         long id) {
    try {
      return emailDelegationService.leave(request.getRemoteUser(), id);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * The caller's toggles on one share.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param id the delegation id, resolved with the caller as grantee
   * @param preferences the toggles; a null leaves one as it is
   * @return the delegation as it now stands
   */
  @PutMapping("/delegations/{id}/preferences")
  @Secured("users")
  @Operation(summary = "Stores the caller's toggles on a mailbox shared with them",
             method = "PUT",
             description = "badgeIncluded: whether the shared INBOX counts in the caller's unread badge (off by default). notifyNewMail: whether new mail there notifies the caller (off by default; only while the caller uses that mailbox, since a share not in use is not synced). searchIncluded: whether the unified search returns this shared mailbox's mail, labelled with its owner (on by default). A missing field leaves the toggle as it is.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Not signed in"),
      @ApiResponse(responseCode = "404", description = "No such delegation of the caller's") })
  public EmailDelegation updateDelegationPreferences(HttpServletRequest request,
                                                     @Parameter(description = "The delegation id", required = true)
                                                     @PathVariable("id")
                                                     long id,
                                                     @RequestBody
                                                     DelegationPreferencesRequest preferences) {
    try {
      return emailDelegationService.updatePreferences(request.getRemoteUser(),
                                                      id,
                                                      preferences.getBadgeIncluded(),
                                                      preferences.getNotifyNewMail(),
                                                      preferences.getSearchIncluded());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  /**
   * The caller's automatic reply section, read live from their mail server, with the
   * mailbox's forward shown read-only.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; any value is refused, the
   *          automatic reply being a setting of the caller's own mailbox only
   * @param timeZone the caller's IANA zone, as the browser reports it; the days of a
   *          reply the server stores as instants are answered in it
   * @param forwarding whether to read the mailbox's forward
   * @return the section
   */
  @GetMapping("/absence")
  @Secured("users")
  @Operation(summary = "Reads the caller's automatic reply from their mail server", method = "GET",
      description = "A live read, as the caller, of the mail server their connector's rules engine manages "
          + "(email.connector.rulesEngine[.<connectorId>]): what the engine can do (capabilities), the reply the server holds "
          + "(vacation, without any copy kept in eXo), and its state -- OWN, ELSEWHERE (another client's active script may send "
          + "its own reply; foreignScriptName names it), MODIFIED (eXo's script changed outside eXo), INACTIVE (the server no "
          + "longer runs eXo's script) or NONE. On BlueMind the server holds one reply per mailbox, whoever set it: it is "
          + "answered OWN, its days in timeZone. forwarding says, read-only, whether the mailbox forwards mail: "
          + "SERVER_FORWARD with its destinations and keepCopy (BlueMind), MAY_FORWARD_BY_SCRIPT naming another client's "
          + "script that holds a redirect (Sieve; destinations are never read out of it), NONE, or UNKNOWN; manageUrl is the "
          + "connector's webmail. eXo never writes a forward. forwarding is null, and nothing read, when "
          + "email.connector.forwarding.display.enabled is false or the request says forwarding=false. Own mailbox only: with delegationId the answer is 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.absence.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be used (emailConnector.absence.serverUnreachable, .tlsHostName, .authenticationFailed, .serverUnsupported, .serverNotConfigured)") })
  public AbsenceSettings getAbsence(HttpServletRequest request,
                                    @Parameter(description = "The share the request is made from; refused")
                                    @RequestParam(name = "delegationId", required = false)
                                    Long delegationId,
                                    @Parameter(description = "The caller's IANA time zone; the days of a reply the mail server "
                                        + "stores as instants (BlueMind) are answered in it")
                                    @RequestParam(name = "timeZone", required = false)
                                    String timeZone,
                                    @Parameter(description = "Whether to read the mailbox's forward; false answers forwarding "
                                        + "null without asking the mail server for it")
                                    @RequestParam(name = "forwarding", required = false, defaultValue = "true")
                                    boolean forwarding) {
    try {
      return emailAbsenceService.getAbsence(request.getRemoteUser(), delegationId, timeZone, forwarding);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * Writes the caller's automatic reply on their mail server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; any value is refused
   * @param republish true to overwrite eXo's own script although it changed outside eXo
   * @param vacation the reply
   * @return the section after the write, or the conflict with the script it is about
   */
  @PutMapping("/absence/vacation")
  @Secured("users")
  @Operation(summary = "Writes the caller's automatic reply on their mail server", method = "PUT",
      description = "Plain text, one-line subject (at most 200 characters), text at most 4000 characters, optional first and "
          + "last days (YYYY-MM-DD, the last one included) in timeZone, the IANA zone of the browser, required with a day. "
          + "Each sender is answered once per email.connector.absence.vacation.days days; editing a reply that stays on does "
          + "not answer them again, switching it on again does. Nothing another client wrote is ever replaced: when it would "
          + "be, the answer is 409 with the script it is about. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Written; the section after the write, capabilities not re-read"),
      @ApiResponse(responseCode = "400", description = "An invalid value (emailConnector.absence.subject.invalid, .text.invalid, .window.invalid, .timeZone.invalid), or a connector that cannot hold a reply (emailConnector.absence.unsupported)"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.absence.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = "{message, scriptName}: another client's reply is active (emailConnector.absence.managedElsewhere), another script is active and the server cannot include it (.serverConflict), or eXo's script changed outside eXo (.modifiedOutside, re-send with republish=true)"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be used (emailConnector.absence.serverUnreachable, .serverRefused, .tlsHostName, .authenticationFailed, .serverUnsupported, .serverNotConfigured)") })
  public ResponseEntity<Object> setVacation(HttpServletRequest request,
                                            @Parameter(description = "The share the request is made from; refused")
                                            @RequestParam(name = "delegationId", required = false)
                                            Long delegationId,
                                            @Parameter(description = "Overwrite eXo's own script although it changed outside eXo")
                                            @RequestParam(name = "republish", required = false, defaultValue = "false")
                                            boolean republish,
                                            @RequestBody
                                            VacationSetting vacation) {
    try {
      return ResponseEntity.ok(emailAbsenceService.setVacation(request.getRemoteUser(), delegationId, vacation, republish));
    } catch (ServerRuleConflictException e) {
      return conflict(e);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalArgumentException | ServerRuleUnsupportedException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * Switches the caller's automatic reply off, keeping its text on the server.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the request is made from; any value is refused
   * @return 204, or the conflict with the script it is about
   */
  @DeleteMapping("/absence/vacation")
  @Secured("users")
  @Operation(summary = "Switches the caller's automatic reply off", method = "DELETE",
      description = "The reply's text stays on the server, in eXo's script, for when it is switched on again. Nothing is written "
          + "when no reply of eXo's is on. Own mailbox only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "204", description = "Switched off, or nothing was on"),
      @ApiResponse(responseCode = "400", description = "A connector that cannot hold a reply (emailConnector.absence.unsupported)"),
      @ApiResponse(responseCode = "403", description = "Asked from someone else's mailbox (emailConnector.absence.ownMailboxOnly), or the connector may not be used"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no mailbox is connected"),
      @ApiResponse(responseCode = "409", description = "{message, scriptName}: eXo's script changed outside eXo (emailConnector.absence.modifiedOutside)"),
      @ApiResponse(responseCode = "502", description = "The mail server could not be used (emailConnector.absence.*)") })
  public ResponseEntity<Object> disableVacation(HttpServletRequest request,
                                                @Parameter(description = "The share the request is made from; refused")
                                                @RequestParam(name = "delegationId", required = false)
                                                Long delegationId) {
    try {
      emailAbsenceService.disableVacation(request.getRemoteUser(), delegationId);
      return ResponseEntity.noContent().build();
    } catch (ServerRuleConflictException e) {
      return conflict(e);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (ServerRuleUnsupportedException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (ServerRuleUnavailableException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  /**
   * The dates-only summary of an automatic reply, for the mailbox band: the caller's own,
   * or, with a share, the owner's as her delegate sees it.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param delegationId the share the caller looks at the owner's mailbox through, or
   *          null for the caller's own mailbox
   * @return the summary, never the text
   */
  @GetMapping("/absence/status")
  @Secured("users")
  @Operation(summary = "Reads the summary of an automatic reply: the caller's, or a shared mailbox owner's", method = "GET",
      description = "Without delegationId: the caller's own {enabled, start, end, timeZone, source, updatedDate, "
          + "lastServerReadDate}, the cached summary, read again from the mail server when older than "
          + "email.connector.absence.status.ttlSeconds; a server that cannot be read leaves the cached one. "
          + "With delegationId, one of the caller's own accepted shares: the owner's {enabled, start, end, timeZone, "
          + "updatedDate, lastServerReadDate, stale}, from the summary cached by the owner's own reads, no server asked and "
          + "nothing done as the owner; stale when that check is older than the TTL. Dates only, never the text or the subject.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled: the caller's own summary, or a shared mailbox owner's dates",
      content = @Content(schema = @Schema(oneOf = { AbsenceStatus.class, OwnerAbsenceStatus.class }))),
      @ApiResponse(responseCode = "403", description = "The share is not accepted yet (emailConnector.absence.shareNotAccepted), or the caller may not use mail (emailConnector.absence.notAllowed)"),
      @ApiResponse(responseCode = "404", description = "The feature is off, or no such share is the caller's"),
      @ApiResponse(responseCode = "410", description = "The share ended (emailConnector.delegation.revoked, .gone)") })
  public Object getAbsenceStatus(HttpServletRequest request,
                                 @Parameter(description = "One of the caller's shares, to read its owner's dates; "
                                     + "absent for the caller's own")
                                 @RequestParam(name = "delegationId", required = false)
                                 Long delegationId) {
    try {
      return delegationId == null ? emailAbsenceService.getStatus(request.getRemoteUser(), null)
                                  : emailAbsenceService.getOwnerAbsenceForDelegate(request.getRemoteUser(), delegationId);
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    }
  }

  /**
   * The 409 answer of a publish eXo declined: the code, and the script it is about so the
   * interface can name the client that manages it.
   *
   * @param e the conflict
   * @return the answer
   */
  private static ResponseEntity<Object> conflict(ServerRuleConflictException e) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("message", e.getMessage());
    body.put("scriptName", e.getScriptName());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @GetMapping("/connectors")
  @Secured("users")
  @Operation(summary = "Gets user active email connectors", method = "GET", description = "This will get active email connectors")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public List<EmailConnector> getUserEmailConnectors(HttpServletRequest request) {
    return userEmailSettingService.getUserEmailConnectors(request.getLocale(), request.getRemoteUser());
  }
}
