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
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
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

  @GetMapping("/signature")
  @Secured("users")
  @Operation(summary = "Gets the caller's email signature",
             method = "GET",
             description = "Answers the stored preference (the on/off switch and the caller's own markup, when they wrote one) together with the default signature computed from their profile as it stands right now - name linked to the profile page, position and company, location, the phone the platform is configured to display, and the signature image. The image URL in the markup points at this resource's own /signature/image, which the send path replaces with an embedded cid: part so external recipients see it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public EmailSignature getEmailSignature(HttpServletRequest request) {
    return emailSignatureService.getEmailSignature(request.getRemoteUser());
  }

  @PutMapping("/signature")
  @Secured("users")
  @Operation(summary = "Stores the caller's email signature preference",
             method = "PUT",
             description = "Stores the on/off switch and the caller's own markup. The markup is sanitized on the way in and capped in size; sending it null (or blank) resets to the computed default, which then keeps following the profile.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The custom markup exceeds the size cap"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public void saveEmailSignature(HttpServletRequest request,
                                 @RequestBody
                                 EmailSignature signature) {
    try {
      emailSignatureService.saveEmailSignature(request.getRemoteUser(), signature);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Streams the caller's signature image",
             method = "GET",
             description = "The EFFECTIVE image - the picture the caller uploaded through the cropper when they set one, the platform's company logo otherwise. This URL only renders for the logged-in caller; in a message that actually goes out, the send path swaps it for a cid: reference to an embedded multipart/related part, which is the only form an external recipient's client renders. Answers 404 when there is no image at all.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
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

  @PutMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Replaces the caller's signature image",
             method = "PUT",
             description = "Takes the upload id the platform's image cropper produced and stores the picture as the caller's own signature image, replacing the company logo for their signature only.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The upload is gone or is not an image"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
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

  @DeleteMapping("/signature/image")
  @Secured("users")
  @Operation(summary = "Puts the caller's signature image back to the company logo",
             method = "DELETE",
             description = "Deletes the caller's own uploaded signature image; their signature then carries the platform's company logo again.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public void deleteSignatureImage(HttpServletRequest request) {
    emailSignatureService.deleteSignatureLogo(request.getRemoteUser());
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
