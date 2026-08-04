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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

import org.exoplatform.emailConnector.model.EmailContact;
import org.exoplatform.emailConnector.model.EmailContactPage;
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

  @Autowired
  private EmailContactService emailContactService;

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

  @PostMapping
  @Secured("users")
  @Operation(summary = "Creates a manual contact", method = "POST",
             description = "Adds a contact by hand. If the address belongs to a previously removed (suppressed) collected contact, that row is revived and updated from this body - the caller sees a normal create, never a conflict about a row it cannot see. A visible row with the same address answers 409.")
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

  @PostMapping("/directory")
  @Secured("users")
  @Operation(summary = "Imports platform colleagues as contacts", method = "POST",
             description = "Imports the given platform users into the caller's contact store as LINKS: each row keeps the platform username, and name/avatar/address resolve live from the profile at read time - never a copy that rots. Importing somebody already collected, hand-typed or previously removed upgrades that one row in place. Unknown usernames and profiles without an address are skipped silently.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "No usernames given"),
      @ApiResponse(responseCode = "401", description = "Unauthorized operation"), })
  public List<EmailContact> importDirectoryContacts(HttpServletRequest request,
                                                    @Parameter(description = "The platform usernames to import", required = true)
                                                    @RequestBody
                                                    List<String> usernames) {
    if (usernames == null || usernames.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    return emailContactService.importDirectoryContacts(usernames, request.getRemoteUser());
  }

  @PutMapping("/{id}")
  @Secured("users")
  @Operation(summary = "Updates a contact", method = "PUT",
             description = "Edits a manual or collected contact (a collected one keeps its source; collection never overwrites a name a user set). CardDAV rows are read-only in this version. Changing the address re-checks uniqueness.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Unusable email address, or a read-only CardDAV row"),
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
