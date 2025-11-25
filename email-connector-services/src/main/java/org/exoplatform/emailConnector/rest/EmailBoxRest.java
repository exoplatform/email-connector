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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/email-box")
@Tag(name = "/email-connector/rest/email-box", description = "Manages Email Box")
public class EmailBoxRest {

  @Autowired
  private EmailBoxService emailBoxService;

  @GetMapping()
  @Secured("users")
  @Operation(summary = "Gets user emails", method = "GET", description = "This will get user emails")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public EmailBox getEmailBox(HttpServletRequest request) {
    return emailBoxService.getEmailBox(request.getRemoteUser());
  }

  @PostMapping("synchronization")
  @Secured("users")
  @Operation(summary = "Synchronize email box", method = "POST", description = "This will synchronize email box")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<String> synchronizeUserEmails(HttpServletRequest request) {
    try {
      emailBoxService.synchronize(request.getRemoteUser());
      return ResponseEntity.ok().build();
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/{emailRemoteId}")
  @Secured("users")
  @Operation(summary = "Gets user emails", method = "GET", description = "This will get user emails")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<Email> getRemoteEmailById(HttpServletRequest request,
                                                  @Parameter(description = "Email id", required = true)
                                                  @PathVariable("emailRemoteId")
                                                  long emailRemoteId,
                                                  @RequestHeader(value = "If-None-Match", required = false)
                                                  String ifNoneMatch) {
    try {
      String eTag = "\"" + Objects.hash(emailRemoteId, request.getRemoteUser()) + "\"";
      if (ifNoneMatch != null && ifNoneMatch.replace("W/", "").equals(eTag)) {
        emailBoxService.broadcastEvent(EmailConnectorUtils.OPEN_EMAIL, request.getRemoteUser());
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
      }
      Email email = emailBoxService.getRemoteEmailById(emailRemoteId, request.getRemoteUser());
      if (email == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return ResponseEntity.ok().eTag(eTag).cacheControl(CacheControl.noCache().cachePrivate()).body(email);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @PatchMapping("/{emailRemoteId}")
  @Secured("users")
  @Operation(summary = "Gets user emails", method = "PATCH", description = "This will update email read status")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public void updateEmailReadStatus(HttpServletRequest request,
                                    @Parameter(description = "Email id", required = true)
                                    @PathVariable("emailRemoteId")
                                    long emailRemoteId,
                                    @RequestParam("readStatus")
                                    boolean readStatus) {
    try {
      Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(emailRemoteId, request.getRemoteUser());
      if (email == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      emailBoxService.updateEmailReadStatus(emailRemoteId, null, request.getRemoteUser(), readStatus, true);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  @GetMapping("/attachments/{emailRemoteId}/{attachmentId}")
  @Secured("users")
  @Operation(summary = "Gets user emails", method = "GET", description = "This will get user emails")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Bad Request"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Not found"),
      @ApiResponse(responseCode = "409", description = "Conflict"), })
  public ResponseEntity<byte[]> getAttachmentByMailRemoteIdAnId(HttpServletRequest request,
                                                  @Parameter(description = "Email id", required = true)
                                                  @PathVariable("emailRemoteId")
                                                  long emailRemoteId,
                                                  @Parameter(description = "Attachment id", required = true)
                                                  @PathVariable("attachmentId")
                                                  String attachmentId) {
    try {
      EmailAttachment emailAttachment = emailBoxService.getAttachmentByMailRemoteIdAnId(emailRemoteId,
                                                                                        attachmentId,
                                                                                        request.getRemoteUser());
      if (emailAttachment == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      byte[] data = emailAttachment.getData();
      String filename = emailAttachment.getName();
      String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
      return ResponseEntity.ok()
                           .contentType(MediaType.parseMediaType(emailAttachment.getMimeType()))
                           .header(HttpHeaders.CONTENT_DISPOSITION,
                                   "inline; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                           .body(data);
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
