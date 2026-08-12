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
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
import org.exoplatform.emailConnector.carddav.CardDavException;
import org.exoplatform.emailConnector.carddav.CardDavPublishQueuedException;
import org.exoplatform.emailConnector.model.ContactImportState;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
import org.exoplatform.emailConnector.model.EmailContactSuggestion;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;
import org.exoplatform.emailConnector.service.EmailContactService;
import org.exoplatform.emailConnector.service.EmailContactVCardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

  @Autowired
  private EmailContactVCardService       emailContactVCardService;

  @GetMapping
  @Secured("users")
  @Operation(summary = "Lists the user's contacts", method = "GET",
             description = "One alphabetical page of the caller's own contact store, with the letter-index map the A-Z rail runs on and the filtered total. 'All' (no source) is the local store: collected + manual + address book - colleagues are the platform's own user directory, queried by the client directly.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unknown source filter"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public EmailContactPage getContacts(HttpServletRequest request,
                                      @Parameter(description = "Source filter, repeatable: collected, manual, addressBook; omitted for every source")
                                      @RequestParam(value = "source", required = false)
                                      List<String> source,
                                      @Parameter(description = "Free text matched against names and addresses")
                                      @RequestParam(value = "q", required = false)
                                      String query,
                                      @Parameter(description = "When true, only the contacts the caller starred are returned - intersected with the source filter, not replacing it")
                                      @RequestParam(value = "favorites", required = false, defaultValue = "false")
                                      boolean favorites,
                                      @Parameter(description = "Row offset, a multiple of limit")
                                      @RequestParam(value = "offset", required = false, defaultValue = "0")
                                      int offset,
                                      @Parameter(description = "Page size")
                                      @RequestParam(value = "limit", required = false, defaultValue = "100")
                                      int limit) {
    try {
      return emailContactService.getContacts(request.getRemoteUser(), source, query, favorites, offset, limit);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping("/search")
  @Secured("users")
  @Operation(summary = "Searches the caller's contacts for the unified search", method = "GET",
             description = "The 'My contacts' section of the platform's unified search: the caller's own contacts matching the term, excluding anyone who is also a platform user - colleagues are the People section's to answer. Fetched by the search page with the viewer's own session, so results are per-user by construction; phone numbers are not returned. The favorites parameter narrows to the contacts the caller starred, which is how the search page's Favorites filter reaches this connector.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public List<EmailContact> searchContacts(HttpServletRequest request,
                                           @Parameter(description = "What was typed in the search bar")
                                           @RequestParam(value = "q", required = false)
                                           String query,
                                           @Parameter(description = "When true, only the contacts the caller starred")
                                           @RequestParam(value = "favorites", required = false, defaultValue = "false")
                                           boolean favorites,
                                           @Parameter(description = "How many hits to answer; 0 takes the server's default, and the server caps it either way")
                                           @RequestParam(value = "limit", required = false, defaultValue = "0")
                                           int limit) {
    return emailContactService.searchContacts(request.getRemoteUser(), query, favorites, limit);
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

  @GetMapping("/by-address")
  @Secured("users")
  @Operation(summary = "Gets the contact reachable at an address", method = "GET",
             description = "The caller's own contact for this address, matched on any of its addresses rather than only the one it is filed under. Answers 404 when the caller has nobody there, which is also how the mailbox knows to offer creating them.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public EmailContact getContactByAddress(HttpServletRequest request,
                                          @Parameter(description = "The address to look up", required = true)
                                          @RequestParam("email")
                                          String email) {
    EmailContact contact = emailContactService.getContactByAddress(email, request.getRemoteUser());
    if (contact == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return contact;
  }

  @GetMapping("/by-user")
  @Secured("users")
  @Operation(summary = "Gets the caller's contact for a platform user", method = "GET",
             description = "The caller's own contact row for this colleague, however it got there: by the directory link first, then by the profile's address (a collected or manual row holding the same person). Answers 404 when the caller has no row - which is also how the profile action knows to offer adding them.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public EmailContact getContactByUser(HttpServletRequest request,
                                       @Parameter(description = "The platform username to look up", required = true)
                                       @RequestParam("username")
                                       String username) {
    EmailContact contact = emailContactService.getContactByPlatformUser(request.getRemoteUser(), username);
    if (contact == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return contact;
  }

  @PostMapping("/directory")
  @Secured("users")
  @Operation(summary = "Imports a platform colleague into the caller's contacts", method = "POST",
             description = "The 'Add to my contacts' action on a people profile: creates a directory-linked contact whose name, avatar and address resolve live from the colleague's profile. A previously removed link is revived. A visible row already linking or carrying this person answers 409, unmutated - /by-user is where the client finds that row. An unknown or deleted identity answers 404; asking to import yourself answers 400.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Self-import, or a blank username"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Unknown or deleted platform user"),
      @ApiResponse(responseCode = "409", description = "A visible contact already holds this person"), })
  public EmailContact importDirectoryContact(HttpServletRequest request,
                                             @Parameter(description = "The platform username to import", required = true)
                                             @RequestParam("username")
                                             String username) {
    try {
      EmailContact imported = emailContactService.importDirectoryContact(request.getRemoteUser(), username);
      if (imported == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return imported;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
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
             description = "Adds a contact by hand. If the address belongs to a previously removed (suppressed) collected contact, that row is revived and updated from this body - the caller sees a normal create, never a conflict about a row it cannot see. A visible row with the same address answers 409, and so does one holding any of the body's secondaryEmails - the contact is filed under every address the body lists. A 'photoUploadId' on the body gives the new contact its picture in the same round-trip.")
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
             description = "Edits a manual or collected contact (a collected one keeps its source; collection never overwrites a name a user set). CardDAV rows are read-only in this version, and directory-linked ones always are. Changing any address re-checks uniqueness. 'secondaryEmails' is three-state like the photo: absent keeps the stored other addresses (a changed primary is then demoted into them, not dropped), a present list is the authoritative set. The photo travels in the same body as 'photoUploadId': absent leaves the stored photo alone, an empty string removes it, an upload id sets or replaces it.")
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

  @PostMapping("/{id}/publish")
  @Secured("users")
  @Operation(summary = "Publishes one of the caller's contacts to their CardDAV address book", method = "POST",
             description = "Creates the contact as a new entry in the caller's own address book - creates only, never overwrites: the card is stored under If-None-Match:*, so an entry already at that URL makes the server refuse (answered 409) rather than anything be replaced. Manual and collected contacts only; a directory-linked colleague is the platform's to hold, not an address book's. Requires the address book to be bound AND already discovered by a successful sync - publishing never writes to an unverified URL. On success the contact becomes an address-book row, read-only here like any other, and is answered re-read. Somebody else's contact answers 404, never 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "202", description = "The server could not be reached, so the publish was queued: it will be retried automatically after the next successful address-book sync. The contact stays local and visible meanwhile - accepted, not done, and not an error"),
      @ApiResponse(responseCode = "400", description = "Publishing disabled, a non-publishable source, no bound address book, or a book never discovered - the message code says which"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "The contact is already an address-book row, or the server refused to create over an existing entry"),
      @ApiResponse(responseCode = "502", description = "The address book server could not be reached, or answered an error"), })
  public ResponseEntity<Object> publishContact(HttpServletRequest request,
                                               @Parameter(description = "Contact id", required = true)
                                               @PathVariable("id")
                                               long id) {
    try {
      EmailContact published = emailContactCardDavSyncService.publishContact(request.getRemoteUser(), id);
      if (published == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok(published);
    } catch (CardDavPublishQueuedException e) {
      // Before the CardDavException catch it extends: 202, because the click
      // did its job even though the server was away -- the publish is queued
      // and will go out after the next successful sync. An error status here
      // would tell the user to retry what no longer needs retrying.
      return ResponseEntity.status(HttpStatus.ACCEPTED)
                           .body(Map.of("queued", true,
                                        "messageCode", EmailContactCardDavSyncService.PUBLISH_QUEUED));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (CardDavException e) {
      // The server's failure, not the caller's -- and answered as a message
      // code the client can show, never a stack trace. 502 because this
      // service was the gateway to a server that did not deliver. Reached only
      // when the queue could not take the publish either (it is full).
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "emailConnector.contacts.publish.serverError");
    }
  }

  @PutMapping("/{id}/card")
  @Secured("users")
  @Operation(summary = "Edits one of the caller's address-book contacts by pushing the edit to the CardDAV server", method = "PUT",
             description = "The write-through edit of a CardDAV row, which the plain PUT /{id} refuses. The entry's card is fetched from the server at save time, the edit is merged into that raw card (only the form's own fields, everything else on the card preserved), and the card is stored back under If-Match with the etag that fetch answered - so a change somebody else made in the meantime is never overwritten: it answers 409, the server's card becomes the local row again, and the caller's edit is theirs to retry. The contact is kept locally only once the server accepted the write. Same body contract as PUT /{id}; somebody else's contact answers 404, never 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Writing disabled, not an address-book contact, an unusable address or birthday, no bound address book, or a book never discovered - the message code says which"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "The entry changed on the server first (the local row now shows that change), the entry is gone, the row has no server entry, or an address collides with another contact"),
      @ApiResponse(responseCode = "502", description = "The address book server could not be reached, answered an error, or holds a card too broken to edit safely"), })
  public EmailContact updateAddressBookContact(HttpServletRequest request,
                                               @Parameter(description = "Contact id", required = true)
                                               @PathVariable("id")
                                               long id,
                                               @Parameter(description = "The fields to apply", required = true)
                                               @RequestBody
                                               EmailContact contact) {
    try {
      EmailContact updated = emailContactCardDavSyncService.updateAddressBookContact(request.getRemoteUser(), id, contact);
      if (updated == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return updated;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (CardDavException e) {
      // The server's failure, not the caller's -- same shape as the publish's.
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "emailConnector.contacts.update.serverError");
    }
  }

  @GetMapping("/carddav/publishable")
  @Secured("users")
  @Operation(summary = "Whether the caller can publish contacts to their address book", method = "GET",
             description = "Answers from stored state only, no server round trip: true when the admin allows publishing, an address book is bound, and discovery has succeeded at least once. What the contact card consults before showing its publish action. Holds no secret.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public Map<String, Object> isPublishAvailable(HttpServletRequest request) {
    Map<String, Object> response = new HashMap<>();
    response.put("available", emailContactCardDavSyncService.isPublishAvailable(request.getRemoteUser()));
    return response;
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

  @GetMapping("/carddav/publish-queue")
  @Secured("users")
  @Operation(summary = "The caller's pending address-book publishes", method = "GET",
             description = "The publishes the address book has not taken yet: entries still waiting for the next successful sync, and entries parked after repeated failures with why. Only contact ids and bookkeeping - the contacts themselves stay in the store, visible and retryable. Holds no secret. What the settings screen turns into 'N contacts waiting to publish'.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public ContactPublishQueue getPublishQueue(HttpServletRequest request) {
    return emailContactCardDavSyncService.getPublishQueue(request.getRemoteUser());
  }

  @PostMapping("/carddav/publish-queue")
  @Secured("users")
  @Operation(summary = "Queues a reviewed selection of the caller's contacts to publish to their address book", method = "POST",
             description = "The bulk half of the write-back, fed by the review checklist: the selected contacts are queued and published automatically after the next successful address-book sync - no card is written by this call itself, so it works even before the new account's book has ever been reached. Manual contacts only, re-checked server-side: collected and directory contacts are never offered by the checklist and are refused here too - the human's explicit tick is the provenance this rides on. All-or-nothing: any refusal leaves the queue untouched, so what was reviewed is exactly what happens. A selected contact already in the address book is skipped as already satisfied.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled; the stored queue is answered"),
      @ApiResponse(responseCode = "400", description = "Publishing disabled, an id that is not one of the caller's visible contacts, or a non-manual contact in the selection - the message code says which; nothing was queued"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "409", description = "The whole selection cannot fit in the queue; nothing was queued"), })
  public ContactPublishQueue queuePublishes(HttpServletRequest request,
                                            @Parameter(description = "The reviewed contact ids to publish", required = true)
                                            @RequestBody
                                            List<Long> contactIds) {
    try {
      return emailContactCardDavSyncService.queuePublishes(request.getRemoteUser(), contactIds);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @GetMapping(value = "/export", produces = "text/vcard")
  @Secured("users")
  @Operation(summary = "Exports the caller's whole contact store as a .vcf file", method = "GET",
             description = "Streams every visible contact of the caller's own store - all sources, suppressed rows excluded - as one vCard 3.0 file, the dialect Gmail, Outlook and iCloud all accept. Always the whole store, never a filtered view. Directory-linked colleagues leave with their live-resolved name; CardDAV rows keep their server UID.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public void exportContacts(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/vcard");
    response.setCharacterEncoding("UTF-8");
    // The filename needs no RFC 5987 encoding dance: it is a constant, and a
    // constant with nothing to escape.
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts.vcf\"");
    emailContactVCardService.exportContacts(request.getRemoteUser(), response.getWriter());
  }

  @PostMapping(value = "/export-then-start-fresh", produces = "text/vcard")
  @Secured("users")
  @Operation(summary = "Downloads the caller's whole contact store as a .vcf backup, THEN empties the store", method = "POST",
             description = "The provider-switch 'download a backup, then start fresh' choice, as one operation whose ordering is the contract: the response body IS the backup - every visible contact, same file as /export - and the store is emptied only after the last byte was written and flushed without error. A failed or abandoned download deletes nothing. THERE IS NO UNDO past a delivered download: the .vcf in the user's hands is the recovery path, re-importable through /import. A POST on purpose - a destructive download must never be reachable by a prefetchable GET.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The backup was delivered and the store emptied"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public void exportContactsThenStartFresh(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/vcard");
    response.setCharacterEncoding("UTF-8");
    // A distinct filename from the ordinary export's contacts.vcf: in a Downloads
    // folder six months later, "backup" still says why this file exists.
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts-backup.vcf\"");
    emailContactVCardService.exportContactsThenStartFresh(request.getRemoteUser(), response.getWriter());
  }

  @PostMapping("/import")
  @Secured("users")
  @Operation(summary = "Imports an uploaded .vcf file into the caller's contacts", method = "POST",
             description = "Starts importing a vCard file previously pushed to the upload service, and answers immediately - the run happens in the background and /import/status is where it reports. Import skips and reports, never merges and never revives: a card whose any address is already known (including a removed contact's tombstone) is left alone, so re-importing the same file is a no-op. Imported contacts are manual rows.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Missing upload, or a file over the size cap"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "409", description = "An import of this user is already running"), })
  public ContactImportState importContacts(HttpServletRequest request,
                                           @Parameter(description = "The upload id the file was pushed under", required = true)
                                           @RequestParam("uploadId")
                                           String uploadId) {
    try {
      return emailContactVCardService.startImport(request.getRemoteUser(), uploadId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @GetMapping("/import/status")
  @Secured("users")
  @Operation(summary = "How the caller's vCard import is going, or went", method = "GET",
             description = "Answers the stored import state: status, the four counters (imported, already known, no address, unreadable) and, when something cut the run short, its message code. A null status says no import ever ran. Holds no secret.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public ContactImportState getImportStatus(HttpServletRequest request) {
    return emailContactVCardService.getImportState(request.getRemoteUser());
  }

  @PostMapping("/parse")
  @Secured("users")
  @Operation(summary = "Reads the first card of an uploaded .vcf as a would-be contact, storing nothing", method = "POST",
             description = "Parses a vCard file previously pushed to the upload service and answers the contact its FIRST readable card would become - fields only, nothing persisted, no photo. This is the prefill of the add-contact form: the caller shows the fields, the user confirms, and only the confirming save creates anything. The upload is consumed either way.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Missing upload, a file over the size cap, or no readable card in it"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public EmailContact parseFirstCard(HttpServletRequest request,
                                     @Parameter(description = "The upload id the file was pushed under", required = true)
                                     @RequestParam("uploadId")
                                     String uploadId) {
    try {
      return emailContactVCardService.parseFirstCard(request.getRemoteUser(), uploadId);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @GetMapping(value = "/{id}/vcard", produces = "text/vcard")
  @Secured("users")
  @Operation(summary = "One contact as vCard text", method = "GET",
             description = "The vCard of one of the caller's own contacts. Without the photo parameter it is text-only - what the contact card's QR code encodes, where an embedded picture is far past what a scannable QR can hold. With photo=true the stored picture comes along - the shape the contact travels in as a mail attachment - though only a picture this store owns: a directory colleague's platform avatar is never handed out. Neither shape carries a UID. Somebody else's contact is answered 404, never 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public String getContactVCard(HttpServletRequest request,
                                @Parameter(description = "Contact id", required = true)
                                @PathVariable("id")
                                long id,
                                @Parameter(description = "Whether the stored picture travels; the QR path leaves it out")
                                @RequestParam(value = "photo", required = false, defaultValue = "false")
                                boolean photo) {
    String vcard = emailContactVCardService.getContactVCard(request.getRemoteUser(), id, photo);
    if (vcard == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return vcard;
  }

  @GetMapping("/from-attachment")
  @Secured("users")
  @Operation(summary = "Reads the first vCard of a mail attachment as a contact-form prefill", method = "GET",
             description = "Parses a .vcf attachment of one of the caller's own received mails and answers its FIRST card as an unpersisted contact - what the 'Add to contacts' action opens the create form with. First card only, on purpose: an emailed contact is one person, and a multi-card file belongs to the contacts import. Nothing is stored by this call, and the card's embedded photo is left behind - the form owns the picture flow. An attachment over the size cap or holding no readable card answers 400 with the message code; an attachment the caller does not have answers 404, never 403.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Over the size cap, or no readable vCard"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation, or no connected mailbox"),
      @ApiResponse(responseCode = "404", description = "Not found"), })
  public EmailContact getContactFromAttachment(HttpServletRequest request,
                                               @Parameter(description = "The INBOX IMAP UID of the mail", required = true)
                                               @RequestParam("mailRemoteId")
                                               long mailRemoteId,
                                               @Parameter(description = "The attachment's MIME part path within the mail", required = true)
                                               @RequestParam("attachmentId")
                                               String attachmentId) {
    try {
      EmailContact prefill = emailContactVCardService.getAttachmentContact(request.getRemoteUser(),
                                                                           mailRemoteId,
                                                                           attachmentId);
      if (prefill == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return prefill;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      // The mailbox folds "not yours", "not there" and "unreachable" into this
      // one exception. From the caller's seat every one of them reads the same
      // way - no such attachment of theirs exists - and answering 404 for all
      // three is what keeps somebody else's mail id unprobeable, per this
      // surface's 404-never-403 rule.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
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
