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
import java.util.HashMap;
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

import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;
import org.exoplatform.emailConnector.service.EmailContactService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The contact store's REST surface, at {@code /email-connector/rest/contacts}.
 * The acting user always comes from the authenticated request — a contact id
 * belonging to someone else is answered 404, never 403, so an id can never be
 * probed for existence. Status mapping follows this add-on's convention:
 * missing → 404, invalid input → 400 with the message code as body,
 * already-exists → 409 with the message code.
 */
@RestController
@RequestMapping("/contacts")
@Tag(name = "/email-connector/rest/contacts", description = "Manages the user's personal email contacts")
public class EmailContactRest {

  // Photo responses may be cached hard because their URL carries the row's update
  // date: a replaced picture is a different URL, so a stale one is unreachable.
  private static final long   PHOTO_CACHE_DAYS = 365;

  @Autowired
  private EmailContactService            emailContactService;

  @Autowired
  private EmailContactCardDavSyncService emailContactCardDavSyncService;

  @GetMapping
  @Secured("users")
  @Operation(summary = "Lists the user's contacts", method = "GET",
             description = "One alphabetical page of the caller's own contact store, with the letter-index map the A-Z rail runs on and the filtered total. 'All' (no source) is the local store: collected + manual + address book - colleagues are the platform's own user directory, queried by the client directly.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unknown source filter"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public EmailContactPage getContacts(HttpServletRequest request,
                                      @Parameter(description = "Source filter: omitted/all, collected, or addressBook")
                                      @RequestParam(value = "source", required = false)
                                      String source,
                                      @Parameter(description = "Free text matched against names and addresses")
                                      @RequestParam(value = "q", required = false)
                                      String query,
                                      @Parameter(description = "Row offset, a multiple of limit")
                                      @RequestParam(value = "offset", required = false, defaultValue = "0")
                                      int offset,
                                      @Parameter(description = "Page size")
                                      @RequestParam(value = "limit", required = false, defaultValue = "100")
                                      int limit) {
    try {
      return emailContactService.getContacts(request.getRemoteUser(), source, query, offset, limit);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/suggest")
  @Secured("users")
  @Operation(summary = "Suggests recipients for the compose field", method = "GET",
             description = "One ranked, de-duplicated recipient list merging the caller's own contact store with the platform's people directory, so a recipient field is useful before the store has collected anybody. Ranking: the store first, ordered by usefulness (most-corresponded-with, then most recent, then alphabetical), then the directory-only matches. De-duplication is by normalized address and the store wins, the platform profile supplying the live name and avatar. A blank term answers the top of the store - capped by the same limit - and does not query the directory at all.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public List<EmailContactSuggestion> suggestRecipients(HttpServletRequest request,
                                                        @Parameter(description = "What the user typed; blank answers their top contacts")
                                                        @RequestParam(value = "q", required = false)
                                                        String query,
                                                        @Parameter(description = "How many suggestions to answer; 0 takes the server's default, and the server caps it either way")
                                                        @RequestParam(value = "limit", required = false, defaultValue = "0")
                                                        int limit) {
    return emailContactService.suggestRecipients(request.getRemoteUser(), query, limit);
  }

  @GetMapping("/{id}")
  @Secured("users")
  @Operation(summary = "Gets one contact", method = "GET",
             description = "One contact of the caller's own store, enriched with the platform profile (avatar, profile link) when the address belongs to a colleague. Somebody else's contact is answered 404, never 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public EmailContact getContact(HttpServletRequest request,
                                 @Parameter(description = "Contact id", required = true)
                                 @PathVariable("id")
                                 long id) {
    EmailContact contact = emailContactService.getContact(id, request.getRemoteUser());
    if (contact == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return contact;
  }

  @GetMapping("/{id}/photo")
  @Secured("users")
  @Operation(summary = "Gets a contact photo", method = "GET",
             description = "Streams the picture the caller set on one of their own contacts. Answers 404 for a contact that does not exist, belongs to somebody else, or simply has no photo - a photo URL is never a way to probe another user's store. The URL answered in the contact's avatarUrl carries a version parameter, so responses are cached privately.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public ResponseEntity<InputStreamResource> getContactPhoto(HttpServletRequest request,
                                                             @Parameter(description = "Contact id", required = true)
                                                             @PathVariable("id")
                                                             long id) {
    FileItem photo = emailContactService.getContactPhoto(id, request.getRemoteUser());
    if (photo == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    String mimetype = photo.getFileInfo() == null ? null : photo.getFileInfo().getMimetype();
    return ResponseEntity.ok()
                         .cacheControl(CacheControl.maxAge(PHOTO_CACHE_DAYS, TimeUnit.DAYS).cachePrivate())
                         .contentType(StringUtils.isBlank(mimetype) ? MediaType.IMAGE_PNG : MediaType.parseMediaType(mimetype))
                         .body(new InputStreamResource(new ByteArrayInputStream(photo.getAsByte())));
  }

  @PostMapping
  @Secured("users")
  @Operation(summary = "Creates a manual contact", method = "POST",
             description = "Adds a contact by hand. If the address belongs to a previously removed (suppressed) collected contact, that row is revived and updated from this body - the caller sees a normal create, never a conflict about a row it cannot see. A visible row with the same address answers 409. A 'photoUploadId' on the body gives the new contact its picture in the same round-trip.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unusable email address"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "409", description = "A visible contact already carries this address"), })
  public EmailContact createContact(HttpServletRequest request,
                                    @Parameter(description = "The contact to create", required = true)
                                    @RequestBody
                                    EmailContact contact) {
    try {
      return emailContactService.createContact(contact, request.getRemoteUser());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PutMapping("/{id}")
  @Secured("users")
  @Operation(summary = "Updates a contact", method = "PUT",
             description = "Edits a manual or collected contact (a collected one keeps its source; collection never overwrites a name a user set). CardDAV rows are read-only in this version, and directory-linked ones always are. Changing the address re-checks uniqueness. The photo travels in the same body as 'photoUploadId': absent leaves the stored photo alone, an empty string removes it, an upload id sets or replaces it.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unusable email address, a read-only CardDAV or directory row, or a photo on a row whose picture is not editable"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "The new address collides with another contact"), })
  public EmailContact updateContact(HttpServletRequest request,
                                    @Parameter(description = "Contact id", required = true)
                                    @PathVariable("id")
                                    long id,
                                    @Parameter(description = "The fields to apply", required = true)
                                    @RequestBody
                                    EmailContact contact) {
    try {
      EmailContact updated = emailContactService.updateContact(id, contact, request.getRemoteUser());
      if (updated == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return updated;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @DeleteMapping("/{id}")
  @Secured("users")
  @Operation(summary = "Deletes or suppresses a contact", method = "DELETE",
             description = "A manual contact is deleted for real. A collected (or CardDAV) contact is suppressed instead - hidden everywhere and never re-collected - because a hard delete would only resurrect on the next mail from that person. The response says which happened, so the client can offer the undo toast after a suppression.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public Map<String, Object> deleteContact(HttpServletRequest request,
                                           @Parameter(description = "Contact id", required = true)
                                           @PathVariable("id")
                                           long id) {
    EmailContact result = emailContactService.deleteOrSuppressContact(id, request.getRemoteUser());
    if (result == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    Map<String, Object> response = new HashMap<>();
    response.put("id", result.getId());
    response.put("suppressed", result.isSuppressed());
    return response;
  }

  @PostMapping("/carddav/sync")
  @Secured("users")
  @Operation(summary = "Pulls the caller's CardDAV address book into their contacts",
             method = "POST",
             description = "Runs the address-book sync for the caller. Most runs cost one request: an unchanged collection version means nothing changed. Pass full=true to forget the last version and re-read everything.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public void syncAddressBook(HttpServletRequest request,
                              @Parameter(description = "Re-read the whole address book rather than only what changed")
                              @RequestParam(value = "full", required = false, defaultValue = "false")
                              boolean full) {
    String username = request.getRemoteUser();
    if (full) {
      emailContactCardDavSyncService.resetAddressBookSync(username);
    }
    emailContactCardDavSyncService.syncAddressBook(username, true);
  }

  @GetMapping("/carddav/status")
  @Secured("users")
  @Operation(summary = "How the caller's last address-book sync went", method = "GET",
             description = "Answers the stored sync state: status, consecutive failures and when the last run started. Holds no secret.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public ContactSyncState getAddressBookSyncStatus(HttpServletRequest request) {
    return emailContactCardDavSyncService.getSyncState(request.getRemoteUser());
  }

  @PostMapping("/{id}/restore")
  @Secured("users")
  @Operation(summary = "Restores a suppressed contact", method = "POST",
             description = "Un-suppresses a contact - the undo of deleting a collected one, wired to the toast shown right after.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public EmailContact restoreContact(HttpServletRequest request,
                                     @Parameter(description = "Contact id", required = true)
                                     @PathVariable("id")
                                     long id) {
    EmailContact restored = emailContactService.restoreContact(id, request.getRemoteUser());
    if (restored == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return restored;
  }
}
